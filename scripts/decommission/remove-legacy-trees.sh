#!/usr/bin/env bash
# AB-530: remove the legacy mainframe trees (cobol/, jcl/, java-facade/) from the repository and
# rewire the build so it stays green without them. Step D-10 of docs/modernization/decommission.md.
#
# DO NOT RUN on main before cutover sign-off: `make parity` (COBOL goldens) and `make compat`
# (facade API contract, copybook/JCL layout checks) depend on these trees until then.
# CI proves the removal is safe by running this script in a scratch copy
# (scripts/decommission/test-remove-legacy-trees.sh, `make decommission-dry-run`).
#
#   CUTOVER_SIGNOFF_REF=<change/sign-off reference> scripts/decommission/remove-legacy-trees.sh
#   scripts/decommission/remove-legacy-trees.sh --scratch      (only inside the dry-run clone made by
#                                                               test-remove-legacy-trees.sh)
#
# What it does (one commit):
#   1. keeps what the new platform still reads:
#        cobol/copybooks/*.cpy                    -> parity/contracts/copybooks/ (byte layouts, PasCopybooksDriftTest)
#        java-facade/.../schema.sql, data.sql     -> parity/fixtures/acmeins/ (ACMEINS stand-in seed, contract snapshot data)
#   2. removes cobol/, jcl/, java-facade/ and the tools that only ran against them
#      (parity/tools COBOL harness, parity/extracts/oracle.py, compat/api/run_facade.sh);
#   3. rewrites path references, Makefile targets (compat, parity) and CI jobs (rewire_build.py);
#   4. allow-lists the compat-gate findings the removal causes (ticket AB-530);
#   5. fails if any live reference to a removed tree is left.
set -euo pipefail

scratch=0
[ "${1:-}" = --scratch ] && scratch=1
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
HERE="scripts/decommission"

# --scratch skips the sign-off only in the disposable clone created by test-remove-legacy-trees.sh,
# which exports DECOMMISSION_SCRATCH_ROOT as that clone's path.
if [ "$scratch" = 1 ] && [ "${DECOMMISSION_SCRATCH_ROOT:-}" != "$ROOT" ]; then
  echo "refusing: --scratch only runs inside the clone made by scripts/decommission/test-remove-legacy-trees.sh" >&2
  exit 2
fi
if [ "$scratch" = 0 ] && [ -z "${CUTOVER_SIGNOFF_REF:-}" ]; then
  echo "refusing: set CUTOVER_SIGNOFF_REF to the cutover sign-off / change reference (decommission.md D-10)" >&2
  exit 2
fi
if [ -n "$(git status --porcelain)" ]; then
  echo "refusing: working tree is not clean" >&2
  exit 2
fi
for d in cobol jcl java-facade; do
  [ -d "$d" ] || { echo "refusing: $d/ is already gone" >&2; exit 2; }
done
BASE=$(git rev-parse HEAD)

# 1. Keep what the new platform still reads.
mkdir -p parity/contracts parity/fixtures/acmeins
git mv cobol/copybooks parity/contracts/copybooks
git mv java-facade/src/main/resources/schema.sql parity/fixtures/acmeins/schema.sql
git mv java-facade/src/main/resources/data.sql parity/fixtures/acmeins/data.sql

# 2. Remove the legacy trees and the tools that only ran against them.
git rm -r -q cobol jcl java-facade parity/tools parity/extracts/oracle.py compat/api/run_facade.sh

# 3. Path references outside docs, then Makefile/CI.
git grep -lz -E 'java-facade/src/main/resources/(schema|data)\.sql|cobol/copybooks' -- \
    ':!docs' ':!*.md' ":!$HERE" \
  | xargs -0 -r sed -i -E \
      -e 's#java-facade/src/main/resources/(schema|data)\.sql#parity/fixtures/acmeins/\1.sql#g' \
      -e 's#cobol/copybooks#parity/contracts/copybooks#g'
# Longer paths can push Java comments past the formatter's line length: reformat (google-java-format).
(cd pas-platform && ./mvnw -B -ntp -q spotless:apply)
python3 "$HERE/rewire_build.py" build

# 4. Allow-list the compat findings caused by the removal (and nothing else).
python3 "$HERE/rewire_build.py" allow "$BASE"

# 5. No live reference to a removed tree may remain. parity/programs/*/spec.json record which COBOL
#    source each golden was captured from (history; the source stays in git history).
live=$(git grep -n -E "(^|[\"'( =])(\.\./)*(\./)?(cobol|jcl|java-facade)/" -- \
    Makefile .github compat pas-platform parity scripts \
    ':!*.md' ':!parity/programs' ":!$HERE" ':!**/*.java' || true)
live_java=$(git grep -n -E "\"(\.\./)*(cobol|jcl|java-facade)/" -- 'pas-platform/**/*.java' || true)
if [ -n "$live$live_java" ]; then
  printf 'live references to removed trees remain:\n%s\n%s\n' "$live" "$live_java" >&2
  exit 1
fi

git add -A
msg="AB-530: remove cobol/, jcl/ and java-facade/ after cutover sign-off (${CUTOVER_SIGNOFF_REF:-scratch dry run})"
git commit -q -m "$msg"
echo "removed legacy trees in one commit: $(git rev-parse --short HEAD) (base $BASE)"
