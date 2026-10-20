#!/usr/bin/env bash
# AB-530 decommission dry run: proves scripts/decommission/remove-legacy-trees.sh is safe without
# touching this checkout. It copies the repository (committed state plus local changes) into a
# scratch directory, runs the removal there and then runs the checks that must stay green:
#   compat static checks against the pre-removal commit, route-flag tests, `make compat`
#   (build + tests of every remaining Maven module, i.e. pas-platform `verify`) and `make parity`.
#
#   scripts/decommission/test-remove-legacy-trees.sh
#   DRY_RUN_KEEP=1      keep the scratch copy for inspection
#   DRY_RUN_QUICK=1     skip the Maven verify / parity runs (script and rewiring only)
set -euo pipefail

SRC="$(git rev-parse --show-toplevel)"
WORK="$(mktemp -d)"
SCRATCH="$WORK/repo"
cleanup() { if [ -z "${DRY_RUN_KEEP:-}" ]; then rm -rf "$WORK"; else echo "scratch copy kept: $SCRATCH"; fi; }
trap cleanup EXIT

# Identity for the throwaway commits in the scratch copy only.
export GIT_AUTHOR_NAME="decommission-dry-run" GIT_AUTHOR_EMAIL="decommission-dry-run@localhost"
export GIT_COMMITTER_NAME="$GIT_AUTHOR_NAME" GIT_COMMITTER_EMAIL="$GIT_AUTHOR_EMAIL"

echo "== scratch copy: $SCRATCH"
git clone -q --no-hardlinks "$SRC" "$SCRATCH"
git -C "$SRC" diff HEAD --binary > "$WORK/local.patch"
[ -s "$WORK/local.patch" ] && git -C "$SCRATCH" apply --whitespace=nowarn "$WORK/local.patch"
git -C "$SRC" ls-files --others --exclude-standard -z | (cd "$SRC" && xargs -0 -r cp --parents -t "$SCRATCH")
git -C "$SCRATCH" add -A
git -C "$SCRATCH" commit -q --allow-empty -m "dry run: pre-removal state"
BASE=$(git -C "$SCRATCH" rev-parse HEAD)
cd "$SCRATCH"

echo "== remove-legacy-trees.sh --scratch"
scripts/decommission/remove-legacy-trees.sh --scratch

echo "== assertions"
for d in cobol jcl java-facade; do
  [ ! -e "$d" ] || { echo "FAIL: $d/ still present" >&2; exit 1; }
done
for f in parity/contracts/copybooks/POLICY-RECORD.cpy parity/fixtures/acmeins/schema.sql parity/fixtures/acmeins/data.sql \
         compat/api/snapshots/policies-v1.json parity/programs/prembat/golden.csv; do
  [ -s "$f" ] || { echo "FAIL: $f missing after removal" >&2; exit 1; }
done
if make -n compat parity | grep -E "(cobol|jcl|java-facade)/|run_facade|oracle\.py|cobol_harness"; then
  echo "FAIL: make compat/parity still reference removed trees" >&2; exit 1
fi
if grep -nE "^  (api-contract|cobol-golden|decommission-dry-run):" .github/workflows/*.yml; then
  echo "FAIL: CI still has jobs for removed trees" >&2; exit 1
fi
python3 - <<'PY'
import glob, sys
import yaml  # PyYAML ships with the GitHub runner image and Ubuntu python3
for f in glob.glob(".github/workflows/*.yml"):
    doc = yaml.safe_load(open(f))
    if not doc.get("jobs"):
        sys.exit(f"FAIL: {f} has no jobs after rewiring")
print("workflows parse")
PY
echo "== compat static checks vs the pre-removal commit"
COMPAT_BASE_REF="$BASE" make compat-static
echo "== route flags"
make route-flags

if [ -n "${DRY_RUN_QUICK:-}" ]; then
  echo "DRY RUN (quick) PASS: removal script and rewiring verified; Maven/parity skipped"
  exit 0
fi
echo "== make compat (every remaining Maven module: build + tests)"
COMPAT_BASE_REF="$BASE" make compat
echo "== make parity"
make parity
echo "DRY RUN PASS: legacy trees removed in scratch; pas-platform builds and compat/parity pass"
