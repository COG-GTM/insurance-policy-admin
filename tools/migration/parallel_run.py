#!/usr/bin/env python3
"""AB-529 parallel run: daily output diffs, difference triage and the full-cycle gate.

During the parallel run the Java premium batch and extracts run beside the mainframe every business
day. Both sets of outputs are captured into one directory per business date:

    <captured>/<YYYY-MM-DD>/<file>    mainframe output (PREMRPT, extract datasets, PREMIUMS unload)
    <candidate>/<YYYY-MM-DD>/<file>   Java output for the same date and file name

``compare`` diffs every captured file against the candidate record by record, classifies each
difference against the triage register (bug, approved_fix, data_issue) and writes a day report.
``gate`` checks that one full billing cycle (at least ``cycle.min_business_days``) ran with zero
unexplained differences and zero bug differences: a fixed bug only stops blocking once the outputs
are regenerated and recaptured and the records no longer differ.
``check`` validates the plan and the triage register (run in CI). Reports never contain record
content: only file name, record number, record key (policy number) and the classification.
Nothing here executes cutover or retirement; those stay in the gated runbook (execute_on_branch
is false for every step).
"""

from __future__ import annotations

import argparse
import datetime as dt
import fnmatch
import json
import re
import sys
from pathlib import Path

CLASSIFICATIONS = ("bug", "approved_fix", "data_issue")
UNEXPLAINED = "unexplained"
ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PLAN = ROOT / "docs/pas/parallel-run.json"
DEFAULT_TRIAGE = ROOT / "docs/pas/parallel-run-triage.json"
DEFAULT_QUIRKS = ROOT / "docs/modernization/quirks.json"


def load(path: Path) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def output_spec(plan: dict, name: str) -> dict | None:
    for spec in plan["outputs"]:
        if fnmatch.fnmatch(name, spec["pattern"]):
            return spec
    return None


def record_key(record: bytes, spec: dict | None) -> str:
    if not spec or "key" not in spec:
        return ""
    start, end = spec["key"]
    return record[start:end].decode("latin-1").strip()


def split_records(data: bytes) -> list[bytes]:
    text = data.replace(b"\r\n", b"\n")
    if text.endswith(b"\n"):
        text = text[:-1]
    return text.split(b"\n") if data else []


def diff_file(name: str, captured: bytes, candidate: bytes, spec: dict | None) -> list[dict]:
    """Record-level differences between two outputs; keys only, never record content."""
    left, right = split_records(captured), split_records(candidate)
    diffs = []
    for i in range(max(len(left), len(right))):
        a = left[i] if i < len(left) else None
        b = right[i] if i < len(right) else None
        if a == b:
            continue
        kind = "changed" if a is not None and b is not None else ("missing" if b is None else "extra")
        source = a if a is not None else b
        diffs.append({"file": name, "record": i + 1, "key": record_key(source, spec), "kind": kind})
    return diffs


def triage_matches(entry: dict, date: str, diff: dict) -> bool:
    if not fnmatch.fnmatch(diff["file"], entry.get("files", "*")):
        return False
    if not re.fullmatch(entry.get("key_regex", ".*"), diff["key"]):
        return False
    kinds = entry.get("kinds")
    if kinds and diff["kind"] not in kinds:
        return False
    start, end = entry.get("dates", [None, None])
    return (start is None or date >= start) and (end is None or date <= end)


def classify(date: str, diff: dict, triage: dict) -> dict:
    for entry in triage["entries"]:
        if triage_matches(entry, date, diff):
            return {**diff, "classification": entry["classification"], "triage": entry["id"],
                    "status": entry.get("status", "open")}
    return {**diff, "classification": UNEXPLAINED, "triage": None, "status": "open"}


def first_business_day_of_month(date: str, holidays: set[str]) -> bool:
    day = dt.date.fromisoformat(date)
    return business_days(day.replace(day=1), day, holidays) == [date]


def required_on(spec: dict, date: str, holidays: set[str]) -> bool:
    when = spec.get("required_on")
    if when == "first_business_day_of_month":
        return first_business_day_of_month(date, holidays)
    return bool(spec.get("required"))


def list_files(directory: Path) -> list[str]:
    return sorted(p.name for p in directory.iterdir() if p.is_file()) if directory.is_dir() else []


def compare_day(date: str, captured: Path, candidate: Path, plan: dict, triage: dict) -> dict:
    differences = []
    files = list_files(captured)
    holidays = set(plan.get("cycle", {}).get("holidays", []))
    missing_outputs = [s["pattern"] for s in plan["outputs"] if required_on(s, date, holidays)
                       and not any(fnmatch.fnmatch(f, s["pattern"]) for f in files)]
    for name in list_files(candidate):
        if name not in files and output_spec(plan, name) is not None:
            differences.append({"file": name, "record": 0, "key": "", "kind": "extra_file"})
    for name in files:
        spec = output_spec(plan, name)
        other = candidate / name
        if not other.exists():
            differences.append({"file": name, "record": 0, "key": "", "kind": "missing_file"})
            continue
        differences.extend(diff_file(name, (captured / name).read_bytes(), other.read_bytes(), spec))
    classified = [classify(date, d, triage) for d in differences]
    counts = {c: sum(1 for d in classified if d["classification"] == c)
              for c in CLASSIFICATIONS + (UNEXPLAINED,)}
    open_bugs = sum(1 for d in classified if d["classification"] == "bug" and d["status"] != "resolved")
    clean = not missing_outputs and counts[UNEXPLAINED] == 0 and counts["bug"] == 0
    return {"business_date": date, "files": files, "missing_outputs": missing_outputs,
            "differences": classified, "counts": counts, "open_bugs": open_bugs,
            "status": "CLEAN" if clean else "NOT_CLEAN"}


def markdown(report: dict) -> str:
    lines = [f"# PAS parallel run {report['business_date']}: {report['status']}", "",
             "| Classification | Differences |", "|---|---:|"]
    lines += [f"| {k} | {v} |" for k, v in report["counts"].items()]
    if report["missing_outputs"]:
        lines += ["", "Missing mainframe outputs: " + ", ".join(report["missing_outputs"])]
    shown = [d for d in report["differences"] if d["classification"] in (UNEXPLAINED, "bug")][:200]
    if shown:
        lines += ["", "| File | Record | Key | Kind | Classification | Triage |", "|---|---:|---|---|---|---|"]
        lines += [f"| {d['file']} | {d['record']} | {d['key']} | {d['kind']} | {d['classification']} "
                  f"| {d['triage'] or '-'} |" for d in shown]
    return "\n".join(lines) + "\n"


def business_days(start: dt.date, end: dt.date, holidays: set[str]) -> list[str]:
    days, day = [], start
    while day <= end:
        if day.weekday() < 5 and day.isoformat() not in holidays:
            days.append(day.isoformat())
        day += dt.timedelta(days=1)
    return days


def gate(plan: dict, reports: dict[str, dict]) -> dict:
    cycle = plan["cycle"]
    if not cycle.get("start") or not cycle.get("end"):
        return {"passed": False, "problems": ["billing cycle dates are not agreed yet (FU-529-2)"]}
    expected = business_days(dt.date.fromisoformat(cycle["start"]), dt.date.fromisoformat(cycle["end"]),
                             set(cycle.get("holidays", [])))
    problems = []
    for day in expected:
        report = reports.get(day)
        if report is None:
            problems.append(f"{day}: no parallel-run report")
        elif report["status"] != "CLEAN":
            problems.append(f"{day}: {report['counts'][UNEXPLAINED]} unexplained, "
                            f"{report['counts']['bug']} bug, missing {report['missing_outputs']}")
    minimum = cycle.get("min_business_days", 1)
    if len(expected) < minimum:
        problems.append(f"cycle has {len(expected)} business days; at least {minimum} are required")
    return {"passed": not problems, "cycle": cycle, "business_days": len(expected), "problems": problems}


def check(plan: dict, triage: dict, quirks: dict) -> list[str]:
    problems = []
    fixes = {q["id"] for q in quirks["quirks"] if q.get("proposed_decision") == "fix"}
    seen = set()
    for entry in triage["entries"]:
        eid = entry.get("id", "?")
        if not re.fullmatch(r"PRT-\d{3}", eid) or eid in seen:
            problems.append(f"{eid}: ids are unique PRT-nnn")
        seen.add(eid)
        cls = entry.get("classification")
        if cls not in CLASSIFICATIONS:
            problems.append(f"{eid}: classification must be one of {CLASSIFICATIONS}")
        if cls == "approved_fix" and entry.get("quirk") not in fixes:
            problems.append(f"{eid}: approved_fix needs a quirk whose decision is 'fix'")
        if cls in ("bug", "data_issue") and not entry.get("reference"):
            problems.append(f"{eid}: {cls} needs a reference (defect, follow-up or data fix)")
        if not entry.get("reason"):
            problems.append(f"{eid}: reason is required")
        if entry.get("status", "open") not in ("open", "resolved"):
            problems.append(f"{eid}: status is open or resolved")
        if entry.get("files", "*") in ("*", "") and entry.get("key_regex", ".*") == ".*":
            problems.append(f"{eid}: must name files or keys; a catch-all would hide real differences")
        try:
            re.compile(entry.get("key_regex", ".*"))
        except re.error:
            problems.append(f"{eid}: key_regex does not compile")
    for spec in plan["outputs"]:
        if "pattern" not in spec or "job" not in spec:
            problems.append(f"output {spec}: pattern and job are required")
        if spec.get("required_on") not in (None, "first_business_day_of_month"):
            problems.append(f"output {spec.get('pattern')}: required_on is first_business_day_of_month")
    for step in plan["runbook"]:
        if step.get("execute_on_branch") is not False:
            problems.append(f"runbook step {step.get('id')}: execute_on_branch must be false")
        if step.get("dry_run_default") is not True:
            problems.append(f"runbook step {step.get('id')}: dry_run_default must be true")
    for signoff in plan["sign_offs"]:
        if signoff.get("status") not in ("pending", "approved"):
            problems.append(f"sign-off {signoff.get('role')}: status is pending or approved")
        if signoff.get("status") == "approved" and not signoff.get("evidence"):
            problems.append(f"sign-off {signoff.get('role')}: approved needs evidence")
    if plan["cycle"].get("min_business_days", 0) < 1:
        problems.append("cycle.min_business_days must be at least 1")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    c = sub.add_parser("compare", help="diff and triage one or more business dates")
    c.add_argument("--captured", type=Path, required=True)
    c.add_argument("--candidate", type=Path, required=True)
    c.add_argument("--date", action="append", help="business date(s); default: every captured day")
    c.add_argument("--out", type=Path, required=True, help="directory for day reports")
    g = sub.add_parser("gate", help="full billing cycle gate over the day reports")
    g.add_argument("--reports", type=Path, required=True)
    sub.add_parser("check", help="validate the plan and the triage register")
    for p in (c, g, sub.choices["check"]):
        p.add_argument("--plan", type=Path, default=DEFAULT_PLAN)
        p.add_argument("--triage", type=Path, default=DEFAULT_TRIAGE)
        p.add_argument("--quirks", type=Path, default=DEFAULT_QUIRKS)
    args = parser.parse_args(argv)
    plan, triage = load(args.plan), load(args.triage)

    if args.command == "check":
        problems = check(plan, triage, load(args.quirks))
        for problem in problems:
            print(f"parallel-run: {problem}", file=sys.stderr)
        print(f"parallel-run check: {len(triage['entries'])} triage entries, "
              f"{len(plan['outputs'])} outputs, {'OK' if not problems else 'FAILED'}")
        return 1 if problems else 0

    if args.command == "compare":
        dates = args.date or sorted(p.name for p in args.captured.iterdir() if p.is_dir())
        args.out.mkdir(parents=True, exist_ok=True)
        clean = True
        for date in dates:
            dt.date.fromisoformat(date)
            report = compare_day(date, args.captured / date, args.candidate / date, plan, triage)
            (args.out / f"parallel-run-{date}.json").write_text(json.dumps(report, indent=2) + "\n")
            (args.out / f"parallel-run-{date}.md").write_text(markdown(report))
            print(f"parallel-run {date}: {report['status']} {report['counts']}")
            clean = clean and report["status"] == "CLEAN"
        return 0 if clean else 1

    reports = {}
    for path in sorted(args.reports.glob("parallel-run-*.json")):
        report = json.loads(path.read_text())
        reports[report["business_date"]] = report
    result = gate(plan, reports)
    print(json.dumps(result, indent=2))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
