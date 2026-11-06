#!/usr/bin/env python3
"""Gated, dry-run-by-default mainframe decommission for PAS (AB-530).

Reads docs/pas/mainframe-decommission.json. --check validates the plan against the repository:
every decommissioned definition still exists where the plan says, every DB2 table in the DDL has an
archive entry, every file outside the legacy trees that references them is covered by a repointing
entry, and the only scripted step removes exactly the listed trees. The default run prints the plan,
the repository impact and the MIPS figures. --execute refuses while any gate is open or on a
protected branch, and even then only changes the working tree for review in a PR; every mainframe
action is manual."""
import argparse
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import facade_retirement as fr  # noqa: E402

ROOT = fr.ROOT
PLAN = os.path.join(ROOT, "docs", "pas", "mainframe-decommission.json")
ITEM_KEYS = ("id", "kind", "name", "defined_in", "evidence", "replaced_by", "owner")
SIGNOFF_STATUSES = {"pending", "approved", "rejected"}
TABLE_RE = re.compile(r"^\s*CREATE\s+TABLE\s+(?:\w+\.)?(\w+)", re.I | re.M)


def ddl_tables(path):
    with open(path, encoding="utf-8") as f:
        return set(TABLE_RE.findall(f.read()))


def tree_reference(trees):
    alternatives = "|".join(re.escape(t) for t in trees)
    return re.compile(rf"(?<![\w.-])({alternatives})/")


def tracked_files(root):
    try:
        out = subprocess.run(["git", "ls-files", "-z"], cwd=root, capture_output=True, check=True)
        return [p for p in out.stdout.decode("utf-8").split("\0") if p]
    except (OSError, subprocess.CalledProcessError):
        files = []
        for base, dirs, names in os.walk(root):
            dirs[:] = [d for d in dirs if d != ".git"]
            files.extend(os.path.relpath(os.path.join(base, n), root).replace(os.sep, "/") for n in names)
        return files


def dependents(root, trees, files=None):
    """Tracked files outside the legacy trees that reference them."""
    pattern = tree_reference(trees)
    found = []
    for path in files if files is not None else tracked_files(root):
        if any(path == t or path.startswith(t + "/") for t in trees):
            continue
        try:
            with open(os.path.join(root, path), encoding="utf-8") as f:
                text = f.read()
        except (OSError, UnicodeDecodeError):
            continue
        if pattern.search(text):
            found.append(path)
    return sorted(found)


def uncovered(found, entries):
    prefixes = [e["path"] for e in entries]
    return [p for p in found if not any(p == pre or (pre.endswith("/") and p.startswith(pre)) for pre in prefixes)]


def mips_saving(plan):
    """(saving_msu, saving_percent) once both measurements are recorded, else None."""
    mips = plan.get("mips", {})
    before, after = mips.get("baseline_msu"), mips.get("after_msu")
    if before is None or after is None:
        return None
    return before - after, round(100.0 * (before - after) / before, 1)


def validate(plan, root=ROOT, files=None):
    errors = fr.validate(plan)
    for key in ("decommission", "archive", "repository", "mips", "signoffs"):
        if key not in plan:
            errors.append(f"missing '{key}'")
    if errors:
        return errors

    ids = [i.get("id") for i in plan["decommission"]]
    if len(ids) != len(set(ids)):
        errors.append("duplicate decommission ids")
    for item in plan["decommission"]:
        missing = [k for k in ITEM_KEYS if not item.get(k)]
        if missing:
            errors.append(f"{item.get('id')}: missing {', '.join(missing)}")
            continue
        path = os.path.join(root, item["defined_in"])
        if not os.path.isfile(path):
            errors.append(f"{item['id']}: {item['defined_in']} not found")
            continue
        with open(path, encoding="utf-8") as f:
            if item["evidence"] not in f.read():
                errors.append(f"{item['id']}: '{item['evidence']}' not found in {item['defined_in']}")

    archive = plan["archive"]
    ddl = os.path.join(root, archive.get("source_ddl", ""))
    if not os.path.isfile(ddl):
        errors.append(f"archive: {archive.get('source_ddl')} not found")
    else:
        listed = [t.get("table") for t in archive.get("tables", [])]
        expected = ddl_tables(ddl) - set(archive.get("out_of_scope_tables", []))
        for table in sorted(expected - set(listed)):
            errors.append(f"archive: table {table} in {archive['source_ddl']} has no archive entry")
        for table in sorted(set(listed) - expected):
            errors.append(f"archive: {table} is not a table in {archive['source_ddl']}")
        for t in archive.get("tables", []):
            if not t.get("retention"):
                errors.append(f"archive: {t.get('table')} has no retention")

    repo = plan["repository"]
    trees = repo.get("trees", [])
    for tree in trees:
        if not os.path.isdir(os.path.join(root, tree)):
            errors.append(f"repository: tree {tree}/ not found")
    for kept in repo.get("kept", []):
        if kept in trees:
            errors.append(f"repository: {kept} is both kept and removed")
    for path in uncovered(dependents(root, trees, files), repo.get("dependents", [])):
        errors.append(f"repository: {path} references a legacy tree but has no dependents entry")

    scripted = [s for s in plan["steps"] if s.get("command")]
    expected_cmd = "git rm -r -q " + " ".join(trees)
    if [s["command"] for s in scripted] != [expected_cmd]:
        errors.append(f"steps: the only scripted step must be '{expected_cmd}'")

    mips = plan["mips"]
    for key in ("baseline_msu", "after_msu"):
        value = mips.get(key)
        if value is not None and (not isinstance(value, (int, float)) or value < 0):
            errors.append(f"mips: {key} must be a non-negative number or null")
    if not errors:
        saving = mips_saving(plan)
        g7 = next((g for g in plan["gates"] if "MIPS" in g["description"]), None)
        if g7 and g7["status"] == "approved" and (saving is None or saving[0] <= 0):
            errors.append(f"{g7['id']}: approved but mips shows no saving")

    for s in plan["signoffs"]:
        if not s.get("role") or s.get("status") not in SIGNOFF_STATUSES:
            errors.append(f"signoff {s}: needs role and status in {sorted(SIGNOFF_STATUSES)}")
    return errors


def print_report(plan, root=ROOT, out=None):
    out = out or sys.stdout
    fr.print_plan(plan, out)
    for item in plan["decommission"]:
        print(f"  decommission {item['id']} {item['kind']} {item['name']} -> {item['replaced_by']}", file=out)
    found = dependents(root, plan["repository"]["trees"])
    print(f"  repository: {len(found)} files outside {', '.join(plan['repository']['trees'])} reference them", file=out)
    for entry in plan["repository"]["dependents"]:
        count = len([p for p in found if p == entry["path"] or p.startswith(entry["path"])])
        print(f"    {entry['path']} ({count}): {entry['action']}", file=out)
    saving = mips_saving(plan)
    print("  mips: " + (f"saving {saving[0]} MSU ({saving[1]}%)" if saving else "not measured yet"), file=out)
    pending = [s["role"] for s in plan["signoffs"] if s["status"] != "approved"]
    print(f"  sign-offs pending: {len(pending)} of {len(plan['signoffs'])}", file=out)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--plan", default=PLAN)
    ap.add_argument("--check", action="store_true", help="validate the plan file and exit")
    ap.add_argument("--execute", action="store_true", help="run the scripted step (gated)")
    args = ap.parse_args(argv)
    plan = fr.load(args.plan)
    errors = validate(plan)
    if errors:
        for e in errors:
            print(f"mainframe-decommission: {e}", file=sys.stderr)
        return 1
    if args.check:
        print(f"mainframe-decommission: plan valid, {len(plan['decommission'])} components, "
              f"{len(fr.open_gates(plan))} of {len(plan['gates'])} gates open")
        return 0
    print_report(plan)
    if not args.execute:
        print("mainframe-decommission: dry run, nothing changed")
        return 0
    reason = fr.refusal(plan, fr.current_branch())
    if reason:
        print(f"mainframe-decommission: {reason}", file=sys.stderr)
        return 2
    for step in plan["steps"]:
        if step.get("command"):
            print(f"mainframe-decommission: {step['id']}: {step['command']}")
            subprocess.run(step["command"], shell=True, cwd=ROOT, check=True)
    print("mainframe-decommission: working tree changed; repoint dependents, commit and open a PR")
    return 0


if __name__ == "__main__":
    sys.exit(main())
