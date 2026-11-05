#!/usr/bin/env python3
"""COBOL characterization harness for PAS parity testing (AB-520).

Runs the unchanged legacy COBOL programs under GnuCOBOL against controlled fixtures and stores
what they do as golden files. EXEC SQL / EXEC CICS blocks are replaced by stubs (one per block,
see parity/cobol/stubs/) that read fixture values and print every observed effect. The legacy
source files are only read, never modified: the precompiled copy is written under target/parity/.

  python3 tools/parity/parity.py check            # validate registry, fixtures, known differences,
                                                  # and re-run COBOL: golden files must be reproduced
  python3 tools/parity/parity.py record [--suite S]   # (re)write golden files from the COBOL run
  python3 tools/parity/parity.py run --suite S --case C   # print one COBOL run
  python3 tools/parity/parity.py precompile PROGRAM       # write the harness source, print its path
"""
import argparse
import difflib
import fnmatch
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PARITY = ROOT / "parity"
REGISTRY = PARITY / "suites.json"
KNOWN_DIFFERENCES = PARITY / "known-differences.json"
QUIRKS = ROOT / "docs" / "modernization" / "quirks.json"
COPYBOOKS = ROOT / "cobol" / "copybooks"
HARNESS_COPYBOOKS = PARITY / "cobol" / "harness"
BUILD = ROOT / "target" / "parity"

CASE_ID = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
KD_ID = re.compile(r"^KD-\d{2,3}$")
TICKET = re.compile(r"^AB-5(1[7-9]|2\d|30)$")
ENV_KEY = re.compile(r"^[A-Z][A-Z0-9_]*$")
# CICS translator: DFHRESP(condition) becomes a numeric literal.
DFHRESP = {"NORMAL": 0, "ERROR": 1, "NOTFND": 13, "DUPREC": 14, "MAPFAIL": 36, "QIDERR": 44}
COBOL_VERBS = {
    "ACCEPT", "ADD", "CALL", "CLOSE", "COMPUTE", "CONTINUE", "DISPLAY", "DIVIDE", "ELSE", "END-EVALUATE",
    "END-IF", "END-PERFORM", "EVALUATE", "EXEC", "EXIT", "GO", "GOBACK", "IF", "INITIALIZE", "MOVE",
    "MULTIPLY", "OPEN", "PERFORM", "READ", "SET", "STOP", "STRING", "SUBTRACT", "WHEN", "WRITE",
}
AREA_B = " " * 11


class HarnessError(Exception):
    pass


# --------------------------------------------------------------------------------------------
# Precompiler: legacy source + stub file -> GnuCOBOL source
# --------------------------------------------------------------------------------------------

def is_comment(line):
    return len(line) > 6 and line[6] in "*/"


def code_area(line):
    return line[7:72] if len(line) > 7 else ""


def normalize(text):
    return " ".join(text.replace("\n", " ").split())


def parse_stubs(text, path="<stubs>"):
    """Parse a .stubs file into (working_storage_lines, [stub dicts in order])."""
    working_storage, stubs, current = [], [], None
    for number, raw in enumerate(text.splitlines(), 1):
        line = raw.rstrip()
        stripped = line.strip()
        if current is None:
            if not stripped or stripped.startswith("#"):
                continue
            if stripped == "@working-storage":
                current = {"kind": "ws", "lines": []}
            elif stripped.startswith("@stub "):
                parts = stripped.split(None, 2)
                if len(parts) < 3 or not parts[1].isdigit():
                    raise HarnessError(f"{path}:{number}: expected '@stub <n> <fingerprint>'")
                current = {"kind": "stub", "n": int(parts[1]), "fingerprint": normalize(parts[2]),
                           "lines": [], "line": number}
            else:
                raise HarnessError(f"{path}:{number}: unexpected line outside a section: {stripped}")
        elif stripped == "@end":
            if current["kind"] == "ws":
                working_storage.extend(current["lines"])
            else:
                stubs.append(current)
            current = None
        else:
            current["lines"].append(line)
    if current is not None:
        raise HarnessError(f"{path}: section not closed with @end")
    for expected, stub in enumerate(stubs, 1):
        if stub["n"] != expected:
            raise HarnessError(f"{path}:{stub['line']}: stub numbered {stub['n']}, expected {expected}")
    return working_storage, stubs


def quote(text):
    return "'" + text.replace("'", "''") + "'"


def expand_macro(line):
    """Expand one stub macro line (@get, @put, ...) into fixed-format COBOL lines."""
    stripped = line.strip()
    if not stripped.startswith("@"):
        if len(line) > 72:
            raise HarnessError(f"stub line longer than 72 columns: {line}")
        return [line]
    parts = stripped.split()
    macro, args = parts[0], parts[1:]

    def need(count):
        if len(args) != count:
            raise HarnessError(f"{macro} expects {count} arguments: {stripped}")

    out = []
    if macro == "@get":  # @get NAME HOST-VAR          alphanumeric host variable
        need(2)
        out = [f"MOVE {quote(args[0])} TO PX-NAME", "PERFORM PX-GET", f"MOVE PX-VALUE TO {args[1]}"]
    elif macro == "@get-num":  # @get-num NAME HOST-VAR  numeric host variable (0 when unset)
        need(2)
        out = [f"MOVE {quote(args[0])} TO PX-NAME", "PERFORM PX-GET-NUM", f"MOVE PX-NUM TO {args[1]}"]
    elif macro == "@sqlcode":  # @sqlcode STATEMENT       SQLCODE 0 unless SQLCODE_<STATEMENT> is set
        need(1)
        out = [f"MOVE {quote(args[0])} TO PX-NAME", "PERFORM PX-SQLCODE"]
    elif macro == "@db2-date":  # @db2-date HOST-VAR      DB2 DATE validation: invalid -> SQLCODE -181
        need(1)
        out = [f"MOVE {args[0]} TO PX-DATE-CHECK", "PERFORM PX-CHECK-DATE"]
    elif macro == "@effect":  # @effect OPERATION TEXT...
        if not args:
            raise HarnessError(f"@effect needs an operation: {stripped}")
        out = [f"MOVE {quote(' '.join(args))} TO PX-NAME", "PERFORM PX-EFFECT"]
    elif macro == "@effect-sqlcode":
        need(0)
        out = ["PERFORM PX-EFFECT-SQLCODE"]
    elif macro == "@text":  # @text NAME HOST-VAR
        need(2)
        out = [f"MOVE {quote(args[0])} TO PX-NAME", f"MOVE {args[1]} TO PX-VALUE",
               f"MOVE LENGTH OF {args[1]} TO PX-LEN", "PERFORM PX-PUT-TEXT"]
    elif macro == "@text-literal":  # @text-literal NAME VALUE   (SQL literal in the statement)
        need(2)
        out = [f"MOVE {quote(args[0])} TO PX-NAME", f"MOVE {quote(args[1])} TO PX-VALUE",
               f"MOVE {len(args[1])} TO PX-LEN", "PERFORM PX-PUT-TEXT"]
    elif macro in ("@num", "@probe"):  # @num NAME HOST-VAR SCALE
        need(3)
        paragraph = "PX-PUT-NUM" if macro == "@num" else "PX-PUT-PROBE"
        out = [f"MOVE {quote(args[0])} TO PX-NAME", f"MOVE {args[1]} TO PX-NUM",
               f"MOVE {args[2]} TO PX-SCALE", f"PERFORM {paragraph}"]
    elif macro == "@num-literal":  # @num-literal NAME VALUE SCALE
        need(3)
        out = [f"MOVE {quote(args[0])} TO PX-NAME", f"MOVE {args[1]} TO PX-NUM",
               f"MOVE {args[2]} TO PX-SCALE", "PERFORM PX-PUT-NUM"]
    elif macro == "@row-scope":  # @row-scope on|off      fixture names PX_R<row>_<NAME>
        need(1)
        out = [f"MOVE {quote('Y' if args[0] == 'on' else 'N')} TO PX-USE-ROW"]
    else:
        raise HarnessError(f"unknown stub macro {macro}")
    lines = [AREA_B + statement for statement in out]
    for generated in lines:
        if len(generated) > 72:
            raise HarnessError(f"generated line longer than 72 columns: {generated}")
    return lines


def divide_guards(statement_lines):
    """S0CB emulation: GnuCOBOL leaves the target unchanged on a zero divide, z/OS abends."""
    text = " ".join(code_area(line) for line in statement_lines)
    divisors = []
    for match in re.finditer(r"/\s*([A-Z][A-Z0-9-]*)", text):
        if match.group(1) not in divisors:
            divisors.append(match.group(1))
    return [f"{AREA_B}IF {name} = 0 PERFORM PX-ABEND-S0CB END-IF" for name in divisors]


def first_word(line):
    words = code_area(line).split()
    return words[0].rstrip(".") if words else ""


def precompile(source_text, stubs_text, stubs_path="<stubs>"):
    """Return the GnuCOBOL harness source for one legacy program."""
    working_storage, stubs = parse_stubs(stubs_text, stubs_path)
    lines = source_text.splitlines()
    out, used, index, in_procedure = [], 0, 0, False
    while index < len(lines):
        line = lines[index]
        if is_comment(line):
            out.append(line)
            index += 1
            continue
        area = code_area(line)
        upper = area.upper()
        if "PROCEDURE DIVISION" in upper:
            in_procedure = True
        exec_match = re.search(r"\bEXEC\s+(SQL|CICS)\b", upper)
        if exec_match:
            if area[: exec_match.start()].strip():
                raise HarnessError(f"line {index + 1}: code before EXEC is not supported")
            block, start = [], index
            while True:
                block.append(code_area(lines[index]))
                if "END-EXEC" in code_area(lines[index]).upper():
                    break
                index += 1
                if index >= len(lines):
                    raise HarnessError(f"line {start + 1}: EXEC without END-EXEC")
            index += 1
            joined = " ".join(block)
            end = joined.upper().index("END-EXEC")
            trailing = joined[end + len("END-EXEC"):].strip()
            body = normalize(joined[exec_match.start() + len("EXEC"):end])
            out.append(f"      *>PX {normalize(joined[:end + len('END-EXEC')])[:60]}")
            if body.upper() == "SQL INCLUDE SQLCA":
                out.append(f"{AREA_B}COPY PXSQLCA.")
                continue
            if used >= len(stubs):
                raise HarnessError(f"line {start + 1}: no stub for EXEC block {used + 1}: {body[:60]}")
            stub = stubs[used]
            used += 1
            if not body.upper().startswith(stub["fingerprint"].upper()):
                raise HarnessError(
                    f"{stubs_path}: stub {stub['n']} fingerprint '{stub['fingerprint']}' does not match "
                    f"EXEC block at line {start + 1}: '{body[:80]}'")
            for stub_line in stub["lines"]:
                out.extend(expand_macro(stub_line))
            if trailing:
                out.append(f"{AREA_B}{trailing}")
            continue
        if "WORKING-STORAGE SECTION" in upper:
            out.append(line)
            out.append(f"{AREA_B[:-4]}COPY PXWORK.")
            out.extend(working_storage)
            index += 1
            continue
        line = re.sub(r"DFHRESP\((\w+)\)", lambda m: str(DFHRESP[m.group(1).upper()]), line)
        if in_procedure and first_word(line).upper() == "COMPUTE":
            statement = [line]
            look = index + 1
            while not statement[-1].rstrip().endswith(".") and look < len(lines):
                nxt = lines[look]
                if not is_comment(nxt) and (first_word(nxt).upper() in COBOL_VERBS or not code_area(nxt).strip()):
                    break
                if not is_comment(nxt):
                    statement.append(nxt)
                look += 1
            out.extend(divide_guards(statement))
        out.append(line)
        index += 1
    if used != len(stubs):
        raise HarnessError(f"{stubs_path}: {len(stubs) - used} stub(s) left over; the program has {used} EXEC blocks")
    out.append(f"{AREA_B[:-4]}COPY PXPROC.")
    return "\n".join(out) + "\n"


# --------------------------------------------------------------------------------------------
# Registry, fixtures, quirks, known differences
# --------------------------------------------------------------------------------------------

def load_json(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load_registry():
    return load_json(REGISTRY)


def load_fixtures(suite):
    return load_json(PARITY / suite["fixtures"])


def fixture_env(case):
    env = {}

    def scalar(key, value):
        if isinstance(value, bool) or isinstance(value, float) or value is None:
            raise HarnessError(f"case {case['id']}: {key} must be a string or an integer (no floats)")
        return str(value)

    for key, value in case.get("input", {}).items():
        if not ENV_KEY.match(key):
            raise HarnessError(f"case {case['id']}: input key {key} must be UPPER_SNAKE_CASE")
        if key == "ROWS":
            env["PX_ROWS"] = str(len(value))
            for row_number, row in enumerate(value, 1):
                for row_key, row_value in row.items():
                    if not ENV_KEY.match(row_key):
                        raise HarnessError(f"case {case['id']}: row key {row_key} must be UPPER_SNAKE_CASE")
                    env[f"PX_R{row_number:04d}_{row_key}"] = scalar(row_key, row_value)
        else:
            env[f"PX_{key}"] = scalar(key, value)
    return env


def validate(registry, quirks, known):
    """Static checks: registry, fixtures, tag coverage, quirk links, known differences."""
    errors = []
    quirk_by_id = {q["id"]: q for q in quirks["quirks"]}
    suites = {}
    seen_tags = set()
    for suite in registry["suites"]:
        name = suite["suite"]
        if name in suites:
            errors.append(f"suite {name}: duplicate")
        suites[name] = suite
        for key in ("program", "legacy_source", "stubs", "fixtures", "owner_ticket"):
            if not suite.get(key):
                errors.append(f"suite {name}: missing {key}")
        if not TICKET.match(suite.get("owner_ticket", "")):
            errors.append(f"suite {name}: owner_ticket must be an AB-516 child ticket")
        for key in ("legacy_source", "stubs", "fixtures"):
            if suite.get(key) and not (ROOT / suite[key] if key != "fixtures" else PARITY / suite[key]).is_file():
                errors.append(f"suite {name}: {key} {suite[key]} not found")
        if not str(suite.get("legacy_source", "")).startswith("cobol/programs/"):
            errors.append(f"suite {name}: legacy_source must be a program under cobol/programs/")
        adapter = suite.get("java_adapter")
        if adapter is not None and not re.match(r"^com\.acme\.insurance\.pas\.parity\.[A-Za-z0-9_.]+$", adapter):
            errors.append(f"suite {name}: java_adapter must be a class in com.acme.insurance.pas.parity")
        if not (PARITY / suite.get("fixtures", "-")).is_file():
            continue
        fixtures = load_fixtures(suite)
        if fixtures.get("suite") != name or fixtures.get("program") != suite["program"]:
            errors.append(f"suite {name}: fixture file suite/program do not match the registry")
        ids = set()
        for case in fixtures.get("cases", []):
            case_id = case.get("id", "")
            if not CASE_ID.match(case_id):
                errors.append(f"{name}: case id '{case_id}' must be lower-kebab-case")
            if case_id in ids:
                errors.append(f"{name}: duplicate case id {case_id}")
            ids.add(case_id)
            if not case.get("description"):
                errors.append(f"{name}/{case_id}: description missing")
            if not re.match(r"^\d{4}-\d{2}-\d{2}$", case.get("run_date", "")):
                errors.append(f"{name}/{case_id}: run_date must be YYYY-MM-DD")
            for quirk in case.get("quirks", []):
                if quirk not in quirk_by_id:
                    errors.append(f"{name}/{case_id}: unknown quirk {quirk}")
            seen_tags.update(case.get("tags", []))
            try:
                fixture_env(case)
            except HarnessError as exc:
                errors.append(str(exc))
        suite["_case_ids"] = ids
        golden_dir = PARITY / "golden" / name
        on_disk = {p.stem for p in golden_dir.glob("*.json")} if golden_dir.is_dir() else set()
        for missing in sorted(ids - on_disk):
            errors.append(f"{name}/{missing}: golden file missing (run: make parity-record)")
        for orphan in sorted(on_disk - ids):
            errors.append(f"{name}/{orphan}: golden file has no fixture case")
    for tag in registry.get("required_tags", []):
        if tag not in seen_tags:
            errors.append(f"required fixture tag '{tag}' is not covered by any case")
    errors.extend(validate_known_differences(known, suites, quirk_by_id))
    return errors


def validate_known_differences(known, suites, quirk_by_id):
    errors, ids = [], set()
    for entry in known.get("differences", []):
        kd = entry.get("id", "?")
        if not KD_ID.match(kd):
            errors.append(f"{kd}: id must look like KD-01")
        if kd in ids:
            errors.append(f"{kd}: duplicate id")
        ids.add(kd)
        suite = suites.get(entry.get("suite"))
        if suite is None:
            errors.append(f"{kd}: unknown suite {entry.get('suite')}")
        else:
            for case in entry.get("cases", []):
                if case != "*" and case not in suite.get("_case_ids", set()):
                    errors.append(f"{kd}: unknown case {case} in suite {suite['suite']}")
        if not entry.get("cases"):
            errors.append(f"{kd}: cases must list case ids or '*'")
        if not entry.get("paths"):
            errors.append(f"{kd}: paths must list result paths (or '*')")
        quirk = quirk_by_id.get(entry.get("quirk"))
        if quirk is None:
            errors.append(f"{kd}: unknown quirk {entry.get('quirk')}")
        else:
            if quirk.get("proposed_decision") != "fix":
                errors.append(f"{kd}: {quirk['id']} is '{quirk.get('proposed_decision')}', only quirks the "
                              "business decided to fix may be known differences")
            anchor = f"docs/modernization/quirk-register.md#{quirk['id'].lower()}"
            if entry.get("decision") != anchor:
                errors.append(f"{kd}: decision must link the quirk decision: {anchor}")
        if not TICKET.match(entry.get("ticket", "")):
            errors.append(f"{kd}: ticket must be an AB-516 child ticket")
        sign_off = entry.get("sign_off", "")
        if sign_off != "pending" and not sign_off.startswith("approved:"):
            errors.append(f"{kd}: sign_off must be 'pending' or 'approved: <who, date, reference>'")
        if not entry.get("reason"):
            errors.append(f"{kd}: reason missing")
    return errors


# --------------------------------------------------------------------------------------------
# Compile and run
# --------------------------------------------------------------------------------------------

def cobc_path():
    return shutil.which(os.environ.get("COBC", "cobc"))


def cobc_version(cobc):
    first = subprocess.run([cobc, "--version"], capture_output=True, text=True, check=True).stdout.splitlines()
    return first[0].strip() if first else "unknown"


def build(program, suite, cobc):
    source = ROOT / suite["legacy_source"]
    stubs = ROOT / suite["stubs"]
    harness_source = precompile(source.read_text(encoding="latin-1"), stubs.read_text(encoding="utf-8"),
                                str(stubs.relative_to(ROOT)))
    (BUILD / "src").mkdir(parents=True, exist_ok=True)
    (BUILD / "bin").mkdir(parents=True, exist_ok=True)
    src = BUILD / "src" / f"{program}.cbl"
    src.write_text(harness_source, encoding="latin-1")
    exe = BUILD / "bin" / program
    command = [cobc, "-x", "-std=ibm", "-I", str(COPYBOOKS), "-I", str(HARNESS_COPYBOOKS), "-o", str(exe), str(src)]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        raise HarnessError(f"cobc failed for {program}:\n{result.stdout}{result.stderr}")
    return exe


def escape(raw):
    text = raw.rstrip(b" ").decode("latin-1")
    return "".join(ch if 32 <= ord(ch) < 127 else f"\\x{ord(ch):02X}" for ch in text)


def number(scale, value):
    quantum = Decimal(1).scaleb(-int(scale))
    return str(Decimal(value.decode("ascii").strip()).quantize(quantum))


def parse_output(stdout):
    effects, displays, abend = [], [], None
    for raw in stdout.split(b"\n"):
        if not raw.startswith(b"PX|"):
            if raw.strip():
                displays.append(escape(raw))
            continue
        kind, rest = raw[3:4], raw[5:]
        if kind == b"E":
            effects.append({"op": escape(rest), "sqlcode": None, "text": {}, "numbers": {}, "probes": {}})
        elif kind == b"C":
            effects[-1]["sqlcode"] = int(Decimal(rest.decode("ascii").strip()))
        elif kind == b"T":
            name, _, value = rest.partition(b"|")
            effects[-1]["text"][name.decode("ascii")] = escape(value)
        elif kind in (b"N", b"P"):
            name, scale, value = rest.split(b"|", 2)
            target = "numbers" if kind == b"N" else "probes"
            effects[-1][target][name.decode("ascii")] = number(scale, value)
        elif kind == b"A":
            abend = rest.decode("ascii").strip()
    for effect in effects:
        if effect["sqlcode"] is None:
            del effect["sqlcode"]
        for key in ("text", "numbers", "probes"):
            if not effect[key]:
                del effect[key]
    return effects, displays, abend


def run_case(suite, case, exe):
    env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "LANG": "C", "COB_SET_TRACE": "N",
           "COB_CURRENT_DATE": case["run_date"].replace("-", "/") + " 01:00:00"}
    env.update(fixture_env(case))
    artifacts = {}
    with tempfile.TemporaryDirectory(prefix="parity-") as work:
        for dd in suite.get("files", {}):
            env[f"DD_{dd}"] = os.path.join(work, dd)
        result = subprocess.run([str(exe)], env=env, cwd=work, capture_output=True, timeout=60)
        for dd, spec in suite.get("files", {}).items():
            path = Path(work) / dd
            data = path.read_bytes() if path.exists() else b""
            width = spec["record_length"]
            artifacts[dd] = [escape(data[i:i + width]) for i in range(0, len(data), width)]
    effects, displays, abend = parse_output(result.stdout)
    if result.stderr.strip() and abend is None:
        raise HarnessError(f"{suite['suite']}/{case['id']}: runtime error: {result.stderr.decode('latin-1')[:400]}")
    outcome = {"status": "abend" if abend else "completed", "return_code": result.returncode}
    if abend:
        outcome["abend_code"] = abend
    golden = {
        "suite": suite["suite"],
        "case": case["id"],
        "program": suite["program"],
        "description": case["description"],
        "legacy_source": {"path": suite["legacy_source"], "sha256": sha256(ROOT / suite["legacy_source"])},
        "run_date": case["run_date"],
        "outcome": outcome,
        "effects": effects,
    }
    if displays:
        golden["displays"] = displays
    if artifacts:
        golden["artifacts"] = artifacts
    return golden


def dump(data):
    return json.dumps(data, indent=2, ensure_ascii=True) + "\n"


def golden_path(suite_name, case_id):
    return PARITY / "golden" / suite_name / f"{case_id}.json"


def selected(registry, suite_filter):
    suites = [s for s in registry["suites"] if not suite_filter or fnmatch.fnmatch(s["suite"], suite_filter)]
    if not suites:
        raise HarnessError(f"no suite matches {suite_filter}")
    return suites


def run_suites(registry, suite_filter=None, case_filter=None):
    cobc = cobc_path()
    if not cobc:
        raise HarnessError("GnuCOBOL (cobc) not found. Install it (apt-get install gnucobol3) or set COBC.")
    executables, matched = {}, 0
    for suite in selected(registry, suite_filter):
        cases = [c for c in load_fixtures(suite)["cases"] if not case_filter or c["id"] == case_filter]
        if not cases:
            continue
        program = suite["program"]
        if program not in executables:
            executables[program] = build(program, suite, cobc)
        for case in cases:
            matched += 1
            yield suite, case, run_case(suite, case, executables[program])
    if case_filter and not matched:
        raise HarnessError(f"no case {case_filter} in suite(s) matching {suite_filter or '*'}")


# --------------------------------------------------------------------------------------------
# Commands
# --------------------------------------------------------------------------------------------

def command_check(args):
    registry, known = load_registry(), load_json(KNOWN_DIFFERENCES)
    errors = validate(registry, load_json(QUIRKS), known)
    for suite in registry["suites"]:
        for case_id in sorted(suite.get("_case_ids", [])):
            path = golden_path(suite["suite"], case_id)
            if path.is_file():
                recorded = load_json(path)
                if recorded.get("legacy_source", {}).get("sha256") != sha256(ROOT / suite["legacy_source"]):
                    errors.append(f"{suite['suite']}/{case_id}: golden file was recorded from a different "
                                  f"{suite['legacy_source']} (re-record)")
    cases = 0
    if errors:
        pass
    elif os.environ.get("PARITY_SKIP_COBOL") == "1":
        print("parity: PARITY_SKIP_COBOL=1, COBOL golden reproduction skipped")
    else:
        for suite, case, golden in run_suites(registry, args.suite):
            cases += 1
            path = golden_path(suite["suite"], case["id"])
            expected = path.read_text(encoding="utf-8")
            actual = dump(golden)
            if expected != actual:
                diff = "".join(difflib.unified_diff(expected.splitlines(True), actual.splitlines(True),
                                                    str(path.relative_to(ROOT)), "cobol run", n=2))
                errors.append(f"{suite['suite']}/{case['id']}: COBOL run differs from the golden file\n{diff}")
    for error in errors:
        print(f"FAIL {error}")
    suites = len(registry["suites"])
    if errors:
        print(f"== Parity harness (COBOL golden): FAIL ({len(errors)} problem(s))")
        return 1
    print(f"== Parity harness (COBOL golden): PASS ({suites} suites, {cases} COBOL runs reproduced, "
          f"{len(known.get('differences', []))} known differences valid)")
    return 0


def command_record(args):
    registry = load_registry()
    errors = [e for e in validate(registry, load_json(QUIRKS), load_json(KNOWN_DIFFERENCES))
              if "golden file" not in e]
    if errors:
        for error in errors:
            print(f"FAIL {error}")
        return 1
    count = 0
    for suite in selected(registry, args.suite):
        golden_dir = PARITY / "golden" / suite["suite"]
        golden_dir.mkdir(parents=True, exist_ok=True)
        ids = {case["id"] for case in load_fixtures(suite)["cases"]}
        for stale in golden_dir.glob("*.json"):
            if stale.stem not in ids:
                stale.unlink()
    for suite, case, golden in run_suites(registry, args.suite):
        golden_path(suite["suite"], case["id"]).write_text(dump(golden), encoding="utf-8")
        count += 1
    print(f"parity: recorded {count} golden file(s) with {cobc_version(cobc_path())}")
    return 0


def command_run(args):
    for _, _, golden in run_suites(load_registry(), args.suite, args.case):
        sys.stdout.write(dump(golden))
    return 0


def command_precompile(args):
    for suite in load_registry()["suites"]:
        if suite["program"] == args.program:
            source = ROOT / suite["legacy_source"]
            text = precompile(source.read_text(encoding="latin-1"), (ROOT / suite["stubs"]).read_text(encoding="utf-8"))
            (BUILD / "src").mkdir(parents=True, exist_ok=True)
            out = BUILD / "src" / f"{args.program}.cbl"
            out.write_text(text, encoding="latin-1")
            print(out)
            return 0
    raise HarnessError(f"no suite for program {args.program}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    check = sub.add_parser("check")
    check.add_argument("--suite")
    record = sub.add_parser("record")
    record.add_argument("--suite")
    run = sub.add_parser("run")
    run.add_argument("--suite", required=True)
    run.add_argument("--case")
    pre = sub.add_parser("precompile")
    pre.add_argument("program")
    args = parser.parse_args(argv)
    try:
        return {"check": command_check, "record": command_record, "run": command_run,
                "precompile": command_precompile}[args.command](args)
    except HarnessError as exc:
        print(f"FAIL {exc}")
        print("== Parity harness (COBOL golden): FAIL")
        return 1


if __name__ == "__main__":
    sys.exit(main())
