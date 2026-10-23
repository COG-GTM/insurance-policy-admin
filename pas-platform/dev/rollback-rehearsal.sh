#!/usr/bin/env bash
set -euo pipefail

PLATFORM="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=pas-platform/dev/rehearsal-lib.sh
source "$PLATFORM/dev/rehearsal-lib.sh"
LOG_DIR="$HOME/ab530"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/rollback-rehearsal.log"
exec > >(tee "$LOG_FILE") 2>&1

load_rehearsal_environment
cd "$PLATFORM"
start_time="$(date +%s)"
printf 'AB-529 rollback rehearsal started at %s\n' "$(date -u +%FT%TZ)"
printf 'Log: %s\n' "$LOG_FILE"

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

policy_effective_date="$(date -u -d 'tomorrow' +%F)"
endorsement_effective_date="$(date -u -d 'tomorrow' +%F)"
endorsement_new_limit="$(
  target_psql \
    "SELECT coverage_limit + 1000 FROM pas.coverages WHERE policy_number = 'POL529000001' AND sequence_num = 1"
)"
renewal_expiry_date="$(
  target_psql "SELECT expiry_date FROM pas.policies WHERE policy_number = 'POL529000001'"
)"

jwt="$(mint_rehearsal_jwt ab529-rollback-rehearsal)"
policy_number="$(api_create_policy "$jwt" "$policy_effective_date")"
printf 'Authenticated API write created policy %s\n' "$policy_number"

api_endorse_policy "$jwt" POL529000001 "$endorsement_effective_date" "$endorsement_new_limit" \
  >/dev/null
renewal_already_recorded="$(
  target_psql \
    "SELECT EXISTS (SELECT 1 FROM pas.policy_renewals WHERE policy_number = 'POL529000001' AND prior_expiry_date = '$renewal_expiry_date')"
)"
if [[ "$renewal_already_recorded" == "t" ]]; then
  printf 'Renewal for term ending %s was already recorded.\n' "$renewal_expiry_date"
else
  api_renew_policy "$jwt" POL529000001 "$renewal_expiry_date" >/dev/null
fi
printf 'Authenticated endorsement and renewal API writes completed.\n'

export PAS_ROUTE_NEW_BUSINESS=legacy
export PAS_ROUTE_RENEWAL=legacy
export PAS_ROUTE_ENDORSEMENT=legacy
docker compose --profile migration up -d --wait --no-deps --force-recreate pas-policy-service
run_cli --pas.batch.cli.job=reverseSyncJob --pas.batch.cli.drain=true
run_cli --pas.batch.cli.job=reconciliationJob

recon_status="$(target_psql "SELECT status FROM pas_batch.recon_run ORDER BY id DESC LIMIT 1")"
if [[ "$recon_status" != "CLEAN" ]]; then
  printf 'Reconciliation did not finish cleanly: %s\n' "$recon_status" >&2
  exit 1
fi

export PAS_MIGRATION_ENABLED=false
export PAS_ROUTE_NEW_BUSINESS=legacy
export PAS_ROUTE_RENEWAL=legacy
export PAS_ROUTE_ENDORSEMENT=legacy
docker compose --profile migration up -d --wait --force-recreate --no-deps pas-policy-service pas-batch
assert_legacy_route() {
  local response
  response="$(curl -sS -o /dev/null -w '%{http_code}' "$@")"
  if [[ "$response" != "503" ]]; then
    printf 'Expected legacy route response 503, got %s.\n' "$response" >&2
    exit 1
  fi
}
assert_legacy_route -X POST "http://localhost:${PAS_POLICY_HTTP_PORT}/api/v1/policies" \
  -H "Authorization: Bearer $jwt" -H 'Content-Type: application/json' \
  --data "{\"policyType\":\"AUT\",\"policyholderId\":\"C529000001\",\"effectiveDate\":\"$policy_effective_date\",\"agentCode\":\"AGT001\",\"branchCode\":\"B001\",\"coverageLimit\":100000.00,\"deductible\":500.00,\"totalPremium\":1200.00}"
assert_legacy_route -X POST \
  "http://localhost:${PAS_POLICY_HTTP_PORT}/api/v1/policies/$policy_number/endorsements" \
  -H "Authorization: Bearer $jwt" -H 'Content-Type: application/json' \
  --data "{\"type\":\"LCH\",\"effectiveDate\":\"$policy_effective_date\",\"coverageSeq\":1,\"newLimit\":125000.00}"
assert_legacy_route -X POST \
  "http://localhost:${PAS_POLICY_HTTP_PORT}/api/v1/policies/POL529000001/renewals" \
  -H "Authorization: Bearer $jwt" -H 'Content-Type: application/json' \
  --data "{\"termExpiryDate\":\"$renewal_expiry_date\"}"
set +e
docker compose --profile migration run --rm --no-deps \
  -e PAS_MIGRATION_ENABLED=false pas-batch \
  --pas.batch.cli.job=reverseSyncJob \
  --spring.main.web-application-type=none
disabled_exit=$?
set -e
if [[ "$disabled_exit" -ne 2 ]]; then
  printf 'Expected migration job absence after feature rollback (exit 2), got %s\n' \
    "$disabled_exit" >&2
  exit 1
fi
printf 'Feature rollback verified: migration jobs are absent when disabled.\n'

elapsed_seconds="$(( $(date +%s) - start_time ))"
policy_lag="$(
  target_psql \
    "SELECT count(*) FROM pas.change_log c WHERE c.changed_at > COALESCE((SELECT synced_through FROM pas_batch.sync_watermark WHERE table_name = 'CHANGE_LOG' AND direction = 'REVERSE'), TIMESTAMPTZ '1900-01-01 00:00:00+00')"
)"
if [[ "$policy_lag" != "0" ]]; then
  printf 'Reverse-sync lag remains for %s policies.\n' "$policy_lag" >&2
  exit 1
fi
if [[ "$elapsed_seconds" -gt 14400 ]]; then
  printf 'RTO verdict: FAIL (%s seconds exceeds 14400 seconds).\n' "$elapsed_seconds" >&2
  exit 1
fi
printf 'RPO verdict: PASS (reverse-sync lag 0 and clean reconciliation).\n'
printf 'RTO rehearsal duration: %s seconds (limit 14400); verdict: PASS.\n' "$elapsed_seconds"
printf 'AB-529 rollback rehearsal completed; log saved at %s\n' "$LOG_FILE"
