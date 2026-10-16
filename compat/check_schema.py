#!/usr/bin/env python3
"""Fail on backward-incompatible DB schema changes versus the base branch.

Checks every CREATE TABLE / CREATE SEQUENCE in sql/**/*.sql and
**/src/main/resources/schema.sql, plus immutability of applied Flyway
migrations (**/db/migration/V*.sql)."""
import re
from common import base_file, head_file, files_at_base, files_at_head, git, merge_base, report, exit_with

DESTRUCTIVE = [
    ("table-dropped", re.compile(r"\bDROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?([\w.]+)", re.I)),
    ("column-dropped", re.compile(r"\bALTER\s+TABLE\s+([\w.]+)\s+DROP\s+(?:COLUMN\s+)?(?!CONSTRAINT\b|PRIMARY\b|FOREIGN\b|UNIQUE\b|CHECK\b)(\w+)", re.I)),
    ("column-renamed", re.compile(r"\bALTER\s+TABLE\s+([\w.]+)\s+(?:RENAME\s+COLUMN|ALTER\s+COLUMN\s+\w+\s+RENAME)\s+(\w+)", re.I)),
    ("type-changed", re.compile(r"\bALTER\s+TABLE\s+([\w.]+)\s+(?:ALTER|MODIFY)\s+(?:COLUMN\s+)?(\w+)\s+(?:SET\s+DATA\s+)?TYPE\b", re.I)),
    ("constraint-removed", re.compile(r"\bALTER\s+TABLE\s+([\w.]+)\s+DROP\s+(CONSTRAINT\s+\w+|PRIMARY\s+KEY|FOREIGN\s+KEY\s+\w+)", re.I)),
    ("table-renamed", re.compile(r"\b(?:RENAME\s+TABLE\s+([\w.]+)|ALTER\s+TABLE\s+([\w.]+)\s+RENAME\s+TO)", re.I)),
    ("sequence-changed", re.compile(r"\b(?:DROP|ALTER)\s+SEQUENCE\s+([\w.]+)", re.I)),
]


def statements(sql):
    return {normalize_sql(st): " ".join(st.split()) for st in strip_comments(sql or "").split(";") if st.strip()}


def destructive_statements():
    """Scan SQL statements that are new or changed in this PR (whole statements,
    so a clause edited under an existing ALTER TABLE is still caught)."""
    changed = git("diff", "--name-only", "--diff-filter=AM", f"{merge_base()}...HEAD", "--", "*.sql").split()
    out = []
    for path in changed:
        old = statements(base_file(path))
        for key, stmt in statements(head_file(path)).items():
            if key in old:
                continue
            for kind, rx in DESTRUCTIVE:
                for m in rx.finditer(stmt):
                    target = ".".join(g.upper() for g in m.groups() if g)
                    out.append(("FAIL", f"schema:{target}:{kind}", f"{' '.join(m.group(0).split())} ({path})"))
    return out


def strip_comments(sql):
    return re.sub(r"--[^\n]*", "", sql)


def normalize_sql(text):
    """Drop whitespace and case outside quoted literals; keep literals verbatim."""
    parts = re.split(r"('(?:[^']|'')*')", text)
    return "".join(p if i % 2 else re.sub(r"\s+", "", p).upper() for i, p in enumerate(parts))


def split_top(body):
    parts, depth, cur = [], 0, ""
    for ch in body:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    parts.append(cur)
    return [p.strip() for p in parts if p.strip()]


def parse(sql):
    tables, seqs = {}, {}
    sql = strip_comments(sql or "")
    for m in re.finditer(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([\w.]+)\s*\(", sql, re.I):
        name, i, depth = m.group(1).upper(), m.end(), 1
        start = i
        while i < len(sql) and depth:
            depth += {"(": 1, ")": -1}.get(sql[i], 0)
            i += 1
        cols, pks = {}, set()
        for part in split_top(sql[start:i - 1]):
            up = part.upper()
            if up.startswith(("CONSTRAINT", "PRIMARY", "FOREIGN", "UNIQUE", "CHECK")):
                cm = re.match(r"CONSTRAINT\s+(\w+)\s+(PRIMARY\s+KEY|FOREIGN\s+KEY|UNIQUE|CHECK)(.*)", part, re.S | re.I)
                if cm:
                    kind = " ".join(cm.group(2).upper().split())
                    pks.add(f"{kind}:{cm.group(1).upper()}:{normalize_sql(cm.group(3))}")
                continue
            tok = part.split(None, 1)
            if len(tok) < 2:
                continue
            tm = TYPE_RE.match(tok[1].strip())
            cols[tok[0].upper()] = {
                "type": tm.group(1).upper(),
                "len": int(tm.group(2)) if tm.group(2) else None,
                "scale": int(tm.group(3)) if tm.group(3) else None,
                "notnull": "NOT NULL" in up,
                "default": "DEFAULT" in up,
                "raw": " ".join(tok[1].split()),
            }
        tables[name] = {"cols": cols, "constraints": pks}
    for m in re.finditer(r"CREATE\s+SEQUENCE\s+([\w.]+)([^;]*);", sql, re.I):
        seqs[m.group(1).upper()] = " ".join(m.group(2).split()).upper()
    return tables, seqs


def narrowed(old, new):
    if old["type"] == new["type"]:
        if old["type"] in NUMERIC:
            op, os_ = old["len"] or 0, old["scale"] or 0
            np_, ns = new["len"] or 0, new["scale"] or 0
            return ns < os_ or (np_ - ns) < (op - os_)
        return old["len"] is not None and new["len"] is not None and new["len"] < old["len"]
    if old["type"] in INT_RANK and new["type"] in INT_RANK:
        return INT_RANK[new["type"]] < INT_RANK[old["type"]]
    if {old["type"], new["type"]} <= {"CHAR", "VARCHAR"}:
        return (new["len"] or 0) < (old["len"] or 0)
    return True


def schema_files(lister):
    return [p for p in lister() if p.endswith(".sql") and (p.startswith("sql/") or p.endswith("src/main/resources/schema.sql"))]


def main():
    findings = []
    for path in sorted(set(schema_files(files_at_base))):
        old_t, old_s = parse(base_file(path))
        new_sql = head_file(path)
        if new_sql is None:
            if old_t:
                findings.append(("FAIL", f"schema:{path}:file-removed", f"{path} removed ({len(old_t)} tables)"))
            continue
        new_t, new_s = parse(new_sql)
        for t, td in old_t.items():
            if t not in new_t:
                findings.append(("FAIL", f"schema:{t}:table-dropped", f"table {t} dropped in {path}"))
                continue
            for c, cd in td["cols"].items():
                nc = new_t[t]["cols"].get(c)
                if nc is None:
                    findings.append(("FAIL", f"schema:{t}.{c}:column-dropped", f"column dropped/renamed ({path})"))
                elif narrowed(cd, nc):
                    kind = "type-narrowed" if cd["type"] == nc["type"] or {cd["type"], nc["type"]} <= set(INT_RANK) | {"CHAR", "VARCHAR"} else "type-changed"
                    findings.append(("FAIL", f"schema:{t}.{c}:{kind}", f"{cd['raw']} -> {nc['raw']} ({path})"))
                elif nc["notnull"] and not cd["notnull"] and not nc["default"]:
                    findings.append(("FAIL", f"schema:{t}.{c}:made-not-null", f"now NOT NULL without default ({path})"))
            for c, nc in new_t[t]["cols"].items():
                if c not in td["cols"] and nc["notnull"] and not nc["default"]:
                    findings.append(("FAIL", f"schema:{t}.{c}:new-required-column", f"new NOT NULL column without default ({path})"))
                elif c not in td["cols"]:
                    findings.append(("NOTE", f"schema:{t}.{c}:column-added", f"additive column ({path})"))
            for con in td["constraints"] - new_t[t]["constraints"]:
                findings.append(("FAIL", f"schema:{t}:{con}:constraint-removed", f"constraint removed ({path})"))
        for s, sd in old_s.items():
            if s not in new_s:
                findings.append(("FAIL", f"schema:{s}:sequence-dropped", f"sequence dropped ({path})"))
            elif new_s[s] != sd:
                findings.append(("FAIL", f"schema:{s}:sequence-changed", f"'{sd}' -> '{new_s[s]}' ({path})"))
    for path in files_at_base():
        if re.search(r"db/migration/V[^/]*\.sql$", path) and head_file(path) != base_file(path):
            findings.append(("FAIL", f"schema:{path}:applied-migration-modified", "applied migrations must not be edited; add a new version"))
    findings += destructive_statements()
    return report("DB schema compatibility", findings)


if __name__ == "__main__":
    exit_with(main())
