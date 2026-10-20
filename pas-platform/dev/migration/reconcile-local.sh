#!/usr/bin/env bash
# AB-529 `make reconcile`: end-to-end local run against seeded data.
#   Compose PostgreSQL + pas-policy-service (Flyway pas V1..V6) -> seed the ACMEINS stand-in
#   (schema acmeins_src) -> pas-batch CLI: bulk-load, delta-sync, reconcile -> print the report.
# Exit 0 = PASS, 3 = unexplained differences. Local stand-in only: no real DB2 is involved.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

ensure_env
stack_up
seed_source
set +e
migration_cli bulk-load,delta-sync,reconcile
rc=$?
set -e
latest="$OUT_DIR/reports/reconciliation/latest.md"
if [ -f "$latest" ]; then
  cat "$latest"
  echo
  echo "JSON: $OUT_DIR/reports/reconciliation/latest.json"
fi
echo "CLI log: $OUT_DIR/cli-bulk-load_delta-sync_reconcile.log (exit $rc)"
exit "$rc"
