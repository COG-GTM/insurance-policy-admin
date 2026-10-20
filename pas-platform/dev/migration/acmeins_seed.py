#!/usr/bin/env python3
"""AB-529: emit the ACMEINS stand-in seed (java-facade schema.sql + data.sql + acmeins-extra.sql).

--dialect h2        unchanged (H2 MODE=DB2, as the facade uses it)
--dialect postgres  schema renamed (default acmeins_src); H2 `MERGE ... KEY (...) VALUES` becomes
                    `INSERT ... VALUES ... ON CONFLICT DO NOTHING`, so the seed is re-runnable.
The legacy files are only read, never modified.
"""
import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[3]
FILES = [
    ROOT / "java-facade/src/main/resources/schema.sql",
    ROOT / "java-facade/src/main/resources/data.sql",
    pathlib.Path(__file__).resolve().parent / "acmeins-extra.sql",
]
MERGE = re.compile(r"MERGE\s+INTO\s+(\S+)\s*(\([^)]*\))\s*KEY\s*\([^)]*\)\s*VALUES", re.I | re.S)


def statements(text):
    text = "\n".join(l for l in text.splitlines() if not l.strip().startswith("--"))
    return [s.strip() for s in text.split(";") if s.strip()]


def to_postgres(stmt, schema):
    stmt = re.sub(r"(CREATE\s+INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?)ACMEINS\.", r"\1", stmt, flags=re.I)
    stmt = re.sub(r"\bACMEINS\b", schema, stmt)
    if MERGE.match(stmt):
        stmt = MERGE.sub(r"INSERT INTO \1 \2 VALUES", stmt) + " ON CONFLICT DO NOTHING"
    elif re.match(r"INSERT\s+INTO", stmt, re.I):
        stmt += " ON CONFLICT DO NOTHING"
    return stmt


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dialect", choices=["h2", "postgres"], default="postgres")
    ap.add_argument("--schema", default="acmeins_src")
    args = ap.parse_args()
    for f in FILES:
        for stmt in statements(f.read_text(encoding="utf-8")):
            if args.dialect == "postgres":
                stmt = to_postgres(stmt, args.schema)
            sys.stdout.write(stmt + ";\n")


if __name__ == "__main__":
    main()
