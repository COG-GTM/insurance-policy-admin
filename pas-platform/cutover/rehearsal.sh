#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck disable=SC1091
source "$PLATFORM_DIR/dev/rehearsal-lib.sh"

LOG_DIR="$HOME/ab530"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/cutover-rehearsal.log"
exec > >(tee "$LOG_FILE") 2>&1

validate_rehearsal_environment
configure_rehearsal_connections
export PAS_CUTOVER_ENV=local
export PAS_CUTOVER_STATE_DIR="$HOME/ab530/cutover-state/rehearsal"
export PAS_POLICY_HTTP_PORT="${PAS_POLICY_HTTP_PORT:-18080}"
export PAS_BATCH_HTTP_PORT="${PAS_BATCH_HTTP_PORT:-18081}"
export PAS_ROUTE_POLICY_INQUIRY=new
export PAS_ROUTE_NEW_BUSINESS=legacy
export PAS_ROUTE_UNDERWRITING=legacy
export PAS_ROUTE_RENEWAL=legacy
export PAS_ROUTE_ENDORSEMENT=legacy
export PAS_ROUTE_DOCUMENT_EVENTS=legacy
export PAS_ROUTE_BROKER_PORTAL_READ=legacy
export PAS_ROUTING_PREMIUM_BATCH=legacy
export PAS_ROUTING_DAILY_EXTRACT=legacy
export PAS_ROUTING_MONTHLY_EXPOSURE=legacy
export PAS_MIGRATION_DELTA_SYNC_PAUSED=false
export PAS_MIGRATION_REVERSE_SYNC_ENABLED=false

started="$(date +%s)"
run_id="ab530-$(date +%s)"
printf 'AB-530 cutover rehearsal started at %s\n' "$(date -u +%FT%TZ)"
printf 'Log: %s\n' "$LOG_FILE"

docker compose --profile migration down -v
docker compose --profile migration up -d --wait \
  acmeins-source rollback-target postgres kafka
docker compose build pas-policy-service
docker compose build pas-batch
docker compose --profile migration up -d --wait --no-build pas-policy-service pas-batch

source_psql \
  "UPDATE acmeins.policies SET effective_date = CURRENT_DATE - 358, expiry_date = CURRENT_DATE + 7 WHERE policy_number = 'POL529000001'" \
  >/dev/null
run_cli --pas.batch.cli.job=bulkLoadJob
run_cli --pas.batch.cli.job=deltaSyncJob

"$SCRIPT_DIR/cutover.sh" --execute --run-id "$run_id"
recon_status="$(target_psql "SELECT status FROM pas_batch.recon_run ORDER BY id DESC LIMIT 1")"
if [[ "$recon_status" != CLEAN ]]; then
  printf 'Cutover rehearsal reconciliation is not CLEAN: %s\n' "$recon_status" >&2
  exit 1
fi

export PAS_MIGRATION_REVERSE_SYNC_ENABLED=true
"$SCRIPT_DIR/rollback.sh" --execute --include-reads --run-id "$run_id"
recon_status="$(target_psql "SELECT status FROM pas_batch.recon_run ORDER BY id DESC LIMIT 1")"
if [[ "$recon_status" != CLEAN ]]; then
  printf 'Rollback rehearsal reconciliation is not CLEAN: %s\n' "$recon_status" >&2
  exit 1
fi

reverse_lag="$(
  target_psql \
    "SELECT count(*) FROM pas.change_log c WHERE c.changed_at > COALESCE((SELECT synced_through FROM pas_batch.sync_watermark WHERE table_name = 'CHANGE_LOG' AND direction = 'REVERSE'), TIMESTAMPTZ '1900-01-01 00:00:00+00')"
)"
if [[ "$reverse_lag" != 0 ]]; then
  printf 'Rollback rehearsal reverse-sync lag is %s, expected 0.\n' "$reverse_lag" >&2
  exit 1
fi

jwt="$(mint_rehearsal_jwt ab530-rollback-check)"
assert_legacy_route() {
  local path="$1" method="$2" body="${3:-}"
  local status
  if [[ -n "$body" ]]; then
    status="$(curl -sS -o /dev/null -w '%{http_code}' -X "$method" \
      "http://localhost:${PAS_POLICY_HTTP_PORT}${path}" \
      -H "Authorization: Bearer $jwt" -H 'Content-Type: application/json' --data "$body")"
  else
    status="$(curl -sS -o /dev/null -w '%{http_code}' -X "$method" \
      "http://localhost:${PAS_POLICY_HTTP_PORT}${path}" \
      -H "Authorization: Bearer $jwt")"
  fi
  if [[ "$status" != 503 ]]; then
    printf 'Expected legacy route %s to return 503, received %s.\n' "$path" "$status" >&2
    exit 1
  fi
}
assert_legacy_route /api/v1/policies POST \
  "{\"policyType\":\"AUT\",\"policyholderId\":\"C529000001\",\"effectiveDate\":\"$(date -u -d tomorrow +%F)\",\"agentCode\":\"AGT001\",\"branchCode\":\"B001\",\"coverageLimit\":100000.00,\"deductible\":500.00,\"totalPremium\":1200.00}"
assert_legacy_route /api/v1/policies/POL529000001/underwriting-decisions POST
assert_legacy_route /api/v1/policies/POL529000001/renewals POST \
  '{"termExpiryDate":"2099-12-31"}'
assert_legacy_route /api/v1/policies/POL529000001/endorsements POST \
  "{\"type\":\"LCH\",\"effectiveDate\":\"$(date -u -d tomorrow +%F)\",\"coverageSeq\":1,\"newLimit\":125000.00}"

if [[ "$(target_psql "SELECT has_table_privilege('pas_service', 'pas.policies', 'INSERT')")" != f \
  || "$(target_psql "SELECT has_table_privilege('pas_sync', 'pas.policies', 'INSERT')")" != t \
  || "$(target_psql "SELECT has_table_privilege('pas_service', 'pas.outbox', 'INSERT')")" != t ]]; then
  printf 'Rollback rehearsal grant assertions failed.\n' >&2
  exit 1
fi
if [[ "$(source_psql "SELECT has_table_privilege('acmeins_cics', 'acmeins.policies', 'UPDATE')")" != t ]]; then
  printf 'Rollback rehearsal did not unfreeze legacy writes.\n' >&2
  exit 1
fi

duration=$(( $(date +%s) - started ))
printf 'PASS cutover and rollback rehearsal completed in %s seconds.\n' "$duration"
printf 'Compose stack remains running for the separate rollback-rehearsal check.\n'
