#!/usr/bin/env python3
"""AB-527 extract parity and cut-over gate.

compare: byte-for-byte comparison of captured mainframe feed files against the files written by
pas-batch, one sub-directory per run (YYYY-MM-DD), and a check that each feed has at least
--required consecutive identical runs (acceptance criterion: 5). Consecutive means scheduled runs
with nothing skipped: every calendar day (or every weekday with --claims-calendar weekdays) for the
Claims feeds and the first of every month for the exposure feed. Run directories are named by
business date; a capture on any other date (a late or manual replay) breaks the streak. A missing scheduled run breaks the streak, and
every feed must pass.

    extract_parity.py compare --captured DIR --candidate DIR [--required 5] [--report FILE]
        [--claims-calendar daily|weekdays] [--exposure-order-insensitive]

The mainframe orders exposure rows only by POLICY_TYPE, BRANCH_CODE (Q-34). Until Actuarial
confirms the order, --exposure-order-insensitive counts a run whose rows differ only in order
within those keys as matching; such runs are reported as "order-only", never as identical.

gate: reads docs/pas/extracts-cutover.json and prints the cut-over plan and its gates. It never
changes anything: legacy jobs are retired by Mainframe operations through change management.
Exit 0 when every gate is met, 3 while anything is pending, 2 when the file is invalid.

    extract_parity.py gate [--file docs/pas/extracts-cutover.json] [--check]
"""
import argparse
import datetime
import json
import os
import sys

FEEDS = {
    "policy_extract.dat": {"lrecl": 200, "kind": "fixed", "cadence": "daily"},
    "coverage_extract.dat": {"lrecl": 150, "kind": "fixed", "cadence": "daily"},
    "exposure_monthly.csv": {"lrecl": 300, "kind": "csv", "cadence": "monthly"},
}
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CUTOVER = os.path.join(ROOT, "docs", "pas", "extracts-cutover.json")
SIGNOFF_STATES = ("pending", "approved", "rejected")


def _records(data):
    lines = data.split(b"\n")
    if lines and lines[-1] == b"":
        lines.pop()
    return [line[:-1] if line.endswith(b"\r") else line for line in lines]


def compare_bytes(name, expected, actual):
    """Returns None when identical, else a description of the first difference."""
    if expected == actual:
        return None
    exp, act = _records(expected), _records(actual)
    for i, (e, a) in enumerate(zip(exp, act), start=1):
        if e != a:
            col = next((j for j, (x, y) in enumerate(zip(e, a)) if x != y), min(len(e), len(a)))
            return f"record {i} differs at byte {col + 1} (lengths {len(e)} vs {len(a)})"
    if len(exp) != len(act):
        return f"record count {len(exp)} captured vs {len(act)} candidate"
    return "record terminators or encoding differ"


def order_only(expected, actual):
    """Exposure rows are ordered only by POLICY_TYPE, BRANCH_CODE on the mainframe (Q-34)."""
    def groups(data):
        out = {}
        for rec in _records(data):
            key = tuple(rec.split(b",")[:2])
            out.setdefault(key, []).append(rec)
        return {k: sorted(v) for k, v in out.items()}, [tuple(r.split(b",")[:2]) for r in _records(data)]
    (eg, ek), (ag, ak) = groups(expected), groups(actual)
    dedup = lambda keys: [k for i, k in enumerate(keys) if i == 0 or keys[i - 1] != k]
    return eg == ag and dedup(ek) == dedup(ak)


def next_scheduled(date, cadence, claims_calendar="daily"):
    if cadence == "monthly":
        return (date.replace(day=1) + datetime.timedelta(days=32)).replace(day=1)
    date += datetime.timedelta(days=1)
    while claims_calendar == "weekdays" and date.weekday() >= 5:
        date += datetime.timedelta(days=1)
    return date


def is_scheduled(date, cadence, claims_calendar="daily"):
    if cadence == "monthly":
        return date.day == 1
    return claims_calendar == "daily" or date.weekday() < 5


def _scheduled_after(previous, run, cadence, claims_calendar):
    prev, cur = datetime.date.fromisoformat(previous), datetime.date.fromisoformat(run)
    return cur == next_scheduled(prev, cadence, claims_calendar)


def compare_runs(captured, candidate, required, exposure_order_insensitive=False,
                 claims_calendar="daily"):
    runs = sorted(d for d in os.listdir(captured) if os.path.isdir(os.path.join(captured, d)))
    for run in runs:
        datetime.date.fromisoformat(run)
    report = {"required": required, "feeds": {}}
    for name in FEEDS:
        results = []
        for run in runs:
            cap = os.path.join(captured, run, name)
            if not os.path.isfile(cap):
                continue
            cand = os.path.join(candidate, run, name)
            if not os.path.isfile(cand):
                results.append({"run": run, "identical": False, "detail": "candidate file missing"})
                continue
            with open(cap, "rb") as f:
                expected = f.read()
            with open(cand, "rb") as f:
                actual = f.read()
            detail = compare_bytes(name, expected, actual)
            entry = {"run": run, "identical": detail is None, "detail": detail}
            if detail and FEEDS[name]["kind"] == "csv" and order_only(expected, actual):
                entry["detail"] += "; rows differ only in order within POLICY_TYPE, BRANCH_CODE (Q-34)"
                entry["order_only"] = True
            results.append(entry)
        streak = best = 0
        previous = None
        for r in results:
            matched = r["identical"] or (exposure_order_insensitive and r.get("order_only", False))
            if not is_scheduled(datetime.date.fromisoformat(r["run"]), FEEDS[name]["cadence"],
                                claims_calendar):
                r["unscheduled"] = True
                streak = 0
                previous = None
                continue
            if previous is not None and not _scheduled_after(
                    previous, r["run"], FEEDS[name]["cadence"], claims_calendar):
                r["gap_before"] = True
                streak = 0
            streak = streak + 1 if matched else 0
            best = max(best, streak)
            previous = r["run"]
        report["feeds"][name] = {"runs": results, "consecutive_identical": best,
                                 "passed": best >= required}
    report["passed"] = all(f["passed"] for f in report["feeds"].values())
    return report


def validate_cutover(doc):
    errors = []
    for key in ("feeds", "signoffs", "legacy_retirement"):
        if key not in doc:
            errors.append(f"missing '{key}'")
    for feed in doc.get("feeds", []):
        for key in ("consumer", "legacy_job", "new_job", "remote_files", "cutover_date", "status"):
            if key not in feed:
                errors.append(f"feed {feed.get('consumer', '?')}: missing '{key}'")
        date = feed.get("cutover_date")
        if date not in (None, "TBD"):
            try:
                datetime.date.fromisoformat(date)
            except (TypeError, ValueError):
                errors.append(f"feed {feed.get('consumer')}: cutover_date must be YYYY-MM-DD or TBD")
    for s in doc.get("signoffs", []):
        if not str(s.get("status", "")).split(" ")[0] in SIGNOFF_STATES:
            errors.append(f"signoff {s.get('party')}: status must start with one of {SIGNOFF_STATES}")
    for step in doc.get("legacy_retirement", []):
        if step.get("execute_on_branch", False):
            errors.append(f"retirement step {step.get('step')}: must never execute on the branch")
    return errors


def gate(path, check_only):
    with open(path, encoding="utf-8") as f:
        doc = json.load(f)
    errors = validate_cutover(doc)
    if errors:
        for e in errors:
            print(f"INVALID {e}")
        return 2
    if check_only:
        print(f"{os.path.relpath(path, ROOT)}: valid")
        return 0
    pending = []
    print("Feeds:")
    for feed in doc["feeds"]:
        print(f"  {feed['consumer']}: {feed['legacy_job']} -> {feed['new_job']}, "
              f"cut-over {feed['cutover_date']} ({feed['status']})")
        if feed["cutover_date"] in (None, "TBD") or feed["status"] != "confirmed":
            pending.append(f"cut-over date for {feed['consumer']}")
    print("Sign-offs:")
    for s in doc["signoffs"]:
        print(f"  {s['party']}: {s['status']}")
        if not s["status"].startswith("approved"):
            pending.append(f"sign-off {s['party']}")
    print("Legacy retirement plan (dry run, executed by Mainframe operations):")
    for step in doc["legacy_retirement"]:
        print(f"  {step['step']}. {step['action']}  [gate: {step['gate']}]")
    if pending:
        print("PENDING: " + "; ".join(pending))
        return 3
    print("All gates met.")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("compare")
    c.add_argument("--captured", required=True)
    c.add_argument("--candidate", required=True)
    c.add_argument("--required", type=int, default=5)
    c.add_argument("--report")
    c.add_argument("--exposure-order-insensitive", action="store_true")
    c.add_argument("--claims-calendar", choices=("daily", "weekdays"), default="daily")
    g = sub.add_parser("gate")
    g.add_argument("--file", default=CUTOVER)
    g.add_argument("--check", action="store_true", help="validate the file only")
    args = parser.parse_args(argv)
    if args.cmd == "gate":
        return gate(args.file, args.check)
    report = compare_runs(args.captured, args.candidate, args.required,
                          args.exposure_order_insensitive, args.claims_calendar)
    for name, feed in report["feeds"].items():
        for r in feed["runs"]:
            tag = "OK  " if r["identical"] else "ORDR" if r.get("order_only") else "DIFF"
            if r.get("unscheduled"):
                print(f"SKIP {r['run']} {name}: not a scheduled business date, breaks the streak")
            if r.get("gap_before"):
                print(f"GAP  scheduled run(s) missing before {r['run']} {name}")
            print(f"{tag} {r['run']} {name}"
                  + (f": {r['detail']}" if r["detail"] else ""))
        if not feed["runs"]:
            print(f"{name}: no captured runs, need {args.required}: FAIL")
        else:
            print(f"{name}: {feed['consecutive_identical']} consecutive matching run(s), "
                  f"need {args.required}: {'PASS' if feed['passed'] else 'FAIL'}")
    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
