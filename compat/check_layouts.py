#!/usr/bin/env python3
"""Fail on changes to COBOL record layouts and JCL dataset record lengths.

Downstream consumers (Claims, Actuarial, document management) read these
records byte-for-byte, so field offsets/lengths and total record length must
not change. Carving a new field out of FILLER keeps the record compatible."""
import math
import re
from common import base_file, head_file, files_at_base, report, exit_with

LINE_RE = re.compile(r"^\s*(\d\d)\s+([\w-]+)(.*)$")


def pic_size(pic, usage):
    pic = pic.upper()
    expanded = re.sub(r"(.)\((\d+)\)", lambda m: m.group(1) * int(m.group(2)), pic)
    digits = sum(1 for ch in expanded if ch in "9PZ")
    if usage in ("COMP-3", "PACKED-DECIMAL"):
        return math.ceil((digits + 1) / 2)
    if usage in ("COMP", "COMP-4", "BINARY", "COMP-5"):
        return 2 if digits <= 4 else 4 if digits <= 9 else 8
    return len([ch for ch in expanded if ch not in "SV"])


def parse_copybook(text):
    """Return (fields, total_length). fields: name -> (offset, size, pic)."""
    stmts, buf = [], ""
    for raw in (text or "").splitlines():
        line = raw[6:72] if len(raw) > 6 else ""
        if line[:1] == "*" or not line.strip():
            continue
        buf += " " + line.strip()
        if buf.rstrip().endswith("."):
            stmts.append(buf.strip().rstrip("."))
            buf = ""
    fields, offset, filler = {}, 0, 0
    redefine_stack = []
    for s in stmts:
        m = LINE_RE.match(s)
        if not m or m.group(1) == "88":
            continue
        level, name, rest = int(m.group(1)), m.group(2).upper(), m.group(3).upper()
        while redefine_stack and level <= redefine_stack[-1][0]:
            offset = redefine_stack.pop()[1]
        if "REDEFINES" in rest:
            redefine_stack.append((level, offset))
        pm = re.search(r"PIC(?:TURE)?\s+(?:IS\s+)?(\S+)", rest)
        if not pm:
            continue
        usage = next((u for u in ("COMP-3", "PACKED-DECIMAL", "COMP-5", "COMP-4", "COMP", "BINARY") if re.search(rf"\b{u}\b", rest)), "DISPLAY")
        occurs = re.search(r"OCCURS\s+(\d+)", rest)
        size = pic_size(pm.group(1), usage) * (int(occurs.group(1)) if occurs else 1)
        if name == "FILLER":
            filler += 1
            name = f"FILLER#{filler}"
        fields[name] = (offset, size, f"{pm.group(1)} {usage}")
        offset += size
    while redefine_stack:
        redefine_stack.pop()
    total = max((o + s for o, s, _ in fields.values()), default=0)
    return fields, total


def jcl_lrecls(text):
    out, dd = {}, None
    for line in (text or "").splitlines():
        m = re.match(r"^//(\S+)\s+DD\b", line)
        if m:
            dd = m.group(1)
        lm = re.search(r"LRECL=(\d+)", line)
        if lm and dd:
            out[dd] = int(lm.group(1))
    return out


def main():
    findings = []
    for path in files_at_base():
        if re.search(r"\.cpy$", path, re.I):
            old_f, old_len = parse_copybook(base_file(path))
            new_text = head_file(path)
            if new_text is None:
                findings.append(("FAIL", f"layout:{path}:removed", "copybook removed"))
                continue
            new_f, new_len = parse_copybook(new_text)
            if new_len != old_len:
                findings.append(("FAIL", f"layout:{path}:record-length", f"record length {old_len} -> {new_len} bytes"))
            for name, (off, size, pic) in old_f.items():
                if name.startswith("FILLER#"):
                    continue
                if name not in new_f:
                    findings.append(("FAIL", f"layout:{path}:{name}:removed", f"field removed/renamed (was offset {off}, {pic})"))
                elif new_f[name][2] != pic and new_f[name][:2] == (off, size):
                    findings.append(("FAIL", f"layout:{path}:{name}:type-changed", f"{pic} -> {new_f[name][2]} at offset {off}"))
                elif new_f[name][:2] != (off, size):
                    n = new_f[name]
                    findings.append(("FAIL", f"layout:{path}:{name}:moved", f"offset/size {off}/{size} -> {n[0]}/{n[1]} ({pic} -> {n[2]})"))
            for name in new_f.keys() - old_f.keys():
                if not name.startswith("FILLER#"):
                    findings.append(("NOTE", f"layout:{path}:{name}:added", f"new field at offset {new_f[name][0]}"))
        elif path.lower().endswith(".jcl"):
            old, new = jcl_lrecls(base_file(path)), jcl_lrecls(head_file(path))
            if head_file(path) is None:
                findings.append(("FAIL", f"layout:{path}:removed", "JCL job removed; replacement must be approved"))
                continue
            for dd, lrecl in old.items():
                if dd not in new:
                    findings.append(("FAIL", f"layout:{path}:{dd}:dataset-removed", f"output DD {dd} (LRECL={lrecl}) removed"))
                elif new[dd] != lrecl:
                    findings.append(("FAIL", f"layout:{path}:{dd}:lrecl", f"LRECL {lrecl} -> {new[dd]}"))
    return report("Record & file layout compatibility", findings)


if __name__ == "__main__":
    exit_with(main())
