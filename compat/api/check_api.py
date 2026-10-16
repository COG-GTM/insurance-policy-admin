#!/usr/bin/env python3
"""Compare a running PAS read API against recorded contract snapshots.

Usage: check_api.py [--record] [--base-url http://localhost:8080]
Set COMPAT_AUTH_HEADER="Name: value" when the API under test requires auth.

Rules: status codes must match; every field in the snapshot must exist with
the same JSON type and value (fields in VOLATILE are type-checked only);
arrays must keep length and order; additive fields are allowed."""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SNAP_DIR = os.path.join(HERE, "snapshots")
sys.path.insert(0, os.path.dirname(HERE))
from common import report  # noqa: E402

POLICIES = ["POL-00000001", "POL-00000002", "POL-00000003", "POL-99999999"]
ENDPOINTS = [p for n in POLICIES for p in (f"/api/v1/policies/{n}", f"/api/v1/policies/{n}/coverages")]
VOLATILE = {"lastUpdated"}


def fetch(base, path):
    req = urllib.request.Request(base + path, headers={"Accept": "application/json"})
    hdr = os.environ.get("COMPAT_AUTH_HEADER")
    if hdr and ":" in hdr:
        k, v = hdr.split(":", 1)
        req.add_header(k.strip(), v.strip())
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            body = r.read().decode()
            return r.status, json.loads(body) if body else None
    except urllib.error.HTTPError as e:
        return e.code, None


def jtype(v):
    return {bool: "boolean", int: "number", float: "number", str: "string", list: "array", dict: "object", type(None): "null"}[type(v)]


def compare(exp, act, where, out, key=None):
    if jtype(exp) != jtype(act):
        out.append(("FAIL", f"api:{where}:type", f"{jtype(exp)} -> {jtype(act)} ({json.dumps(exp)[:40]} -> {json.dumps(act)[:40]})"))
    elif isinstance(exp, dict):
        for k, v in exp.items():
            if k not in act:
                out.append(("FAIL", f"api:{where}.{k}:removed", "field missing from response"))
            else:
                compare(v, act[k], f"{where}.{k}", out, k)
        for k in act.keys() - exp.keys():
            out.append(("NOTE", f"api:{where}.{k}:added", "additive field"))
    elif isinstance(exp, list):
        if len(exp) != len(act):
            out.append(("FAIL", f"api:{where}:length", f"{len(exp)} -> {len(act)} items"))
        for i, (e, a) in enumerate(zip(exp, act)):
            compare(e, a, f"{where}[{i}]", out)
    elif key not in VOLATILE and exp != act:
        out.append(("FAIL", f"api:{where}:value", f"{exp!r} -> {act!r}"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--record", action="store_true")
    ap.add_argument("--base-url", default=os.environ.get("COMPAT_BASE_URL", "http://localhost:8080"))
    a = ap.parse_args()
    snap_file = os.path.join(SNAP_DIR, "policies-v1.json")
    if a.record:
        data = {p: dict(zip(("status", "body"), fetch(a.base_url, p))) for p in ENDPOINTS}
        os.makedirs(SNAP_DIR, exist_ok=True)
        with open(snap_file, "w") as fh:
            json.dump(data, fh, indent=2, sort_keys=True)
            fh.write("\n")
        print(f"recorded {len(data)} responses to {snap_file}")
        return 0
    with open(snap_file) as fh:
        snaps = json.load(fh)
    findings = []
    for path, exp in snaps.items():
        status, body = fetch(a.base_url, path)
        if status != exp["status"]:
            findings.append(("FAIL", f"api:GET {path}:status", f"{exp['status']} -> {status}"))
        elif exp["body"] is not None:
            compare(exp["body"], body, f"GET {path}", findings)
    return report("REST API contract (GET /api/v1/policies)", findings)


if __name__ == "__main__":
    sys.exit(main())
