"""Shared helpers for the PAS backward-compatibility gate."""
import json
import os
import subprocess
import sys

ROOT = subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip()
BASE_REF = os.environ.get("COMPAT_BASE_REF", "origin/main")
ALLOWED_FILE = os.path.join(ROOT, "compat", "allowed-changes.json")


def git(*args, check=True):
    return subprocess.run(["git", *args], cwd=ROOT, text=True, capture_output=True, check=check).stdout


def merge_base():
    return git("merge-base", BASE_REF, "HEAD").strip()


def base_file(path):
    """Contents of path at the merge base, or None if it did not exist."""
    r = subprocess.run(["git", "show", f"{merge_base()}:{path}"], cwd=ROOT, text=True, capture_output=True)
    return r.stdout if r.returncode == 0 else None


def head_file(path):
    full = os.path.join(ROOT, path)
    if not os.path.exists(full):
        return None
    with open(full, encoding="utf-8", errors="replace") as fh:
        return fh.read()


def files_at_base(prefix=""):
    return [p for p in git("ls-tree", "-r", "--name-only", merge_base()).splitlines() if p.startswith(prefix)]


def files_at_head(prefix=""):
    return [p for p in git("ls-files").splitlines() if p.startswith(prefix)]


def allowed_ids():
    if not os.path.exists(ALLOWED_FILE):
        return {}
    with open(ALLOWED_FILE) as fh:
        entries = json.load(fh).get("allowed", [])
    return {e["id"]: e for e in entries}


def report(check_name, findings):
    """findings: list of (severity, id, message). severity in {FAIL, NOTE}.

    A FAIL whose id is listed in compat/allowed-changes.json (with a ticket) is
    downgraded to an approved change. Exits non-zero if any FAIL remains."""
    allowed = allowed_ids()
    lines, failed = [], False
    for sev, fid, msg in findings:
        if sev == "FAIL" and fid in allowed:
            e = allowed[fid]
            lines.append(f"APPROVED  {fid}: {msg} (ticket {e.get('ticket', '?')}: {e.get('reason', '')})")
        else:
            failed |= sev == "FAIL"
            lines.append(f"{sev:<9} {fid}: {msg}")
    status = "FAIL" if failed else "PASS"
    print(f"== {check_name}: {status}")
    for l in lines or ["no compatibility-relevant changes found"]:
        print("  " + l)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as fh:
            fh.write(f"### {check_name}: {status}\n")
            for l in lines or ["no compatibility-relevant changes found"]:
                fh.write(f"- `{l}`\n")
            fh.write("\n")
    return 1 if failed else 0


def exit_with(code):
    sys.exit(code)
