#!/usr/bin/env python3
"""Gated retirement of the Spring Boot 1.5 facade (AB-524).

Dry run by default: prints the gates from docs/pas/facade-retirement.json and the steps that would
run. --check validates the plan file. --execute runs the scripted steps only when every gate is
approved with evidence and the current branch is not protected; it changes the working tree and
never commits or pushes.
"""
import argparse
import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PLAN = os.path.join(ROOT, "docs", "pas", "facade-retirement.json")
STATUSES = {"pending", "approved", "rejected"}


def load(path=PLAN):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def validate(plan):
    errors = []
    for key in ("component", "replacement", "protected_branches", "gates", "steps"):
        if key not in plan:
            errors.append(f"missing '{key}'")
    for kind in ("gates", "steps"):
        ids = [item.get("id") for item in plan.get(kind, [])]
        if len(ids) != len(set(ids)):
            errors.append(f"duplicate {kind} ids")
        for item in plan.get(kind, []):
            if not item.get("id") or not item.get("description"):
                errors.append(f"{kind} entry without id or description: {item}")
    for gate in plan.get("gates", []):
        if gate.get("status") not in STATUSES:
            errors.append(f"{gate.get('id')}: status must be one of {sorted(STATUSES)}")
        if gate.get("status") == "approved" and not gate.get("evidence"):
            errors.append(f"{gate.get('id')}: approved without evidence")
        if not gate.get("owner"):
            errors.append(f"{gate.get('id')}: no owner")
    return errors


def open_gates(plan):
    return [g for g in plan["gates"] if g["status"] != "approved" or not g.get("evidence")]


def current_branch():
    return subprocess.run(
        ["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=ROOT, text=True, capture_output=True, check=True
    ).stdout.strip()


def refusal(plan, branch):
    """Why --execute must not run, or None."""
    blocked = open_gates(plan)
    if blocked:
        return "gates not approved: " + ", ".join(g["id"] for g in blocked)
    if branch in plan["protected_branches"]:
        return f"refusing to run on protected branch {branch}"
    return None


def print_plan(plan, out=None):
    out = out or sys.stdout
    print(f"Retirement of {plan['component']} -> {plan['replacement']}", file=out)
    for g in plan["gates"]:
        print(f"  gate {g['id']} [{g['status']}] {g['description']} (owner: {g['owner']})", file=out)
    for s in plan["steps"]:
        how = f"would run: {s['command']}" if s.get("command") else "manual"
        print(f"  step {s['id']} {s['description']} ({how})", file=out)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--plan", default=PLAN)
    ap.add_argument("--check", action="store_true", help="validate the plan file and exit")
    ap.add_argument("--execute", action="store_true", help="run the scripted steps (gated)")
    args = ap.parse_args(argv)
    plan = load(args.plan)
    errors = validate(plan)
    if errors:
        for e in errors:
            print(f"facade-retirement: {e}", file=sys.stderr)
        return 1
    if args.check:
        print(f"facade-retirement: plan valid, {len(open_gates(plan))} of {len(plan['gates'])} gates open")
        return 0
    print_plan(plan)
    if not args.execute:
        print("facade-retirement: dry run, nothing changed")
        return 0
    reason = refusal(plan, current_branch())
    if reason:
        print(f"facade-retirement: {reason}", file=sys.stderr)
        return 2
    for step in plan["steps"]:
        if step.get("command"):
            print(f"facade-retirement: {step['id']}: {step['command']}")
            subprocess.run(step["command"], shell=True, cwd=ROOT, check=True)
    print("facade-retirement: working tree changed; review, commit and open a PR")
    return 0


if __name__ == "__main__":
    sys.exit(main())
