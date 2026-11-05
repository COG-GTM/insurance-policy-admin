#!/usr/bin/env python3
"""ADR checker (AB-518).

Validates the architecture decision records in docs/adr/, their component
registers (NNNN-components.json) and ARB submission packs, regenerates the
component tables, and checks that every quirk, follow-up, integration and
ticket an ADR cites exists in docs/modernization/.

    python3 tools/adr/check_adr.py --write   # regenerate the generated tables
    python3 tools/adr/check_adr.py           # check only (CI)
"""
import argparse
import json
import os
import re
import subprocess
import sys

ROOT = subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True,
                               cwd=os.path.dirname(os.path.abspath(__file__))).strip()
ADR_DIR = "docs/adr"
ARB_DIR = f"{ADR_DIR}/arb"
INDEX = f"{ADR_DIR}/README.md"
MOD = "docs/modernization"
QUIRKS = f"{MOD}/quirks.json"
OPEN_QUESTIONS = f"{MOD}/open-questions.md"
INVENTORY = f"{MOD}/behaviour-inventory.md"

ADR_FILE = re.compile(r"^(\d{4})-[a-z0-9]+(?:-[a-z0-9]+)*\.md$")
STATUS = re.compile(r"^(Proposed|Accepted|Rejected|Deprecated|Superseded by \d{4})$")
HEADER_FIELDS = ("Status", "Date", "Ticket", "New components", "Deciders")
SECTIONS = ("Context", "Decision drivers", "Decisions", "Consequences")
COMPONENT_ID = re.compile(r"^(SVC|DS|MSG|INT|SEC|PLT)-\d{2}$")
KINDS = {"service", "batch", "data-store", "messaging", "integration", "security", "platform"}
STORES = {"data-store", "messaging"}
CLASSIFICATIONS = {"Public", "Internal", "Confidential", "Restricted"}
REVIEW = re.compile(r"^(pending|n/a|(approved|rejected) by .+ \d{4}-\d{2}-\d{2})$")
COMPONENT_FIELDS = ("id", "name", "kind", "decision", "purpose", "replaces", "data", "classification",
                    "encryption", "retention", "owner", "introduced_by", "reviews")
EPIC = range(516, 531)
EXTERNAL_TICKETS = {"AB-364", "AB-365"}  # related tickets named in AB-524 / AB-526


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as fh:
        return fh.read()


def exists(path):
    return os.path.exists(os.path.join(ROOT, path))


def adr_files():
    if not exists(ADR_DIR):
        return []
    return sorted(f for f in os.listdir(os.path.join(ROOT, ADR_DIR)) if ADR_FILE.match(f))


def header(text, field):
    m = re.search(rf"^- \*\*{re.escape(field)}:\*\*\s*(.+)$", text, re.M)
    return m.group(1).strip() if m else None


def decisions(text):
    """Return {Dn: body} for every '### Dn: ...' section."""
    parts = re.split(r"^### (D\d+):.*$", text, flags=re.M)
    out = {}
    for i in range(1, len(parts), 2):
        body = re.split(r"^## ", parts[i + 1], maxsplit=1, flags=re.M)[0]
        out[parts[i]] = body
    return out


def alternatives(body):
    """Rows of the 'Alternatives considered' table: [(option, outcome)]."""
    m = re.search(r"\*\*Alternatives considered\*\*\s*\n((?:\s*\|.*\n?)+)", body)
    if not m:
        return None
    rows = [r for r in m.group(1).strip().splitlines() if r.strip().startswith("|")][2:]
    return [tuple(c.strip() for c in r.strip().strip("|").split("|")[:2]) for r in rows]


def check_adr(name, text):
    errors = []
    status = header(text, "Status")
    for f in HEADER_FIELDS:
        if not header(text, f):
            errors.append(f"{name}: missing header field '{f}'")
    if status and not STATUS.match(status):
        errors.append(f"{name}: invalid Status {status!r}")
    for s in SECTIONS:
        if not re.search(rf"^## {re.escape(s)}\s*$", text, re.M):
            errors.append(f"{name}: missing section '## {s}'")
    ds = decisions(text)
    if not ds:
        errors.append(f"{name}: no '### Dn: ...' decisions")
    for d, body in ds.items():
        rows = alternatives(body)
        if rows is None:
            errors.append(f"{name}: {d} has no 'Alternatives considered' table")
            continue
        chosen = [r for r in rows if "chosen" in r[1].lower()]
        rejected = [r for r in rows if "rejected" in r[1].lower()]
        if len(chosen) != 1:
            errors.append(f"{name}: {d} must mark exactly one option as Chosen (found {len(chosen)})")
        if not rejected:
            errors.append(f"{name}: {d} must list at least one rejected alternative")
    return errors, ds


def validate_components(path, components, decision_ids):
    errors, seen = [], set()
    for c in components:
        cid = c.get("id", "?")
        missing = [f for f in COMPONENT_FIELDS if not c.get(f)]
        if missing:
            errors.append(f"{path}: {cid}: missing {', '.join(missing)}")
        if "pii" not in c or not isinstance(c.get("pii"), bool):
            errors.append(f"{path}: {cid}: pii must be true or false")
        if not COMPONENT_ID.match(cid):
            errors.append(f"{path}: {cid}: id must match {COMPONENT_ID.pattern}")
        if cid in seen:
            errors.append(f"{path}: {cid}: duplicate id")
        seen.add(cid)
        if c.get("kind") and c["kind"] not in KINDS:
            errors.append(f"{path}: {cid}: kind must be one of {sorted(KINDS)}")
        if c.get("classification") and c["classification"] not in CLASSIFICATIONS:
            errors.append(f"{path}: {cid}: classification must be one of {sorted(CLASSIFICATIONS)}")
        if c.get("decision") and c["decision"] not in decision_ids:
            errors.append(f"{path}: {cid}: decision {c['decision']} is not a '### Dn' section of the ADR")
        t = re.fullmatch(r"AB-(\d+)", c.get("introduced_by", ""))
        if not t or int(t.group(1)) not in range(519, 531):
            errors.append(f"{path}: {cid}: introduced_by must be one of AB-519..AB-530")
        reviews = c.get("reviews") or {}
        for r in ("security", "data_governance"):
            v = reviews.get(r)
            if not v or not REVIEW.match(v):
                errors.append(f"{path}: {cid}: reviews.{r} must be pending, n/a or 'approved|rejected by <who> <YYYY-MM-DD>'")
        if reviews.get("security") == "n/a":
            errors.append(f"{path}: {cid}: every new component needs a security review")
        needs_dg = c.get("pii") or c.get("classification") == "Restricted" or c.get("kind") in STORES
        if needs_dg and reviews.get("data_governance") == "n/a":
            errors.append(f"{path}: {cid}: data stores, Restricted and PII components need a data governance review")
    return errors


def register_errors(path, text, num, register_exists):
    """An ADR either links its NNNN-components.json register or declares 'New components: none'."""
    declared = (header(text, "New components") or "").strip()
    comp = f"{num}-components.json"
    if not declared:
        return []  # reported as a missing header field
    if declared.lower() == "none":
        return [f"{path}: declares 'New components: none' but {comp} exists"] if register_exists else []
    if not register_exists:
        return [f"{path}: needs a component register {ADR_DIR}/{comp} (or 'New components: none')"]
    if f"]({comp})" not in declared:
        return [f"{path}: 'New components' header must link {comp}"]
    return []


def components_table(components):
    rows = ["| ID | Component | Kind | Decision | Purpose | Data held | Classification | PII | Encryption | Retention | "
            "Owner | Introduced by | Security review | Data governance review |",
            "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for c in components:
        rows.append(f"| {c['id']} | {c['name']} | {c['kind']} | {c['decision']} | {c['purpose']} | {c['data']} | "
                    f"{c['classification']} | {'yes' if c['pii'] else 'no'} | {c['encryption']} | {c['retention']} | "
                    f"{c['owner']} | {c['introduced_by']} | {c['reviews']['security']} | "
                    f"{c['reviews']['data_governance']} |")
    return "\n".join(rows)


def review_checklist(components):
    rows = ["| ID | Component | Replaces | Classification | PII | Introduced by | Security review | Data governance review |",
            "|---|---|---|---|---|---|---|---|"]
    for c in components:
        rows.append(f"| {c['id']} | {c['name']} | {c['replaces']} | {c['classification']} | "
                    f"{'yes' if c['pii'] else 'no'} | {c['introduced_by']} | {c['reviews']['security']} | "
                    f"{c['reviews']['data_governance']} |")
    return "\n".join(rows)


def replace_block(text, name, body):
    begin, end = f"<!-- BEGIN GENERATED: {name} -->", f"<!-- END GENERATED: {name} -->"
    pattern = re.compile(re.escape(begin) + r".*?" + re.escape(end), re.S)
    if not pattern.search(text):
        return None
    return pattern.sub(lambda _: f"{begin}\n{body}\n{end}", text)


def known_refs():
    quirks = {q["id"] for q in json.loads(read(QUIRKS))["quirks"]}
    fus = set(re.findall(r"^\|\s*(FU-\d+)\s*\|", read(OPEN_QUESTIONS), re.M))
    integrations = set(re.findall(r"^\|\s*(I-\d+)\s*\|", read(INVENTORY), re.M))
    return quirks, fus, integrations


def reference_errors(path, text, refs):
    quirks, fus, integrations = refs
    errors = []
    for q in sorted(set(re.findall(r"\bQ-\d+(?:-\d+)?\b", text))):
        if q not in quirks:
            errors.append(f"{path}: unknown quirk {q}")
    for f in sorted(set(re.findall(r"\bFU-\d+\b", text))):
        if f not in fus:
            errors.append(f"{path}: unknown follow-up {f}")
    for lo, hi in re.findall(r"\bI-(\d+)(?:\.\.I-(\d+))?\b", text):
        for n in range(int(lo), int(hi or lo) + 1):
            if f"I-{n}" not in integrations:
                errors.append(f"{path}: unknown integration I-{n}")
    for t in sorted(set(re.findall(r"\bAB-\d+\b", text))):
        if int(t[3:]) not in EPIC and t not in EXTERNAL_TICKETS:
            errors.append(f"{path}: ticket {t} is outside epic AB-516")
    return errors


def slug(heading):
    """GitHub-style anchor for a Markdown heading."""
    h = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", heading.strip().lower())
    h = re.sub(r"[^\w\- ]", "", h)
    return h.replace(" ", "-")


FENCE = re.compile(r"^ {0,3}(`{3,}|~{3,})")


def prose_lines(text):
    """Lines outside fenced (``` or ~~~) code blocks; fenced lines become blank."""
    out, fence = [], None
    for line in text.splitlines():
        m = FENCE.match(line)
        if fence is None and m:
            fence = m.group(1)
            out.append("")
            continue
        if fence is not None:
            if m and m.group(1)[0] == fence[0] and len(m.group(1)) >= len(fence) and not line.strip()[len(m.group(1)):].strip():
                fence = None
            out.append("")
            continue
        out.append(line)
    return out


def anchors(text):
    found, counts = set(), {}
    lines = prose_lines(text)
    for i, line in enumerate(lines):
        m = re.match(r"^ {0,3}#{1,6}\s+(.+?)\s*#*\s*$", line)
        heading = m.group(1) if m else None
        if heading is None and i + 1 < len(lines) and re.match(r"^ {0,3}(=+|-+)\s*$", lines[i + 1]):
            prev = lines[i - 1] if i else ""
            if line.strip() and not line.startswith("    ") and not re.match(r"^\s*(([-*+]|\d+[.)])\s|[|>])", line) \
                    and not prev.strip():
                heading = line.strip()
        if heading:
            s = slug(heading)
            n = counts.get(s, 0)
            counts[s] = n + 1
            found.add(s if n == 0 else f"{s}-{n}")
    found.update(re.findall(r"<a\s+(?:id|name)=\"([^\"]+)\"", text))
    return found


def link_errors(path, text):
    errors = []
    base = os.path.dirname(path)
    prose = "\n".join(prose_lines(text))
    for target, frag in re.findall(r"\]\(([^)#\s]*)(?:#([^)\s]*))?\)", prose):
        if re.match(r"^[a-z]+:", target) or not (target or frag):
            continue
        resolved = os.path.normpath(os.path.join(base, target)) if target else path
        if target and not exists(resolved):
            errors.append(f"{path}: broken link {target}")
            continue
        if frag and resolved.endswith(".md"):
            if frag not in anchors(text if not target else read(resolved)):
                errors.append(f"{path}: broken anchor {target}#{frag}")
    return errors


def run(write=False):
    errors, outputs = [], {}
    files = adr_files()
    if not files:
        return [f"{ADR_DIR}: no ADRs found"], outputs
    refs = known_refs()
    index = read(INDEX) if exists(INDEX) else ""
    if not index:
        errors.append(f"{INDEX}: missing")
    for f in files:
        path, num = f"{ADR_DIR}/{f}", f[:4]
        text = read(path)
        errs, ds = check_adr(path, text)
        errors += errs
        if f"]({f})" not in index:
            errors.append(f"{INDEX}: ADR {f} is not listed in the index")
        arb = f"{ARB_DIR}/{num}-arb-submission.md"
        arb_text = read(arb) if exists(arb) else None
        if header(text, "Status") == "Proposed" and arb_text is None:
            errors.append(f"{path}: Proposed ADR needs an ARB submission at {arb}")
        comp_path = f"{ADR_DIR}/{num}-components.json"
        reg_errors = register_errors(path, text, num, exists(comp_path))
        errors += reg_errors
        if exists(comp_path) and not reg_errors:
            components = json.loads(read(comp_path))["components"]
            errors += validate_components(comp_path, components, set(ds))
            if not any(e.startswith(comp_path) for e in errors):
                new = replace_block(text, "components", components_table(components))
                if new is None:
                    errors.append(f"{path}: missing 'components' generated block")
                else:
                    outputs[path] = new
                if arb_text is not None:
                    new_arb = replace_block(arb_text, "review-checklist", review_checklist(components))
                    if new_arb is None:
                        errors.append(f"{arb}: missing 'review-checklist' generated block")
                    else:
                        outputs[arb] = new_arb
        if arb_text is not None:
            for d in ds:
                if not re.search(rf"\b{d}\b", arb_text):
                    errors.append(f"{arb}: decision {d} is not presented to the ARB")
            if not re.search(r"^## Sign-off", arb_text, re.M) or "| pending |" not in arb_text and "approved" not in arb_text:
                errors.append(f"{arb}: needs a '## Sign-off' table with pending/approved reviews")
            errors += reference_errors(arb, arb_text, refs) + link_errors(arb, arb_text)
        errors += reference_errors(path, text, refs) + link_errors(path, text)
    errors += link_errors(INDEX, index)
    stale = [p for p, body in outputs.items() if read(p) != body]
    if write:
        for p in stale:
            with open(os.path.join(ROOT, p), "w", encoding="utf-8") as fh:
                fh.write(outputs[p])
            print(f"updated {p}")
    else:
        errors += [f"{p} is stale: run python3 tools/adr/check_adr.py --write" for p in stale]
    return errors, outputs


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--write", action="store_true", help="regenerate the generated tables")
    args = ap.parse_args(argv)
    errors, _ = run(write=args.write)
    if errors:
        print("\n".join(["ADR check FAILED:"] + ["  " + e for e in errors]))
        return 1
    print(f"ADR check PASS: {len(adr_files())} ADR(s), component registers and ARB packs valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
