#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_REPO="$(cd "$SCRIPT_DIR/../.." && pwd)"
repo=''
repo_set=false
execute=false
commit=false
skip_tag_check=false
understand_real_repo=false

usage() {
  printf 'Usage: %s [--execute --repo PATH] [--commit] [--skip-tag-check] [--i-understand-this-is-the-real-repo]\n' "${0##*/}"
}

while (($#)); do
  case "$1" in
    --repo)
      (($# >= 2)) || { usage >&2; exit 2; }
      repo="$2"
      repo_set=true
      shift
      ;;
    --execute) execute=true ;;
    --commit) commit=true ;;
    --skip-tag-check) skip_tag_check=true ;;
    --i-understand-this-is-the-real-repo) understand_real_repo=true ;;
    --help|-h) usage; exit 0 ;;
    *) printf 'Unknown argument: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

if [[ -z "$repo" ]]; then
  repo="$DEFAULT_REPO"
fi
repo="$(cd "$repo" && pwd -P)"
real_repo="$(git -C "$DEFAULT_REPO" rev-parse --show-toplevel)"
if [[ "$execute" != true ]]; then
  printf 'DRY-RUN: target %s\n' "$repo"
  printf 'Would remove cobol/, jcl/, java-facade/, parity/tools/harness.py, and parity/tools/test_harness.py.\n'
  printf 'Would update compat allow-list, Makefile, CI workflows, README, CODEOWNERS, and copybook drift test.\n'
  exit 0
fi

if [[ "$repo_set" != true ]]; then
  printf 'Execute mode requires --repo PATH.\n' >&2
  exit 2
fi
if [[ "$repo" == "$real_repo" ]]; then
  if [[ "$understand_real_repo" != true || ! "${PAS_DECOMMISSION_APPROVED:-}" =~ ^(ARB|CHG)-[A-Za-z0-9-]+$ ]]; then
    printf 'Refusing the real checkout; use an isolated worktree or provide the explicit override and PAS_DECOMMISSION_APPROVED ticket.\n' >&2
    exit 1
  fi
fi
if [[ -n "$(git -C "$repo" status --porcelain)" ]]; then
  printf 'Refusing target with uncommitted changes: %s\n' "$repo" >&2
  exit 1
fi
if [[ "$skip_tag_check" != true ]] \
  && ! git -C "$repo" rev-parse -q --verify refs/tags/pas-legacy-final >/dev/null; then
  printf 'Refusing decommission: target repo is missing tag pas-legacy-final.\n' >&2
  exit 1
fi

python3 - "$repo" <<'PY'
import json
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1])

def replace_once(path, old, new):
    file_path = root / path
    text = file_path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one anchor in {path}, found {count}: {old[:100]!r}")
    file_path.write_text(text.replace(old, new), encoding="utf-8")

tracked = subprocess.check_output(
    ["git", "-C", str(root), "ls-files", "cobol", "jcl"], text=True
).splitlines()
allowed_path = root / "compat/allowed-changes.json"
allowed = json.loads(allowed_path.read_text(encoding="utf-8"))
ids = {
    f"layout:{path}:removed"
    for path in tracked
    if path.lower().endswith((".cpy", ".jcl"))
}
tracked_facade = subprocess.check_output(
    ["git", "-C", str(root), "ls-files", "java-facade"], text=True
).splitlines()
ids.update(
    f"schema:{path}:file-removed"
    for path in tracked_facade
    if path.endswith("src/main/resources/schema.sql")
)
existing = {item["id"] for item in allowed["allowed"]}
for finding in sorted(ids - existing):
    allowed["allowed"].append({
        "id": finding,
        "ticket": "AB-530",
        "reason": "mainframe decommission after cutover + hypercare; facade traffic zero",
    })
allowed_path.write_text(json.dumps(allowed, indent=2) + "\n", encoding="utf-8")

replace_once(
    "Makefile",
    "\t./compat/api/run_facade.sh\n",
    "\t./compat/api/run_modern.sh\n",
)
replace_once(
    "Makefile",
    "parity:\n\tpython3 parity/tools/harness.py check\n\tpython3 -m unittest discover -s parity/tools\n\tcd pas-platform && ./mvnw -B -ntp -pl pas-policy-service -am test\n",
    "parity:\n\tcd pas-platform && ./mvnw -B -ntp -pl pas-policy-service -am test\n",
)
replace_once(
    "Makefile",
    "parity-golden:\n\tpython3 parity/tools/harness.py golden\n\nparity-golden-check: parity-golden\n\tgit diff --exit-code -- parity/golden\n\ttest -z \"$$(git ls-files --others --exclude-standard -- parity/golden)\"\n\n",
    "",
)
makefile = (root / "Makefile").read_text(encoding="utf-8")
makefile = makefile.replace(" parity-golden parity-golden-check", "")
(root / "Makefile").write_text(makefile, encoding="utf-8")

replace_once(
    ".github/workflows/pas-platform-ci.yml",
    "      - name: Install GnuCOBOL\n        run: |\n          sudo apt-get update\n          sudo apt-get install -y gnucobol\n          cobc --version\n      - name: Check COBOL goldens\n        run: make parity-golden-check\n",
    "",
)

compat = root / ".github/workflows/compat-gate.yml"
workflow = compat.read_text(encoding="utf-8")
build = workflow.split("  build-test:", 1)[1].split("  api-contract:", 1)[0]
if "            8\n            17\n            21\n" not in build:
    raise SystemExit("Expected JDK matrix anchor missing from compat build-test")
workflow = workflow.replace(
    "            8\n            17\n            21\n",
    "            17\n            21\n",
    1,
)
api_start = workflow.index("  api-contract:")
api_contract = workflow[api_start:]
if api_contract.count('java-version: "8"') != 1 or "run: ./compat/api/run_facade.sh" not in api_contract:
    raise SystemExit("Expected facade/JDK 8 anchors missing from api-contract")
api_contract = api_contract.replace('java-version: "8"', 'java-version: "21"', 1)
api_contract = api_contract.replace(
    "      - name: Compare GET /api/v1/policies responses with recorded snapshots\n        env:\n          COMPAT_AUTH_HEADER: ${{ secrets.COMPAT_AUTH_HEADER }}\n        run: ./compat/api/run_facade.sh",
    "      - name: Compare modern policy API responses with recorded snapshots\n        run: ./compat/api/run_modern.sh",
    1,
)
workflow = workflow[:api_start] + api_contract
compat.write_text(workflow, encoding="utf-8")

replace_once(
    "compat/README.md",
    "| REST API contract | `api/run_facade.sh` → `api/check_api.py` | Changed status codes, removed fields, changed JSON types/values for `GET /api/v1/policies/{n}` and `/coverages` vs `api/snapshots/` (additive fields are fine) |",
    "| REST API contract | `api/run_modern.sh` → `api/check_api.py` | Changed status codes, removed fields, changed JSON types/values for `GET /api/v1/policies/{n}` and `/coverages` vs `api/snapshots/` (additive fields are fine) |",
)
replace_once(
    "compat/README.md",
    "Run locally: `make compat` (needs JDK 8 for `java-facade` and Python 3).",
    "Run locally: `make compat` (needs JDK 21 and Python 3).",
)
replace_once(
    "compat/README.md",
    "Reviewers see the approval in the PR diff. When the API under test needs\n"
    "credentials, set the `COMPAT_AUTH_HEADER` repository secret (`Name: value`).\n"
    "When a replacement service takes over the read API, point `COMPAT_API_MODULE`\n"
    "/ `COMPAT_API_START` at it; the snapshots stay the contract. Re-record snapshots\n"
    "(`compat/api/run_facade.sh --record`) only with an approved contract change.",
    "Reviewers see the approval in the PR diff. The modern policy-service contract is\n"
    "checked against the retained snapshots; update them only with an approved contract\n"
    "change (`compat/api/run_modern.sh --record`).",
)
replace_once(
    "compat/README.md",
    "The Devin \"PAS backward-compatibility gate\" automation also reviews each PR and\n"
    "covers what static checks can't (business-rule parity against COBOL, intent).",
    "The Devin \"PAS backward-compatibility gate\" automation also reviews each PR and\n"
    "covers what static checks cannot, including intent and Java parity against the frozen\n"
    "legacy fixtures.",
)

replace_once(
    ".github/CODEOWNERS",
    "# /cobol/ @approved-mainframe-team\n",
    "",
)
replace_once(
    "README.md",
    "**Maturity Level:** 0 (Legacy)",
    "**Maturity Level:** Java 21 platform with legacy source retirement pending",
)
replace_once(
    "README.md",
    "### Core Platform\n"
    "- **Language:** COBOL 85\n"
    "- **Runtime:** IBM z/OS 2.5, CICS Transaction Server 5.6\n"
    "- **Database:** IBM DB2 v12 for z/OS\n"
    "- **Messaging:** IBM MQ Series 9.2\n"
    "- **Batch:** z/OS JES2, CA-7 job scheduling\n\n"
    "### REST Facade (Added 2022)\n"
    "- **Language:** Java 8\n"
    "- **Framework:** Spring Boot 1.5.22\n"
    "- **Purpose:** Read-only REST API for modern system integration\n"
    "- **Deployment:** Docker container on Linux VM (not on z/OS)\n",
    "### Target platform\n"
    "- **Language/runtime:** Java 21 and Spring Boot 3.5\n"
    "- **Services:** `pas-policy-service` and `pas-batch`; shared modules `pas-common`, `pas-rating`, and test-scope `pas-parity`\n"
    "- **Database/events:** PostgreSQL 16 and Kafka\n"
    "- **Routing:** Per-capability strangler flags; see the cutover and rollback runbooks\n\n"
    "### Legacy platform (until decommission)\n"
    "The historical CICS/COBOL, DB2, MQ, JCL, and Java facade inventory below describes the "
    "pre-migration system. Legacy sources are retired after the approved cutover and hypercare.\n",
)
replace_once(
    "README.md",
    "The legacy implementation remains alongside the new Java platform:\n\n"
    "| Path | Responsibility |\n"
    "|------|----------------|\n"
    "| `cobol/` | Mainframe program sources and copybooks |\n"
    "| `jcl/` | Batch job control language |\n"
    "| `sql/ddl/` | Legacy DB2 schema definitions |\n"
    "| `java-facade/` | Existing Java 8 / Spring Boot 1.5 read-only facade |\n",
    "The legacy implementation has been removed after cutover and hypercare. The "
    "`pas-legacy-final` tag preserves the final source snapshot.\n\n"
    "| Path | Responsibility |\n"
    "|------|----------------|\n"
    "| Legacy sources | Removed in AB-530; see the `pas-legacy-final` tag and decommission runbook |\n"
    "| `sql/ddl/` | Legacy DB2 schema definitions |\n",
)
replace_once(
    "README.md",
    "| `pas-rating` | planned (AB-522) | Rating component shared by the policy service and batch |\n",
    "| `pas-rating` | — | Rating component shared by the policy service and batch |\n"
    "| `parity/` | — | Frozen parity fixtures and Java parity specifications |\n"
    "| `pas-platform/cutover/` | — | Cutover, rollback, smoke, Flyway, and decommission automation |\n",
)
readme_path = root / "README.md"
readme = readme_path.read_text(encoding="utf-8")
if "## Operations runbooks" not in readme:
    anchor = "## CI\n"
    if readme.count(anchor) != 1:
        raise SystemExit("Expected README operations-links anchor missing")
    readme = readme.replace(
        anchor,
        "## Operations runbooks\n\n"
        "See the [cutover](docs/runbooks/cutover.md), [rollback](docs/runbooks/rollback.md), "
        "[operations handover](docs/runbooks/operations-handover.md), and "
        "[decommission](docs/modernization/decommission.md) runbooks.\n\n" + anchor,
        1,
    )
    readme_path.write_text(readme, encoding="utf-8")

test_path = root / "pas-platform/pas-common/src/test/java/com/acme/insurance/pas/copybook/PasCopybooksDriftTest.java"
test = test_path.read_text(encoding="utf-8")
if "Assumptions.assumeTrue" not in test:
    test = test.replace(
        "import org.junit.jupiter.api.Test;\n",
        "import org.junit.jupiter.api.Assumptions;\nimport org.junit.jupiter.api.Test;\n",
        1,
    )
    anchor = "    Path root = repositoryRoot(Path.of(\"\").toAbsolutePath());\n"
    if test.count(anchor) != 1:
        raise SystemExit("Expected copybook drift test anchor missing")
    test = test.replace(
        anchor,
        "    Path root = repositoryRoot(Path.of(\"\").toAbsolutePath());\n"
        "    Assumptions.assumeTrue(\n"
        "        Files.isDirectory(root.resolve(\"cobol/copybooks\")),\n"
        "        \"legacy copybooks decommissioned (AB-530); layouts frozen in PasCopybooks\");\n",
        1,
    )
    test = test.replace(
        "      if (Files.isDirectory(current.resolve(\"cobol/copybooks\"))) {",
        "      if (Files.exists(current.resolve(\".git\")) || Files.isDirectory(current.resolve(\"pas-platform\"))) {",
        1,
    )
test_path.write_text(test, encoding="utf-8")

modernization = root / "docs/modernization/README.md"
with modernization.open("a", encoding="utf-8") as output:
    output.write(
        "\n## Legacy sources removed\n\n"
        "The `cobol/`, `jcl/`, and `java-facade/` trees were removed after cutover and "
        "hypercare under AB-530. The final pre-decommission source snapshot must be tagged "
        "`pas-legacy-final` before running `decommission-legacy-trees.sh`. Compatibility "
        "layouts remain frozen in the allowed-change ledger; see "
        "[the decommission runbook](decommission.md).\n"
    )

parity_readme = root / "parity/README.md"
if parity_readme.read_text(encoding="utf-8").count("# COBOL characterization and parity harness\n") != 1:
    raise SystemExit("Expected parity README title anchor missing")
parity_readme.write_text(
    "# Frozen parity references\n\n"
    "The pre-decommission COBOL characterization, source locks, fixtures, goldens, and "
    "known differences are retained as historical references. The COBOL runner and its "
    "source-dependent harness are retired; do not regenerate those goldens from this tree. "
    "The final source snapshot is available from the `pas-legacy-final` tag.\n\n"
    "The Java parity suite remains active and runs through `make parity`. The policy-service "
    "parity test reads `parity/programs/UNDWRT/spec.json`; keep the specifications, fixture "
    "data, Java adapters, and goldens needed by those tests. See the "
    "[cutover](../docs/runbooks/cutover.md) and "
    "[decommission](../docs/modernization/decommission.md) runbooks.\n",
    encoding="utf-8",
)

compat_readme = root / "compat/README.md"
with compat_readme.open("a", encoding="utf-8") as output:
    output.write(
        "\n## After legacy source decommission\n\n"
        "The final mainframe and facade source snapshot is retained by the `pas-legacy-final` "
        "tag. Layout removals recorded for AB-530 remain in `allowed-changes.json`; current "
        "build and API compatibility checks target the modern policy service.\n"
    )
PY

git -C "$repo" rm -r -q -- cobol jcl java-facade parity/tools/harness.py parity/tools/test_harness.py
if [[ "$commit" == true ]]; then
  git -C "$repo" add -A
  git -C "$repo" commit -m "chore(AB-530): decommission legacy trees"
fi
printf 'Legacy source decommission edits applied to %s\n' "$repo"
