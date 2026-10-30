"""Shared helpers for the PAS backward-compatibility gate."""
import json
import os
import re
import subprocess
import sys

ROOT = subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip()
BASE_REF = os.environ.get("COMPAT_BASE_REF", "origin/main")
ALLOWED_PATH = "compat/allowed-changes.json"
TICKET_RE = re.compile(r"^[A-Z][A-Z0-9]+-[1-9][0-9]*$")


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


def _parse_allowed(text, source):
    """Valid entries from an allowed-changes.json body, plus problems found."""
    entries, problems = {}, []
    try:
        raw = json.loads(text).get("allowed", [])
    except (ValueError, AttributeError) as exc:
        return {}, [f"{source}: unreadable ({exc})"]
    if not isinstance(raw, list):
        return {}, [f"{source}: 'allowed' is not a list"]
    for e in raw:
        fid = e.get("id") if isinstance(e, dict) else None
        if not isinstance(fid, str) or not fid:
            problems.append(f"{source}: entry without an id ignored")
            continue
        ticket, reason = e.get("ticket"), e.get("reason")
        if not isinstance(ticket, str) or not TICKET_RE.match(ticket):
            problems.append(f"{source}: {fid} ignored, ticket must look like AB-524")
            continue
        if not isinstance(reason, str) or not reason.strip():
            problems.append(f"{source}: {fid} ignored, reason is empty")
            continue
        entries[fid] = {"id": fid, "ticket": ticket, "reason": reason.strip()}
    return entries, problems


def allowed_ids():
    """Approved exceptions, read from the protected base branch (BASE_REF).

    The working-tree copy is never trusted: a change cannot approve its own
    failures, so an exception must be merged to the base branch first."""
    r = subprocess.run(["git", "show", f"{BASE_REF}:{ALLOWED_PATH}"], cwd=ROOT, text=True, capture_output=True)
    if r.returncode != 0:
        exists = subprocess.run(["git", "rev-parse", "--verify", "--quiet", f"{BASE_REF}^{{commit}}"],
                                cwd=ROOT, capture_output=True).returncode == 0
        if exists:
            return {}, []
        return {}, [f"base ref {BASE_REF} not found; no exceptions applied"]
    return _parse_allowed(r.stdout, f"{BASE_REF}:{ALLOWED_PATH}")


def pending_ids(approved):
    """Ids listed in the working tree but not yet approved on the base branch."""
    text = head_file(ALLOWED_PATH)
    if text is None:
        return set()
    entries, _ = _parse_allowed(text, ALLOWED_PATH)
    return set(entries) - set(approved)


def report(check_name, findings):
    """findings: list of (severity, id, message). severity in {FAIL, NOTE}.

    A FAIL whose id is listed in compat/allowed-changes.json on the base branch
    (with a ticket and reason) is downgraded to an approved change. Entries
    added by the change under test are reported as PENDING and still fail.
    Exits non-zero if any FAIL remains."""
    allowed, problems = allowed_ids()
    pending = pending_ids(allowed)
    lines, failed = [f"NOTE      allowlist: {p}" for p in problems], False
    for sev, fid, msg in findings:
        if sev == "FAIL" and fid in allowed:
            e = allowed[fid]
            lines.append(f"APPROVED  {fid}: {msg} (ticket {e['ticket']}: {e['reason']}; approved on {BASE_REF})")
        else:
            failed |= sev == "FAIL"
            lines.append(f"{sev:<9} {fid}: {msg}")
            if sev == "FAIL" and fid in pending:
                lines.append(f"PENDING   {fid}: exception added by this change; it only applies once merged to {BASE_REF}")
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
