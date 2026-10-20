#!/usr/bin/env python3
"""AB-530: rewire Makefile, CI workflows and the compat allow-list after cobol/, jcl/ and
java-facade/ are removed. Called by remove-legacy-trees.sh; every edit asserts its anchor, so drift
in these files fails the decommission dry run in CI instead of producing a half-edited build.

  rewire_build.py build              Makefile + workflows
  rewire_build.py allow BASE_SHA     allow-list the compat findings caused by the removal
"""
import json
import os
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
REMOVED = ("cobol/", "jcl/", "java-facade/", "parity/tools/")
TICKET = "AB-530"
DRY_RUN_COMMENT = ("#  - decommission-dry-run: runs scripts/decommission/remove-legacy-trees.sh in a scratch copy and\n"
                   "#    proves pas-platform builds and compat/parity pass once the legacy trees are gone.\n")


def edit(path, fn):
    p = ROOT / path
    before = p.read_text(encoding="utf-8")
    after = fn(before)
    if after == before:
        sys.exit(f"rewire: no change made to {path}")
    p.write_text(after, encoding="utf-8")
    print(f"rewire: {path}")


def replace(text, old, new):
    if old not in text:
        sys.exit(f"rewire: anchor not found: {old!r}")
    return text.replace(old, new, 1)


def drop_make_target(text, name):
    """Remove `name:` and its recipe lines (plus a directly preceding comment block)."""
    pattern = re.compile(rf"(?:^#[^\n]*\n)*^{re.escape(name)}:[^\n]*\n(?:\t[^\n]*\n)*\n?", re.M)
    new, n = pattern.subn("", text)
    if n != 1:
        sys.exit(f"rewire: Makefile target {name} found {n} times")
    return new


def drop_workflow_job(text, job):
    """Remove a top-level job (two-space indented key under jobs:) up to the next job or EOF."""
    pattern = re.compile(rf"^  {re.escape(job)}:\n(?:(?:    [^\n]*|\s*)\n)*?(?=^  \S|\Z)", re.M)
    new, n = pattern.subn("", text)
    if n != 1:
        sys.exit(f"rewire: workflow job {job} found {n} times")
    return new.rstrip("\n") + "\n"


def makefile(text):
    phony = re.search(r"^\.PHONY: compat [^\n]*$", text, re.M)
    if not phony:
        sys.exit("rewire: compat .PHONY line not found")
    words = [w for w in phony.group(0).split()
             if w not in ("compat-api", "parity-cobol", "parity-golden", "parity-extracts-golden")]
    text = text.replace(phony.group(0), " ".join(words), 1)
    text = replace(text, "compat: compat-static compat-build compat-api", "compat: compat-static compat-build")
    text = drop_make_target(text, "compat-api")
    text = replace(text, "parity: parity-cobol parity-java parity-extracts", "parity: parity-java parity-extracts")
    text = drop_make_target(text, "parity-cobol")
    text = drop_make_target(text, "parity-golden")
    text = drop_make_target(text, "parity-extracts-golden")
    text = replace(text, "# AB-527: oracle goldens are current, then the Java jobs reproduce them byte for byte (needs Docker).",
                   "# AB-527: the Java jobs reproduce the committed extract goldens byte for byte (needs Docker).")
    text = replace(text, "\tpython3 parity/extracts/oracle.py check\n", "")
    text = drop_make_target(text, "decommission-dry-run")
    text = text.replace(" decommission-dry-run", "", 1)
    return text


def build():
    edit("Makefile", makefile)
    edit(".github/workflows/compat-gate.yml", lambda t: drop_workflow_job(t, "api-contract"))
    edit(".github/workflows/parity.yml", lambda t: drop_workflow_job(t, "cobol-golden"))
    edit(".github/workflows/cutover-rehearsal.yml", lambda t: drop_workflow_job(replace(
        t, DRY_RUN_COMMENT, ""), "decommission-dry-run"))


def allow(base_sha):
    env = dict(os.environ, COMPAT_BASE_REF=base_sha)
    ids = []
    for check in ("check_schema.py", "check_layouts.py"):
        out = subprocess.run([sys.executable, f"compat/{check}"], cwd=ROOT, env=env,
                             text=True, capture_output=True).stdout
        ids += re.findall(r"^\s*FAIL\s+(\S+):", out, re.M)
    unexpected = [i for i in ids if not any(f":{r}" in i for r in REMOVED)]
    if unexpected:
        sys.exit(f"rewire: compat findings not caused by the legacy removal: {unexpected}")
    path = ROOT / "compat" / "allowed-changes.json"
    doc = json.loads(path.read_text(encoding="utf-8"))
    known = {e["id"] for e in doc["allowed"]}
    for i in ids:
        if i not in known:
            doc["allowed"].append({
                "id": i,
                "ticket": TICKET,
                "reason": "legacy tree removed after cutover sign-off (docs/modernization/decommission.md); "
                          "copybooks kept in parity/contracts/copybooks, ACMEINS DDL/seed in parity/fixtures/acmeins",
            })
    path.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
    print(f"rewire: allow-listed {len(ids)} compat findings for {TICKET}")


if __name__ == "__main__":
    if sys.argv[1:2] == ["build"]:
        build()
    elif sys.argv[1:2] == ["allow"] and len(sys.argv) == 3:
        allow(sys.argv[2])
    else:
        sys.exit(__doc__)
