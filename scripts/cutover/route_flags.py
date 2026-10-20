#!/usr/bin/env python3
"""PAS route-flag file tool (ADR 0001 D7). Used by the cutover/rollback runbooks and rehearsal.

  route_flags.py validate [--file F] [--production]
  route_flags.py show     [--file F]
  route_flags.py set      [--file F] [--production] STATE CAPABILITY... | --all-merged | --all

Rules (exit 2 on violation):
  * the capability set is exactly the ADR 0001 D7 list; states are legacy|shadow|new|retired;
  * write capabilities flip together: if one is new/retired, every merged write capability is too;
  * retired only when every capability of its kind (read/write/feed) is new or retired;
  * --production: no capability may be new/retired while it is still pendingMerge.
"""
import argparse
import json
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
DEFAULT = REPO / "pas-platform" / "routing" / "route-flags.json"
CAPABILITIES = [
    "inquiry", "broker-read", "new-business", "underwriting", "renewal", "endorsement",
    "premium-batch", "claims-extract", "actuarial-exposure", "document-events",
]
STATES = ["legacy", "shadow", "new", "retired"]
LIVE = {"new", "retired"}


def load(path):
    return json.loads(pathlib.Path(path).read_text(encoding="utf-8"))


def errors(doc, production):
    caps = doc.get("capabilities", {})
    errs = []
    if sorted(caps) != sorted(CAPABILITIES):
        errs.append(f"capabilities must be exactly {CAPABILITIES}, got {sorted(caps)}")
    for name, c in caps.items():
        if c.get("state") not in STATES:
            errs.append(f"{name}: state {c.get('state')!r} not in {STATES}")
        if production and c.get("state") in LIVE and c.get("pendingMerge"):
            errs.append(f"{name}: {c['state']} while pending {c['pendingMerge']} merge")
    writes = {n: c for n, c in caps.items() if c.get("kind") == "write"}
    live = [n for n, c in writes.items() if c.get("state") in LIVE]
    if live:
        lagging = [n for n, c in writes.items()
                   if c.get("state") not in LIVE and (production or not c.get("pendingMerge"))]
        if lagging:
            errs.append(f"write capabilities flip together (D7): {live} live but {lagging} not")
    for name, c in caps.items():
        if c.get("state") == "retired":
            group = [o for o in caps.values() if o.get("kind") == c.get("kind")]
            if any(o.get("state") not in LIVE for o in group):
                errs.append(f"{name}: retired before its {c.get('kind')} group is new")
    return errs


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["validate", "show", "set"])
    ap.add_argument("args", nargs="*")
    ap.add_argument("--file", default=str(DEFAULT))
    ap.add_argument("--production", action="store_true")
    ap.add_argument("--all-merged", action="store_true", help="set: every capability not pendingMerge")
    ap.add_argument("--all", action="store_true", help="set: every capability")
    a = ap.parse_intermixed_args(argv)
    doc = load(a.file)
    caps = doc["capabilities"]
    if a.command == "show":
        for name in CAPABILITIES:
            c = caps.get(name, {})
            pending = f"  (pending {c['pendingMerge']} merge)" if c.get("pendingMerge") else ""
            print(f"{name:20} {c.get('state', '?')}{pending}")
        return 0
    if a.command == "set":
        if not a.args or a.args[0] not in STATES:
            ap.error(f"set needs a STATE in {STATES}")
        state, targets = a.args[0], a.args[1:]
        if a.all:
            targets = list(CAPABILITIES)
        elif a.all_merged:
            targets = [n for n in CAPABILITIES if not caps[n].get("pendingMerge")]
        unknown = [t for t in targets if t not in caps]
        if not targets or unknown:
            ap.error(f"unknown or no capabilities: {unknown or targets}")
        for t in targets:
            caps[t]["state"] = state
    errs = errors(doc, a.production)
    for e in errs:
        print(f"route-flags: {e}", file=sys.stderr)
    if errs:
        return 2
    if a.command == "set":
        pathlib.Path(a.file).write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
        print(f"route-flags: {state} -> {', '.join(targets)} ({a.file})")
    else:
        print(f"route-flags: {a.file} valid{' (production rules)' if a.production else ''}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
