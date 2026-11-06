#!/usr/bin/env python3
"""PAS discovery inventory (AB-517).

Scans the legacy COBOL programs, JCL jobs and DB2 DDL, writes the extracted
facts to docs/modernization/legacy-facts.json, regenerates the generated
sections of the inventory and quirk register, and validates the quirk register
(docs/modernization/quirks.json) against the source it cites.

    python3 tools/discovery/pas_inventory.py --write   # regenerate
    python3 tools/discovery/pas_inventory.py           # check only (CI)

The legacy trees are only read, never modified.
"""
import argparse
import json
import os
import re
import subprocess
import sys

ROOT = subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True,
                               cwd=os.path.dirname(os.path.abspath(__file__))).strip()
DOCS = "docs/modernization"
FACTS = f"{DOCS}/legacy-facts.json"
QUIRKS = f"{DOCS}/quirks.json"
INVENTORY = f"{DOCS}/behaviour-inventory.md"
REGISTER = f"{DOCS}/quirk-register.md"
OPEN_QUESTIONS = f"{DOCS}/open-questions.md"
DDL = "sql/ddl/create-tables.sql"
DECISIONS = {"preserve", "fix"}
EPIC_TICKETS = range(518, 531)  # AB-516 children after AB-517 (AB-518..AB-530)
QUIRK_ID = re.compile(r"^Q-(?:(?!00)(\d{2})|(\d+)-([1-9]\d*))$")
LANDS_IN = re.compile(r"^AB-\d+(?:, AB-\d+)*$")
QUIRK_FIELDS = ("id", "title", "component", "category", "description", "owner",
                "proposed_decision", "decision_status", "rationale", "parity_note", "lands_in", "evidence")
SQL_SKIP = {"SYSIBM.SYSDUMMY1"}
OPS = {"SELECT": "R", "INSERT": "C", "UPDATE": "U", "DELETE": "D"}


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8", errors="replace") as fh:
        return fh.read()


def ls(prefix, suffix):
    out = subprocess.check_output(["git", "ls-files", prefix], cwd=ROOT, text=True).splitlines()
    return sorted(p for p in out if p.lower().endswith(suffix))


def squash(text):
    return re.sub(r"\s+", " ", text).strip()


# --------------------------------------------------------------------------- SQL
def sql_tables(sql):
    """Return {table: set(ops)} for one SQL statement."""
    sql = squash(sql.upper())
    out = {}

    def add(name, op):
        name = name.rstrip(";,)")
        if name.startswith(":") or name in SQL_SKIP or not re.match(r"^[A-Z][\w.]*$", name):
            return
        out.setdefault(name, set()).add(op)

    m = re.match(r"^(?:DECLARE \S+ CURSOR (?:WITH HOLD )?FOR )?(SELECT|INSERT|UPDATE|DELETE)\b", sql)
    if not m:
        return out
    verb = m.group(1)
    if verb == "INSERT":
        t = re.search(r"INSERT INTO (\S+)", sql)
        if t:
            add(t.group(1).split("(")[0], "C")
    elif verb == "UPDATE":
        t = re.search(r"UPDATE (\S+)", sql)
        if t:
            add(t.group(1), "U")
    elif verb == "DELETE":
        t = re.search(r"DELETE FROM (\S+)", sql)
        if t:
            add(t.group(1), "D")
    for t in re.finditer(r"\b(?:FROM|JOIN) (\S+)", sql):
        if verb != "DELETE" or not sql.startswith(f"DELETE FROM {t.group(1)}"):
            add(t.group(1), "R")
    return out


def merge(dst, src):
    for t, ops in src.items():
        dst.setdefault(t, set()).update(ops)


def unqualify(table):
    return table.split(".", 1)[1] if table.startswith("ACMEINS.") else table


def parse_ddl(text):
    tables = sorted({unqualify(m.upper()) for m in re.findall(r"CREATE TABLE (\S+)", text, re.I)})
    seqs = sorted({unqualify(m.upper()) for m in re.findall(r"CREATE SEQUENCE (\S+)", text, re.I)})
    return tables, seqs


# ------------------------------------------------------------------------- COBOL
def cobol_code(text):
    """Source area (cols 8-72) with comment lines blanked, line numbers kept."""
    lines = []
    for raw in text.splitlines():
        ind = raw[6:7] if len(raw) > 6 else ""
        lines.append("" if ind in ("*", "/") else raw[7:72])
    return lines


def parse_cobol(path):
    text = read(path)
    code = "\n".join(cobol_code(text))
    name = re.search(r"PROGRAM-ID\.\s+([\w-]+)", code).group(1).upper()
    literals = {m.group(1).upper(): m.group(2) for m in re.finditer(
        r"01\s+([\w-]+)\s+PIC\s+\S+\s+VALUE\s+'([^']*)'", code)}
    tables, sequences = {}, set()
    for block in re.findall(r"EXEC\s+SQL(.*?)END-EXEC", code, re.S):
        if re.search(r"NEXT VALUE FOR", block, re.I):
            sequences.update(unqualify(s.upper()) for s in re.findall(r"NEXT VALUE FOR\s+([\w.]+)", block, re.I))
        merge(tables, sql_tables(block))
    cics = [squash(b) for b in re.findall(r"EXEC\s+CICS(.*?)END-EXEC", code, re.S)]
    transid = sorted({m for b in cics for m in re.findall(r"TRANSID\('(\w+)'\)", b)})
    maps_in, maps_out, tdq, tsq = set(), set(), set(), set()
    for b in cics:
        verb = b.split()[0].upper()
        mp = re.search(r"MAP\('(\w+)'\)\s+MAPSET\('(\w+)'\)", b)
        if mp and verb == "RECEIVE":
            maps_in.add(f"{mp.group(2)}/{mp.group(1)}")
        elif mp and verb == "SEND":
            maps_out.add(f"{mp.group(2)}/{mp.group(1)}")
        q = re.search(r"WRITEQ\s+(TD|TS)\s+QUEUE\(([^)]+)\)", b)
        if q:
            qname = q.group(2).strip()
            qname = qname.strip("'") if qname.startswith("'") else literals.get(qname.upper(), qname)
            (tdq if q.group(1) == "TD" else tsq).add(qname)
    files = {m.group(1).upper(): m.group(2).upper() for m in re.finditer(r"SELECT\s+([\w-]+)\s+ASSIGN\s+TO\s+([\w-]+)", code)}
    return name, {
        "source": path,
        "kind": "cics" if cics else "batch",
        "transid": transid[0] if transid else None,
        "copybooks": sorted({c.upper() for c in re.findall(r"\bCOPY\s+([\w-]+)", code)}),
        "maps_received": sorted(maps_in),
        "maps_sent": sorted(maps_out),
        "td_queues": sorted(tdq),
        "ts_queues": sorted(tsq),
        "files": files,
        "sequences": sorted(sequences),
        "tables": {unqualify(t): "".join(sorted(ops, key="CRUD".index)) for t, ops in sorted(tables.items())},
        "qualified_tables": sorted(t for t in tables if "." in t),
    }


# --------------------------------------------------------------------------- JCL
def parse_jcl(path):
    lines = read(path).splitlines()
    jobname = re.match(r"^//(\S+)\s+JOB\b", lines[0]).group(1)
    sched = next((squash(l.split("Schedule:", 1)[1]) for l in lines if "Schedule:" in l), None)
    steps, step, instream, ftp_lines, tables = [], None, None, [], {}
    dd = None
    for line in lines:
        if instream is not None:
            if line.startswith("/*") or line.startswith("//"):
                if instream == "SYSIN":
                    for stmt in " ".join(step["sysin"]).split(";"):
                        merge(tables, sql_tables(stmt))
                instream = None
            else:
                (step["sysin"] if instream == "SYSIN" else ftp_lines).append(line.strip())
                if instream == "SYSTSIN":
                    r = re.search(r"RUN PROGRAM\((\w+)\)", line)
                    if r:
                        step["runs"] = r.group(1)
                if instream == "INPUT" and step["pgm"] == "FTP":
                    step["ftp"].append(line.strip())
                continue
        if line.startswith("//*"):
            continue
        m = re.match(r"^//(\S+)\s+EXEC\s+PGM=(\w+)", line)
        if m:
            step = {"step": m.group(1), "pgm": m.group(2), "runs": None, "cond": None,
                    "dds": [], "sysin": [], "ftp": []}
            steps.append(step)
        if step is not None:
            c = re.search(r"COND=(\([^)]*\)\)?|\([^)]*\))", line)
            if c:
                step["cond"] = c.group(1)
        m = re.match(r"^//(\S+)\s+DD\b(.*)$", line)
        if m:
            dd = {"dd": m.group(1), "dsn": None, "lrecl": None, "disp": None}
            if step is not None:
                step["dds"].append(dd)
            if re.search(r"\bDD\s+\*", line):
                instream = {"SYSIN": "SYSIN", "SYSTSIN": "SYSTSIN", "INPUT": "INPUT"}.get(m.group(1), "OTHER")
        if dd is not None and line.startswith("//"):
            d = re.search(r"DSN=([^,\s]+)", line)
            if d:
                dd["dsn"] = d.group(1)
            l = re.search(r"LRECL=(\d+)", line)
            if l:
                dd["lrecl"] = int(l.group(1))
            p = re.search(r"DISP=(\([^)]*\)|\w+)", line)
            if p:
                dd["disp"] = p.group(1)
    outputs, transfers = [], []
    for s in steps:
        for d in s["dds"]:
            if d["dsn"] and d["lrecl"]:
                outputs.append({"step": s["step"], "dd": d["dd"], "dsn": d["dsn"], "lrecl": d["lrecl"], "disp": d["disp"]})
        if s["ftp"]:
            host = s["ftp"][0]
            for cmd in s["ftp"][1:]:
                p = re.match(r"PUT\s+'([^']+)'\s+(\S+)", cmd)
                if p:
                    transfers.append({"step": s["step"], "host": host, "protocol": "ftp", "from": p.group(1), "to": p.group(2)})
        del s["sysin"], s["ftp"]
    return os.path.splitext(os.path.basename(path))[0].upper(), {
        "source": path,
        "job_card": jobname,
        "schedule": sched,
        "steps": [{k: v for k, v in s.items() if k != "dds"} for s in steps],
        "outputs": outputs,
        "transfers": transfers,
        "tables": {unqualify(t): "".join(sorted(ops, key="CRUD".index)) for t, ops in sorted(tables.items())},
    }


# --------------------------------------------------------------------- inventory
def copybooks():
    sys.path.insert(0, os.path.join(ROOT, "compat"))
    from check_layouts import parse_copybook  # same layout rules as the compat gate
    out = {}
    for path in ls("cobol/copybooks", ".cpy"):
        fields, length = parse_copybook(read(path))
        out[os.path.splitext(os.path.basename(path))[0].upper()] = {
            "source": path, "record_length": length,
            "fields": len([f for f in fields if not f.startswith("FILLER#")])}
    return out


def scan():
    ddl_tables, ddl_seqs = parse_ddl(read(DDL))
    programs = dict(parse_cobol(p) for p in ls("cobol/programs", ".cbl"))
    jobs = dict(parse_jcl(p) for p in ls("jcl", ".jcl"))
    referenced = set()
    for unit in list(programs.values()) + list(jobs.values()):
        referenced.update(unit["tables"])
    return {
        "_generated_by": "tools/discovery/pas_inventory.py --write (do not edit by hand)",
        "ddl": {"source": DDL, "tables": ddl_tables, "sequences": ddl_seqs},
        "copybooks": copybooks(),
        "programs": programs,
        "jobs": jobs,
        "tables_not_in_ddl": sorted(referenced - set(ddl_tables)),
        "ddl_tables_unreferenced": sorted(set(ddl_tables) - referenced),
    }


def crud_matrix(facts):
    units = [(n, "CICS " + (p["transid"] or "?") if p["kind"] == "cics" else "batch pgm") for n, p in facts["programs"].items()]
    units += [(n, f"JCL job {j['job_card']}") for n, j in facts["jobs"].items()]
    tables = facts["ddl"]["tables"] + facts["tables_not_in_ddl"]
    rows = ["| Table | " + " | ".join(f"{n} ({k})" for n, k in units) + " |",
            "|---|" + "---|" * len(units)]
    for t in tables:
        label = t if t in facts["ddl"]["tables"] else f"{t} (not in repo DDL)"
        cells = [unit["tables"].get(t, "") for unit in
                 list(facts["programs"].values()) + list(facts["jobs"].values())]
        rows.append(f"| {label} | " + " | ".join(cells) + " |")
    return "\n".join(rows)


def interfaces(facts):
    rows = ["| Unit | Inputs (maps / files) | Outputs (maps / datasets) | Messages / queues | File transfers |",
            "|---|---|---|---|---|"]
    for n, p in facts["programs"].items():
        ins = ", ".join(p["maps_received"]) or "-"
        outs = ", ".join(p["maps_sent"] + [f"{dd} -> DD {a}" for dd, a in p["files"].items()]) or "-"
        msgs = ", ".join([f"TDQ {q}" for q in p["td_queues"]] + [f"TSQ {q}" for q in p["ts_queues"]]) or "-"
        rows.append(f"| {n} | {ins} | {outs} | {msgs} | - |")
    for n, j in facts["jobs"].items():
        runs = ", ".join(sorted({s["runs"] for s in j["steps"] if s["runs"]})) or "-"
        outs = ", ".join(f"{o['dsn']} (LRECL {o['lrecl']})" for o in j["outputs"]) or "-"
        xfer = ", ".join(f"{t['protocol']} {t['host']}:{t['to']}" for t in j["transfers"]) or "-"
        rows.append(f"| {n} | runs {runs} | {outs} | - | {xfer} |")
    return "\n".join(rows)


# ------------------------------------------------------------------------ quirks
def resolve_evidence(ev):
    """Return line number of ev['contains'] in ev['file'] (whitespace-insensitive) or None."""
    path = os.path.join(ROOT, ev["file"])
    if not os.path.exists(path):
        return None
    needle = squash(ev["contains"])
    for i, line in enumerate(read(ev["file"]).splitlines(), 1):
        if needle in squash(line):
            return i
    return None


def follow_up_ids():
    return set(re.findall(r"^\|\s*(FU-\d+(?:-\d+)?)\s*\|", read(OPEN_QUESTIONS), re.M))


def valid_quirk_id(qid):
    m = QUIRK_ID.match(qid)
    return bool(m) and (m.group(1) is not None or int(m.group(2)) in EPIC_TICKETS)


def validate_quirks(quirks, known_follow_ups=None):
    errors, seen = [], set()
    if known_follow_ups is None:
        known_follow_ups = follow_up_ids()
    for q in quirks:
        qid = q.get("id", "?")
        missing = [f for f in QUIRK_FIELDS if not q.get(f)]
        if missing:
            errors.append(f"{qid}: missing {', '.join(missing)}")
        if not valid_quirk_id(qid):
            errors.append(f"{qid}: id must be Q-01..Q-99 (AB-517) or Q-<ticket>-<n> with ticket "
                          f"{EPIC_TICKETS[0]}..{EPIC_TICKETS[-1]} and n >= 1")
        lands_in = q.get("lands_in", "")
        if lands_in and (not LANDS_IN.match(lands_in)
                         or any(int(t) not in EPIC_TICKETS for t in re.findall(r"AB-(\d+)", lands_in))):
            errors.append(f"{qid}: lands_in must list epic tickets AB-{EPIC_TICKETS[0]}..AB-{EPIC_TICKETS[-1]}, "
                          f"got {lands_in!r}")
        for fu in re.findall(r"FU-\d+(?:-\d+)?", q.get("follow_up", "")):
            if fu not in known_follow_ups:
                errors.append(f"{qid}: follow_up {fu} not found in {OPEN_QUESTIONS}")
        if qid in seen:
            errors.append(f"{qid}: duplicate id")
        seen.add(qid)
        if q.get("proposed_decision") not in DECISIONS:
            errors.append(f"{qid}: proposed_decision must be one of {sorted(DECISIONS)}")
        for ev in q.get("evidence", []):
            if resolve_evidence(ev) is None:
                errors.append(f"{qid}: evidence not found: {ev.get('file')}: {ev.get('contains')!r}")
    return errors


def register_table(quirks):
    rows = ["| ID | Quirk | Component | Owner | Proposed decision | Decision status | Lands in | Evidence |",
            "|---|---|---|---|---|---|---|---|"]
    for q in quirks:
        ev = "<br>".join(f"`{e['file']}:{resolve_evidence(e)}`" for e in q["evidence"])
        rows.append(f"| [{q['id']}](#{q['id'].lower()}) | {q['title']} | {q['component']} | {q['owner']} | "
                    f"**{q['proposed_decision']}** | {q['decision_status']} | {q['lands_in']} | {ev} |")
    return "\n".join(rows)


def register_details(quirks):
    out = []
    for q in quirks:
        ev = "\n".join(f"  - `{e['file']}:{resolve_evidence(e)}`: `{squash(e['contains'])}`" for e in q["evidence"])
        out.append(
            f"### {q['id']}\n\n**{q['title']}** ({q['component']}, {q['category']})\n\n"
            f"{q['description']}\n\n"
            f"- Owner: {q['owner']}\n"
            f"- Proposed decision: **{q['proposed_decision']}** ({q['decision_status']})\n"
            f"- Rationale: {q['rationale']}\n"
            f"- Parity / characterization note: {q['parity_note']}\n"
            f"- Lands in: {q['lands_in']}\n"
            + (f"- Follow-up: {q['follow_up']}\n" if q.get("follow_up") else "")
            + f"- Evidence:\n{ev}\n")
    return "\n".join(out)


# ----------------------------------------------------------------------- render
def replace_block(text, name, body):
    begin, end = f"<!-- BEGIN GENERATED: {name} -->", f"<!-- END GENERATED: {name} -->"
    pattern = re.compile(re.escape(begin) + r".*?" + re.escape(end), re.S)
    if not pattern.search(text):
        raise SystemExit(f"missing generated block markers for '{name}'")
    return pattern.sub(lambda _: f"{begin}\n{body}\n{end}", text)


def render(facts, quirks):
    inv = read(INVENTORY)
    inv = replace_block(inv, "crud-matrix", crud_matrix(facts))
    inv = replace_block(inv, "interfaces", interfaces(facts))
    reg = read(REGISTER)
    reg = replace_block(reg, "quirk-table", register_table(quirks))
    reg = replace_block(reg, "quirk-details", register_details(quirks))
    return {FACTS: json.dumps(facts, indent=2, sort_keys=True) + "\n", INVENTORY: inv, REGISTER: reg}


def coverage_errors(facts, quirks):
    errors = []
    inv = read(INVENTORY)
    for n in list(facts["programs"]) + list(facts["jobs"]):
        if not re.search(rf"^###\s+.*\b{re.escape(n)}\b", inv, re.M):
            errors.append(f"{INVENTORY}: no '### ...{n}...' section for {n}")
    for t in facts["tables_not_in_ddl"]:
        if t not in inv:
            errors.append(f"{INVENTORY}: table {t} referenced in code but not documented")
    for t in facts["ddl"]["tables"]:
        if t not in inv:
            errors.append(f"{INVENTORY}: DDL table {t} not documented")
    reg = read(REGISTER)
    for q in quirks:
        if f"### {q['id']}" not in reg:
            errors.append(f"{REGISTER}: missing section for {q['id']}")
    return errors


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--write", action="store_true", help="regenerate facts and generated doc sections")
    args = ap.parse_args(argv)
    facts = scan()
    quirks = json.loads(read(QUIRKS))["quirks"]
    errors = validate_quirks(quirks)
    if errors:
        print("\n".join(["quirk register invalid:"] + ["  " + e for e in errors]))
        return 1
    outputs = render(facts, quirks)
    stale = [p for p, body in outputs.items() if not os.path.exists(os.path.join(ROOT, p)) or read(p) != body]
    if args.write:
        for p in stale:
            with open(os.path.join(ROOT, p), "w", encoding="utf-8") as fh:
                fh.write(outputs[p])
            print(f"updated {p}")
        stale = []
    errors = coverage_errors(facts, quirks)
    errors += [f"{p} is stale: run python3 tools/discovery/pas_inventory.py --write" for p in stale]
    if errors:
        print("\n".join(["inventory check FAILED:"] + ["  " + e for e in errors]))
        return 1
    print(f"inventory check PASS: {len(facts['programs'])} programs, {len(facts['jobs'])} jobs, "
          f"{len(facts['ddl']['tables'])} DDL tables, {len(quirks)} quirks with owner + decision")
    return 0


if __name__ == "__main__":
    sys.exit(main())
