#!/usr/bin/env python3
"""COBOL parity harness (AB-520).

Preprocesses the real z/OS COBOL programs so that every EXEC SQL / EXEC CICS
block becomes a call into a fixture-fed stub paragraph (STUB-*), compiles the
result with GnuCOBOL, runs it against the committed fixtures, and compares
program-emitted PARITY| lines against the committed golden CSVs.

Subcommands:
    check   (default) regenerate and diff against committed goldens
    update  regenerate and overwrite committed goldens
Options:
    --program NAME   only process parity/programs/<name>
"""
import argparse
import csv
import difflib
import json
import os
import re
import shutil
import subprocess
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PARITY_DIR = REPO_ROOT / "parity"
PROGRAMS_DIR = PARITY_DIR / "programs"
BUILD_DIR = PARITY_DIR / "build"
HARNESS_COBOL_DIR = PARITY_DIR / "tools" / "cobol"
COBOL_COPYBOOKS_DIR = REPO_ROOT / "cobol" / "copybooks"
EXPECTED_COBC = "3.1.2"
EOF_SENTINEL = "*EOF*"

STMT_INDENT = " " * 11   # area B
AREA_A = " " * 7         # area A


class HarnessError(Exception):
    pass


# --------------------------------------------------------------------------
# PIC parsing and fixture encoding
# --------------------------------------------------------------------------

_X_PIC = re.compile(r"^X\((\d+)\)$")
_NUM_PIC = re.compile(r"^(S)?9\((\d+)\)(?:V(9+)(?:\((\d+)\))?)?$")


def parse_pic(pic):
    """Parse a DISPLAY PIC string. Returns (kind, int_digits, dec_digits, signed)."""
    p = re.sub(r"\s+", "", pic.upper())
    m = _X_PIC.match(p)
    if m:
        return ("X", int(m.group(1)), 0, False)
    m = _NUM_PIC.match(p)
    if m:
        int_digits = int(m.group(2))
        dec_digits = 0
        if m.group(3):
            dec_digits = int(m.group(4)) if m.group(4) else len(m.group(3))
        return ("9", int_digits, dec_digits, bool(m.group(1)))
    raise HarnessError(f"unsupported PIC {pic!r}")


def encode_field(name, pic, raw):
    """Encode one CSV fixture value into its DISPLAY storage image."""
    kind, int_digits, dec_digits, signed = parse_pic(pic)
    if kind == "X":
        if len(raw) > int_digits:
            raise HarnessError(
                f"fixture {name}: {raw!r} longer than PIC X({int_digits})")
        return raw.ljust(int_digits)
    if raw.strip() == "":
        raise HarnessError(f"fixture {name}: blank numeric value")
    try:
        value = Decimal(raw.strip())
    except InvalidOperation:
        raise HarnessError(f"fixture {name}: {raw!r} is not numeric")
    if value < 0 and not signed:
        raise HarnessError(f"fixture {name}: negative {raw!r} for unsigned PIC")
    if value.as_tuple().exponent < -dec_digits:
        raise HarnessError(
            f"fixture {name}: {raw!r} has more than {dec_digits} decimals")
    digits = format(abs(value), f".{dec_digits}f").replace(".", "")
    if len(digits) > int_digits + dec_digits:
        raise HarnessError(
            f"fixture {name}: {raw!r} has too many integer digits for PIC")
    digits = digits.zfill(int_digits + dec_digits)
    return ("-" if value < 0 else "+") + digits if signed else digits


def cobol_name(name):
    return "HX-" + name.upper().replace("_", "-")


def generate_hxfix(spec):
    lines = ["       01  HX-FIXTURE.",
             "           05  HX-CASE-ID           PIC X(20)."]
    for field in spec["input"]:
        pic = field["pic"]
        kind, int_digits, dec_digits, signed = parse_pic(pic)
        clause = f"PIC {pic.upper()}"
        if signed:
            clause += " SIGN LEADING SEPARATE"
        # keep the terminating period inside fixed-format column 72:
        # emit the PIC clause on its own line for long names/clauses
        lines.append(f"           05  {cobol_name(field['name'])}")
        lines.append(f"               {clause}.")
    return "\n".join(lines) + "\n"


def encode_record(spec, row):
    record = row["case_id"]
    if len(record) > 20:
        raise HarnessError(f"case_id {record!r} longer than 20 chars")
    record = record.ljust(20)
    for field in spec["input"]:
        record += encode_field(field["name"], field["pic"], row[field["name"]])
    return record


# --------------------------------------------------------------------------
# COBOL preprocessor
# --------------------------------------------------------------------------

_PARA_RE = re.compile(r"^ {0,3}([A-Za-z0-9]+(?:-[A-Za-z0-9]+)+)\.\s*$")
_EXEC_RE = re.compile(r"\bEXEC\s+(SQL|CICS)\b")
_END_EXEC_RE = re.compile(r"\bEND-EXEC\b")
_DFH_RE = re.compile(r"DFHRESP\(([^)]+)\)")
_COMMENT_CHARS = "*/"


def _normalize_exec_text(text):
    """Collapse whitespace runs outside quoted literals; quoted content is
    kept verbatim ('' is an escaped quote inside a '...' literal)."""
    out = []
    quote = None
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if quote:
            out.append(ch)
            if ch == quote:
                # '' or "" inside the literal is an escaped quote
                if i + 1 < n and text[i + 1] == quote:
                    out.append(quote)
                    i += 1
                else:
                    quote = None
            i += 1
            continue
        if ch in "'\"":
            quote = ch
            out.append(ch)
        elif ch.isspace():
            while i + 1 < n and text[i + 1].isspace():
                i += 1
            out.append(" ")
        else:
            out.append(ch)
        i += 1
    return "".join(out).strip()


def _stub_prefix(paragraph):
    pfx = paragraph.split("-", 1)[0]
    return pfx if pfx[:1].isdigit() else paragraph


def preprocess_source(source):
    """Return (generated_source, [(stub_name, exec_text), ...])."""
    lines = source.splitlines()
    out = []
    stubs = []
    counters = {}
    paragraph = "MAIN"
    ws_done = False
    in_data = False
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        # fixed-format comment lines (indicator '*' or '/' in column 7)
        # pass through untouched
        if len(line) > 6 and line[6] in _COMMENT_CHARS:
            out.append(line)
            i += 1
            continue
        if stripped == "DATA DIVISION.":
            in_data = True
        if stripped == "PROCEDURE DIVISION.":
            in_data = False
        # insert harness WS copybooks at end of WORKING-STORAGE
        if not ws_done and stripped in ("LINKAGE SECTION.", "PROCEDURE DIVISION."):
            out.append(f"{AREA_A}COPY HXFIX.")
            out.append(f"{AREA_A}COPY HXWS.")
            ws_done = True
        # paragraph headers only when the name starts in area A
        # (columns 8-11, i.e. <= 3 leading spaces after the indicator col)
        pm = _PARA_RE.match(line[7:]) if len(line) > 7 else None
        if pm and not in_data:
            paragraph = pm.group(1)
        m = _EXEC_RE.search(line)
        if m:
            # gather the whole EXEC block (single or multi line)
            block = [line]
            while not _END_EXEC_RE.search(block[-1]):
                i += 1
                block.append(lines[i])
            # join lines first, then normalize whitespace outside quotes only
            text = _normalize_exec_text(" ".join(b.strip() for b in block))
            if in_data:
                if "INCLUDE SQLCA" in text:
                    out.append(f"{AREA_A}COPY HXSQLCA.")
                else:
                    raise HarnessError(f"unsupported DATA DIVISION EXEC: {text[:80]}")
            else:
                counters[paragraph] = counters.get(paragraph, 0) + 1
                name = f"STUB-{_stub_prefix(paragraph)}-{counters[paragraph]}"
                stubs.append((name, text))
                last = block[-1]
                tail = last.split("END-EXEC", 1)[1].strip()
                stmt = f"{STMT_INDENT}PERFORM {name}"
                if tail:
                    stmt += f" {tail}"
                out.append(stmt)
            i += 1
            continue
        # DFHRESP
        def _dfh(mm):
            if mm.group(1).strip() != "NORMAL":
                raise HarnessError(f"unsupported {mm.group(0)}")
            return "0"
        line = _DFH_RE.sub(_dfh, line)
        out.append(line)
        i += 1
    out.append(f"{AREA_A}COPY HXSTUBS.")
    out.append(f"{AREA_A}COPY HXPROC.")
    return "\n".join(out) + "\n", stubs


# --------------------------------------------------------------------------
# fixtures / golden IO
# --------------------------------------------------------------------------

def load_spec(prog_dir):
    return json.loads((prog_dir / "spec.json").read_text())


def load_fixtures(prog_dir):
    with open(prog_dir / "fixtures.csv", newline="") as fh:
        reader = csv.DictReader(fh)
        rows = []
        for r in reader:
            if r.get("case_id") is None:
                continue
            rows.append(r)
    return rows


def stub_names_in(stubs_cpy):
    return set(re.findall(r"^\s*(STUB-[A-Za-z0-9-]+)\s*\.", stubs_cpy, re.M))


def process_program(prog_dir, cobc):
    prog = prog_dir.name
    spec = load_spec(prog_dir)
    bdir = BUILD_DIR / prog
    bdir.mkdir(parents=True, exist_ok=True)

    (bdir / "HXFIX.cpy").write_text(generate_hxfix(spec))
    stubs_cpy = (prog_dir / "stubs.cpy").read_text()
    (bdir / "HXSTUBS.cpy").write_text(stubs_cpy)

    source = (REPO_ROOT / spec["source"]).read_text()
    generated, stubs = preprocess_source(source)
    gen_path = bdir / Path(spec["source"]).name
    gen_path.write_text(generated)

    generated_names = [n for n, _ in stubs]
    defined = stub_names_in(stubs_cpy)
    missing = [n for n in generated_names if n not in defined]
    extra = sorted(defined - set(generated_names))
    if missing or extra:
        raise HarnessError(
            f"{prog}: stub set mismatch; missing from stubs.cpy: {missing or 'none'}; "
            f"extra in stubs.cpy: {extra or 'none'}")

    manifest_path = bdir / "exec-blocks.txt"
    manifest_path.write_text(
        "".join(f"{n} {t}\n" for n, t in stubs))

    binary = bdir / prog
    cmd = [cobc, "-x", "-std=ibm",
           "-I", str(bdir), "-I", str(COBOL_COPYBOOKS_DIR),
           "-I", str(HARNESS_COBOL_DIR),
           "-o", str(binary), str(gen_path)]
    res = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True)
    if res.returncode != 0:
        raise HarnessError(f"{prog}: cobc failed\n{res.stderr}")

    rows = load_fixtures(prog_dir)
    records = [encode_record(spec, r) for r in rows]
    outputs = spec["output"]
    results = []
    # PREMBAT ends RC 4 when it counted errors; a spec may expect that
    expected_rc = int(spec.get("expected_rc", 0))
    if spec.get("mode") == "batch":
        stdin = "".join(r + "\n" for r in records) + EOF_SENTINEL + "\n"
        res = subprocess.run([str(binary)], cwd=bdir, input=stdin,
                             capture_output=True, text=True)
        if res.returncode != expected_rc:
            raise HarnessError(
                f"{prog}: rc={res.returncode} (expected {expected_rc})\n{res.stderr}")
        lines = [l for l in res.stdout.splitlines() if l.startswith("PARITY|")]
        if len(lines) != len(rows):
            raise HarnessError(
                f"{prog}: expected {len(rows)} PARITY lines, got {len(lines)}\n"
                f"stdout:\n{res.stdout}\nstderr:\n{res.stderr}")
        results = lines
    else:
        for rec in records:
            res = subprocess.run([str(binary)], cwd=bdir, input=rec + "\n",
                                 capture_output=True, text=True)
            if res.returncode != expected_rc:
                raise HarnessError(
                    f"{prog}: rc={res.returncode} (expected {expected_rc}) for {rec[:20].strip()}\n{res.stderr}")
            lines = [l for l in res.stdout.splitlines() if l.startswith("PARITY|")]
            if len(lines) != 1:
                raise HarnessError(
                    f"{prog}: expected 1 PARITY line for {rec[:20].strip()}, "
                    f"got {len(lines)}\nstdout:\n{res.stdout}\nstderr:\n{res.stderr}")
            results.append(lines[0])

    golden_rows = []
    for row, line in zip(rows, results):
        fields = line[len("PARITY|"):].split("|")
        if len(fields) != 1 + len(outputs):
            raise HarnessError(
                f"{prog}: {row['case_id']}: expected {1 + len(outputs)} fields, "
                f"got {len(fields)}: {line!r}")
        if fields[0] != row["case_id"]:
            raise HarnessError(
                f"{prog}: case_id mismatch {fields[0]!r} != {row['case_id']!r}")
        golden_rows.append(fields)

    out_path = bdir / "golden.csv"
    with open(out_path, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["case_id"] + outputs)
        w.writerows(golden_rows)
    return out_path, manifest_path


def main(argv=None):
    ap = argparse.ArgumentParser(description="COBOL parity harness")
    ap.add_argument("command", nargs="?", default="check",
                    choices=["check", "update"])
    ap.add_argument("--program", default=None)
    args = ap.parse_args(argv)

    cobc = shutil.which("cobc")
    if cobc is None:
        print("WARNING: cobc (GnuCOBOL) not found; skipping COBOL parity check")
        return 2 if os.environ.get("PARITY_REQUIRE_COBC") == "1" else 0
    ver = subprocess.run([cobc, "--version"], capture_output=True,
                         text=True).stdout.splitlines()[0]
    print(ver)
    if EXPECTED_COBC not in ver:
        print(f"WARNING: expected GnuCOBOL {EXPECTED_COBC}, got: {ver}")

    dirs = sorted(PROGRAMS_DIR.iterdir())
    if args.program:
        dirs = [d for d in dirs if d.name == args.program]
    failures = 0
    for d in dirs:
        if not (d / "spec.json").exists():
            continue
        try:
            generated, manifest = process_program(d, cobc)
        except HarnessError as e:
            print(str(e), file=sys.stderr)
            failures += 1
            continue
        committed = d / "golden.csv"
        committed_manifest = d / "exec-blocks.txt"
        if args.command == "update":
            shutil.copyfile(generated, committed)
            shutil.copyfile(manifest, committed_manifest)
            print(f"{d.name}: updated {committed.relative_to(REPO_ROOT)}")
        else:
            new_m = manifest.read_text().splitlines(keepends=True)
            old_m = committed_manifest.read_text().splitlines(keepends=True) \
                if committed_manifest.exists() else []
            if new_m != old_m:
                print(f"{d.name}: EXEC SQL/CICS statements changed — review "
                      f"stubs.cpy/fixtures, then make parity-golden")
                sys.stdout.writelines(difflib.unified_diff(
                    old_m, new_m, str(committed_manifest), str(manifest)))
                failures += 1
            new = generated.read_text().splitlines(keepends=True)
            old = committed.read_text().splitlines(keepends=True) \
                if committed.exists() else []
            if new != old:
                print(f"{d.name}: golden drift detected:")
                sys.stdout.writelines(difflib.unified_diff(
                    old, new, str(committed), str(generated)))
                failures += 1
            elif new_m == old_m:
                print(f"{d.name}: golden matches")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
