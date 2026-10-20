#!/usr/bin/env python3
"""Independent oracle for the AB-527 extract parity goldens.

There is no mainframe to capture DAILY-EXTRACT / MONTHLY-EXPOSURE output from (and per Q-31 the
JCL as written never writes its output datasets), so the reference files are derived here, in a
separate language and database engine from the Java implementation:

1. The SQL is read *verbatim* from the SYSIN DD of ``jcl/DAILY-EXTRACT.jcl`` and
   ``jcl/MONTHLY-EXPOSURE.jcl`` at run time, so a JCL change shows up as a parity failure.
2. Three documented, mechanical rewrites map DB2 semantics onto SQLite (see ``rewrite``):
   ``CURRENT DATE`` -> the run's business date; ORDER BY keys get DB2 for z/OS EBCDIC (CCSID 37)
   collation and ``NULLS LAST`` (DB2 sorts NULL high); MONTHLY-EXPOSURE gets the remaining GROUP BY
   columns as a deterministic tiebreak (the JCL leaves order inside POLICY_TYPE/BRANCH_CODE undefined).
3. Rows are rendered with the record layouts in ``docs/modernization/extract-layouts.md``,
   implemented independently below (no shared code with pas-common ``RecordLayout``).

Usage: ``oracle.py update`` rewrites golden/; ``oracle.py check`` regenerates into a temp dir and
diffs byte-for-byte against the committed goldens (exit 1 on any difference).
"""
import csv
import datetime as dt
import functools
import json
import re
import sqlite3
import sys
import tempfile
from decimal import Decimal
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
NULL = "\\N"
RECORD_CHARSET = "iso-8859-1"
EOL = b"\r\n"

CHAR_COLUMNS = {
    "POLICY_NUMBER", "POLICY_TYPE", "POLICY_STATUS", "POLICYHOLDER_ID", "AGENT_CODE",
    "BRANCH_CODE", "UW_STATUS", "COVERAGE_TYPE", "DESCRIPTION", "STATUS", "RATING_TERRITORY",
    "CLASS_CODE",
}
MONEY_COLUMNS = {
    "total_premium", "deductible", "coverage_limit", "premium",
}

# (name, kind, digits/length, scale)
POLICY_LAYOUT = [
    ("POLICY_NUMBER", "X", 12, 0), ("POLICY_TYPE", "X", 3, 0), ("POLICY_STATUS", "X", 2, 0),
    ("EFFECTIVE_DATE", "D", 8, 0), ("EXPIRY_DATE", "D", 8, 0), ("POLICYHOLDER_ID", "X", 10, 0),
    ("AGENT_CODE", "X", 6, 0), ("BRANCH_CODE", "X", 4, 0), ("TOTAL_PREMIUM", "S", 11, 2),
    ("DEDUCTIBLE", "S", 9, 2), ("COVERAGE_LIMIT", "S", 13, 2), ("UW_STATUS", "X", 2, 0),
    ("RISK_SCORE", "S", 5, 0), (None, "F", 107, 0),
]
COVERAGE_LAYOUT = [
    ("POLICY_NUMBER", "X", 12, 0), ("SEQUENCE_NUM", "S", 5, 0), ("COVERAGE_TYPE", "X", 4, 0),
    ("DESCRIPTION", "X", 40, 0), ("COVERAGE_LIMIT", "S", 13, 2), ("DEDUCTIBLE", "S", 9, 2),
    ("PREMIUM", "S", 11, 2), ("EFFECTIVE_DATE", "D", 8, 0), ("EXPIRY_DATE", "D", 8, 0),
    ("STATUS", "X", 2, 0), (None, "F", 38, 0),
]
EXPOSURE_LRECL = 300
OUTPUTS = {
    "dailyExtractJob": [
        ("policy_extract.dat", "DAILY-EXTRACT.jcl", 0, POLICY_LAYOUT, 200),
        ("coverage_extract.dat", "DAILY-EXTRACT.jcl", 1, COVERAGE_LAYOUT, 150),
    ],
    "monthlyExposureJob": [
        ("exposure_monthly.csv", "MONTHLY-EXPOSURE.jcl", 0, None, EXPOSURE_LRECL),
    ],
}
POSITIVE = "{ABCDEFGHI"
NEGATIVE = "}JKLMNOPQR"


def jcl_sql(jcl_name, index):
    """Return the index-th SYSIN DD * statement of a JCL member, verbatim."""
    lines = (REPO / "jcl" / jcl_name).read_text(encoding="utf-8").splitlines()
    blocks, current = [], None
    for line in lines:
        if line.startswith("//SYSIN") and "DD *" in line:
            current = []
        elif current is not None and (line.startswith("/*") or line.startswith("//")):
            blocks.append("\n".join(current))
            current = None
        elif current is not None:
            current.append(line[:72])
    return blocks[index].strip().rstrip(";")


def rewrite(sql, business_date):
    """Apply the documented DB2 -> SQLite rewrites. Anything else stays verbatim."""
    sql, n = re.subn(r"CURRENT DATE - 1 DAY", "date(:business_date, '-1 day')", sql)
    assert n <= 1
    head, order = re.split(r"\bORDER BY\b", sql)
    keys = [k.strip() for k in order.split(",")]
    if "GROUP BY" in head:
        group = re.search(r"GROUP BY(.*)$", head, re.S).group(1)
        keys += [g.strip() for g in group.split(",") if g.strip() not in keys]
    rendered = []
    for key in keys:
        column = key.split(".")[-1]
        collate = " COLLATE EBCDIC" if column in CHAR_COLUMNS else ""
        rendered.append(f"{key}{collate} NULLS LAST")
    return f"{head}ORDER BY {', '.join(rendered)}"


def ebcdic_compare(a, b):
    ka, kb = a.encode("cp037"), b.encode("cp037")
    return (ka > kb) - (ka < kb)


def load(conn, run_dir):
    for table, path in (("POLICIES", "policies.csv"), ("COVERAGES", "coverages.csv")):
        with (run_dir / path).open(encoding="utf-8", newline="") as f:
            reader = csv.DictReader(f)
            columns = reader.fieldnames
            conn.execute(f"CREATE TABLE {table} ({', '.join(c.upper() for c in columns)})")
            for row in reader:
                values = []
                for c in columns:
                    v = row[c]
                    if v == NULL:
                        values.append(None)
                    elif c in MONEY_COLUMNS:
                        values.append(int(Decimal(v) * 100))  # exact cents
                    elif c in ("sequence_num", "risk_score", "renewal_count"):
                        values.append(int(v))
                    elif c.upper() in CHAR_COLUMNS:
                        values.append(v.rstrip(" "))  # DB2 CHAR/VARCHAR compare ignoring pad
                    else:
                        values.append(v)
                conn.execute(f"INSERT INTO {table} VALUES ({', '.join('?' * len(values))})",
                             values)


def zoned(value, digits):
    """Signed DISPLAY numeric with IBM trailing overpunch; value is already unscaled (cents)."""
    unscaled = 0 if value is None else value
    text = f"{abs(unscaled):0{digits}d}"
    if len(text) > digits:
        raise ValueError(f"{value} overflows {digits} digits")
    table = NEGATIVE if unscaled < 0 else POSITIVE
    return text[:-1] + table[int(text[-1])]


def fixed_record(row, layout, lrecl):
    out = []
    for name, kind, size, scale in layout:
        value = row.get(name)
        if kind == "X":
            text = "" if value is None else value
            if len(text) > size:
                raise ValueError(f"{name}={text!r} longer than {size}")
            out.append(text.ljust(size))
        elif kind == "D":
            out.append("00000000" if value is None else value.replace("-", ""))
        elif kind == "S":
            out.append(zoned(value, size))
        else:
            out.append(" " * size)
    record = "".join(out).encode(RECORD_CHARSET)
    assert len(record) == lrecl, (len(record), lrecl)
    return record


def csv_field(value, money):
    if value is None:
        return ""
    if money:
        return str(Decimal(value).scaleb(-2).quantize(Decimal("0.01")))
    text = str(value)
    if any(ch in text for ch in ',"\r\n'):
        text = '"' + text.replace('"', '""') + '"'
    return text


def exposure_record(fields, lrecl):
    line = ",".join(fields).encode(RECORD_CHARSET)
    if len(line) > lrecl:
        raise ValueError(f"exposure line longer than LRECL {lrecl}: {line!r}")
    return line.ljust(lrecl, b" ")


def render(cursor, layout, lrecl):
    names = [d[0] for d in cursor.description]
    records = []
    if layout is None:
        records.append(exposure_record(names, lrecl))
        money = {"TOTAL_EXPOSURE", "TOTAL_PREMIUM", "TOTAL_DEDUCTIBLE"}
        for row in cursor:
            records.append(exposure_record(
                [csv_field(v, n in money) for n, v in zip(names, row)], lrecl))
    else:
        for row in cursor:
            records.append(fixed_record(dict(zip(names, row)), layout, lrecl))
    return b"".join(r + EOL for r in records)


def generate(out_root):
    runs = json.loads((HERE / "runs.json").read_text(encoding="utf-8"))["runs"]
    for run in runs:
        conn = sqlite3.connect(":memory:")
        conn.create_collation("EBCDIC", ebcdic_compare)
        load(conn, HERE / "fixtures" / run["id"])
        for file_name, jcl, index, layout, lrecl in OUTPUTS[run["job"]]:
            sql = rewrite(jcl_sql(jcl, index), run["businessDate"])
            cursor = conn.execute(sql, {"business_date": run["businessDate"]})
            target = out_root / run["id"] / file_name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(render(cursor, layout, lrecl))
        conn.close()
    return runs


def main(argv):
    mode = argv[1] if len(argv) > 1 else "check"
    if mode == "update":
        generate(HERE / "golden")
        print("golden/ regenerated")
        return 0
    with tempfile.TemporaryDirectory() as tmp:
        runs = generate(Path(tmp))
        failures = 0
        for run in runs:
            for file_name, *_ in OUTPUTS[run["job"]]:
                got = (Path(tmp) / run["id"] / file_name).read_bytes()
                want_path = HERE / "golden" / run["id"] / file_name
                if not want_path.exists() or want_path.read_bytes() != got:
                    print(f"DIFF {run['id']}/{file_name}")
                    failures += 1
        print(f"oracle check: {len(runs)} runs, {failures} difference(s)")
        return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
