#!/usr/bin/env python3
"""Cutover go/no-go and hypercare decisions for PAS (AB-530).

Reads docs/pas/cutover.json. --check validates the plan: every ADR 0001 D7 capability has a routing
switch, smoke tests and a rollback, every owner is a named role, no step runs from a branch, and the
prerequisite plans (AB-524, AB-527, AB-528, AB-529) and the gate ids it cites exist. The default
run prints the runbook and the go/no-go decision; --gate exits 3 while the decision is NO-GO.
--hypercare evaluates the 14-day no-Sev1/Sev2 acceptance criterion (with --gate: 0 passed,
3 still running or not started, 4 breached). Nothing here switches production."""
import argparse
import datetime as dt
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import facade_retirement as fr  # noqa: E402

ROOT = fr.ROOT
PLAN = os.path.join(ROOT, "docs", "pas", "cutover.json")
STATES = ("LEGACY", "SHADOW", "NEW", "RETIRED")
DONE = {"approved", "passed"}
CHECK_STATUSES = {"pending", "passed", "failed"}
STATUSES = {"pending", "approved", "rejected"}
SEVERITIES = {"Sev1", "Sev2", "Sev3", "Sev4"}
CAPABILITY_ROW = re.compile(r"^\|\s*(C-\d+)\s", re.M)
CAPABILITY_KEYS = ("id", "name", "legacy", "new", "switch", "smoke", "rollback", "state", "target")


def parse_time(value):
    parsed = dt.datetime.fromisoformat(value)
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=dt.timezone.utc)


def lookup(data, dotted):
    for part in dotted.split("."):
        if not isinstance(data, dict):
            return None
        data = data.get(part)
    return data


def adr_capabilities(path):
    with open(path, encoding="utf-8") as f:
        return CAPABILITY_ROW.findall(f.read())


def role_of(owner, roles):
    """Each '+'-joined part of an owner must start a role in plan['owners']."""
    return all(any(r.startswith(part.strip()) for r in roles) for part in owner.split("+"))


def prerequisite_items(prereq, root=ROOT):
    """(label, done) for every item a prerequisite requires."""
    with open(os.path.join(root, prereq["plan"]), encoding="utf-8") as f:
        plan = json.load(f)
    items = []
    rules = prereq.get("entry_rules", {})
    for collection in prereq.get("collections", []):
        rule = rules.get(collection, {})
        done_states = set(rule.get("done", DONE))
        for entry in lookup(plan, collection) or []:
            if prereq.get("ids") and entry.get("id") not in prereq["ids"]:
                continue
            label = (entry.get("id") or entry.get("role") or entry.get("party") or entry.get("consumer")
                     or entry.get("environment"))
            done = entry.get("status", entry.get("result")) in done_states
            done = done and ("evidence" not in entry or bool(entry.get("evidence")))
            done = done and all(entry.get(f) not in (None, "", "TBD") for f in rule.get("fields", []))
            items.append((f"{collection} {label}", done))
    for field in prereq.get("required_fields", []):
        items.append((f"{field} set", lookup(plan, field) not in (None, "", "TBD")))
    return items


def validate(plan, root=ROOT):
    errors = []
    for key in ("component", "replacement", "adr", "protected_branches", "owners", "prerequisites",
                "freeze_window", "capabilities", "steps", "smoke_tests", "go_no_go", "rollback", "hypercare"):
        if key not in plan:
            errors.append(f"missing '{key}'")
    if errors:
        return errors
    roles = [o["role"] for o in plan["owners"] if o.get("role")]
    if len(roles) != len(plan["owners"]):
        errors.append("owners: every owner needs a role")

    for kind in ("prerequisites", "capabilities", "steps", "smoke_tests", "go_no_go"):
        ids = [i.get("id") for i in plan[kind]]
        if None in ids or len(ids) != len(set(ids)):
            errors.append(f"{kind}: missing or duplicate ids")

    adr = os.path.join(root, plan["adr"])
    expected = adr_capabilities(adr) if os.path.isfile(adr) else []
    if not expected:
        errors.append(f"adr: no capability table found in {plan['adr']}")
    listed = [c.get("id") for c in plan["capabilities"]]
    if expected and listed != expected:
        errors.append(f"capabilities must be {', '.join(expected)} in ADR order, found {', '.join(map(str, listed))}")
    smoke_ids = {s.get("id") for s in plan["smoke_tests"]}
    for cap in plan["capabilities"]:
        missing = [k for k in CAPABILITY_KEYS if not cap.get(k)]
        if missing:
            errors.append(f"{cap.get('id')}: missing {', '.join(missing)}")
            continue
        for key in ("state", "target"):
            if cap[key] not in STATES:
                errors.append(f"{cap['id']}: {key} must be one of {', '.join(STATES)}")
        for ref in cap["smoke"]:
            if ref not in smoke_ids:
                errors.append(f"{cap['id']}: unknown smoke test {ref}")
    covered = {ref for cap in plan["capabilities"] for ref in cap.get("smoke", [])}
    for sid in sorted(smoke_ids - covered):
        errors.append(f"{sid}: not referenced by any capability")

    blocking = {s["id"] for s in plan["smoke_tests"] if s.get("blocks_reopen") is True}
    for s in plan["smoke_tests"]:
        if not isinstance(s.get("blocks_reopen"), bool):
            errors.append(f"{s.get('id')}: blocks_reopen must be true (checked before writes reopen) or false (hypercare)")
    gates = [st for st in plan["steps"] if "requires_smoke" in st]
    if len(gates) != 1:
        errors.append("steps: exactly one step (the end-of-freeze go/no-go) must list requires_smoke")
    elif set(gates[0]["requires_smoke"]) != blocking:
        errors.append(f"{gates[0].get('id')}: requires_smoke must be exactly the blocks_reopen smoke tests "
                      f"({', '.join(sorted(blocking))})")
    for step in plan["steps"]:
        if step.get("execute_on_branch") is not False:
            errors.append(f"{step.get('id')}: execute_on_branch must be false")
        if not step.get("action") or not step.get("owner") or not role_of(step["owner"], roles):
            errors.append(f"{step.get('id')}: needs an action and an owner from 'owners'")
    for crit in plan["go_no_go"]:
        if crit.get("status") not in STATUSES:
            errors.append(f"{crit.get('id')}: status must be one of {sorted(STATUSES)}")
        if crit.get("status") == "approved" and not crit.get("evidence"):
            errors.append(f"{crit.get('id')}: approved without evidence")
        if not crit.get("owner") or not role_of(crit["owner"], roles):
            errors.append(f"{crit.get('id')}: owner must be a role from 'owners'")
    if not role_of(plan["rollback"].get("decision_owner", ""), roles):
        errors.append("rollback: decision_owner must be a role from 'owners'")
    if not os.path.isfile(os.path.join(root, plan["rollback"].get("runbook", ""))):
        errors.append("rollback: runbook not found")

    for prereq in plan["prerequisites"]:
        path = os.path.join(root, prereq.get("plan", ""))
        if not os.path.isfile(path):
            errors.append(f"{prereq.get('id')}: plan {prereq.get('plan')} not found")
            continue
        with open(path, encoding="utf-8") as f:
            target = json.load(f)
        known = {e.get("id") for c in prereq.get("collections", []) for e in lookup(target, c) or []}
        for gid in prereq.get("ids", []):
            if gid not in known:
                errors.append(f"{prereq['id']}: {gid} not found in {prereq['plan']}")
        for collection in prereq.get("entry_rules", {}):
            if collection not in prereq.get("collections", []):
                errors.append(f"{prereq['id']}: entry_rules for {collection}, which is not in collections")
        if not prereq_has_items(prereq, root):
            errors.append(f"{prereq['id']}: requires nothing from {prereq['plan']}")

    window = plan["freeze_window"]
    if bool(window.get("start")) != bool(window.get("end")):
        errors.append("freeze_window: set both start and end, or neither")
    elif window.get("start"):
        try:
            hours = (parse_time(window["end"]) - parse_time(window["start"])).total_seconds() / 3600
            if not 0 < hours <= window.get("max_hours", 0):
                errors.append(f"freeze_window: {hours:g} hours is outside 0..{window.get('max_hours')}")
        except ValueError as e:
            errors.append(f"freeze_window: {e}")

    hyper = plan["hypercare"]
    if not isinstance(hyper.get("days"), int) or hyper["days"] < 14:
        errors.append("hypercare: days must be an integer of at least 14 (AB-530 acceptance)")
    for value in [hyper.get("cutover_completed_at")] + [i.get("opened") for i in hyper.get("incidents", [])]:
        if value:
            try:
                parse_time(value)
            except ValueError:
                errors.append(f"hypercare: '{value}' is not an ISO-8601 timestamp")
    for inc in hyper.get("incidents", []):
        if not inc.get("id") or inc.get("severity") not in SEVERITIES or not inc.get("opened"):
            errors.append(f"hypercare: incident {inc} needs id, severity in {sorted(SEVERITIES)} and opened")
    deferred = sorted(s["id"] for s in plan["smoke_tests"] if s.get("blocks_reopen") is False)
    checks = hyper.get("checks", [])
    if sorted(c.get("id") for c in checks) != deferred:
        errors.append(f"hypercare: checks must list exactly the smoke tests deferred to hypercare ({', '.join(deferred)})")
    for c in checks:
        if c.get("status") not in CHECK_STATUSES:
            errors.append(f"hypercare: check {c.get('id')} status must be one of {', '.join(sorted(CHECK_STATUSES))}")
        elif c["status"] != "pending" and not c.get("evidence"):
            errors.append(f"hypercare: check {c.get('id')} is {c['status']} without evidence")
    return errors


def prereq_has_items(prereq, root):
    return bool(prerequisite_items(prereq, root))


def decision(plan, root=ROOT):
    """(go, reasons). GO only when nothing below is open."""
    reasons = []
    for prereq in plan["prerequisites"]:
        open_items = [label for label, done in prerequisite_items(prereq, root) if not done]
        if open_items:
            reasons.append(f"{prereq['id']} {prereq['plan']}: {len(open_items)} open ({', '.join(open_items)})")
    if not plan["freeze_window"].get("start"):
        reasons.append("freeze window not agreed")
    for crit in plan["go_no_go"]:
        if crit["status"] != "approved":
            reasons.append(f"{crit['id']} {crit['status']}: {crit['criterion']}")
    unnamed = [o["role"] for o in plan["owners"] if not o.get("name")]
    if unnamed:
        reasons.append(f"{len(unnamed)} owner roles without a named person")
    return not reasons, reasons


def hypercare(plan, now):
    """(status, detail) with status in not-started, running, passed, breached."""
    hyper = plan["hypercare"]
    if not hyper.get("cutover_completed_at"):
        return "not-started", "cutover_completed_at not recorded"
    start = parse_time(hyper["cutover_completed_at"])
    end = start + dt.timedelta(days=hyper["days"])
    severe = [i for i in hyper.get("incidents", [])
              if i["severity"] in ("Sev1", "Sev2") and start <= parse_time(i["opened"]) < end]
    if severe:
        return "breached", "Sev1/Sev2 during hypercare: " + ", ".join(f"{i['id']} ({i['severity']})" for i in severe)
    failed = [c["id"] for c in hyper.get("checks", []) if c.get("status") == "failed"]
    if failed:
        return "breached", "hypercare check failed: " + ", ".join(failed)
    if now < end:
        return "running", f"no Sev1/Sev2 so far; ends {end.isoformat()}"
    waiting = [c["id"] for c in hyper.get("checks", []) if c.get("status") != "passed"]
    if waiting:
        return "running", f"{hyper['days']} days elapsed without Sev1/Sev2, still waiting for {', '.join(waiting)}"
    return "passed", f"{hyper['days']} days without Sev1/Sev2 (ended {end.isoformat()})"


def print_plan(plan, out=None):
    out = out or sys.stdout
    print(f"Cutover of {plan['component']} -> {plan['replacement']}", file=out)
    for cap in plan["capabilities"]:
        print(f"  {cap['id']} {cap['name']} [{cap['state']} -> {cap['target']}] switch: {cap['switch']}; "
              f"smoke {', '.join(cap['smoke'])}; rollback: {cap['rollback']}", file=out)
    for step in plan["steps"]:
        print(f"  step {step['id']} ({step['phase']}, {step['owner']}) {step['action']}", file=out)


def main(argv=None, now=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--plan", default=PLAN)
    ap.add_argument("--check", action="store_true", help="validate the plan file and exit")
    ap.add_argument("--hypercare", action="store_true", help="evaluate the hypercare criterion")
    ap.add_argument("--gate", action="store_true", help="exit non-zero unless GO / hypercare passed")
    args = ap.parse_args(argv)
    plan = fr.load(args.plan)
    errors = validate(plan)
    if errors:
        for e in errors:
            print(f"cutover: {e}", file=sys.stderr)
        return 1
    if args.check:
        go, reasons = decision(plan)
        print(f"cutover: plan valid, {len(plan['capabilities'])} capabilities, "
              f"{'GO' if go else f'NO-GO ({len(reasons)} open)'}")
        return 0
    if args.hypercare:
        status, detail = hypercare(plan, now or dt.datetime.now(dt.timezone.utc))
        print(f"cutover: hypercare {status}: {detail}")
        return {"passed": 0, "breached": 4}.get(status, 3) if args.gate else 0
    print_plan(plan)
    go, reasons = decision(plan)
    print(f"cutover: {'GO' if go else 'NO-GO'}")
    for reason in reasons:
        print(f"  - {reason}")
    print("cutover: nothing switched; the runbook is executed by its owners in production")
    return 0 if go or not args.gate else 3


if __name__ == "__main__":
    sys.exit(main())
