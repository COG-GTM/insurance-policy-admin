#!/usr/bin/env bash
set -Eeuo pipefail

export PAS_CUTOVER_RUN_KIND=rollback
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"
rollback_started="$(date +%s)"

r01_writes_legacy() {
  cutover_apply_routes \
    PAS_ROUTE_NEW_BUSINESS=legacy \
    PAS_ROUTE_UNDERWRITING=legacy \
    PAS_ROUTE_RENEWAL=legacy \
    PAS_ROUTE_ENDORSEMENT=legacy \
    PAS_ROUTE_DOCUMENT_EVENTS=legacy \
    PAS_ROUTING_PREMIUM_BATCH=legacy
  assert_route policy new-business legacy
  assert_route policy underwriting legacy
  assert_route policy renewal legacy
  assert_route policy endorsement legacy
  assert_route policy document-events legacy
  assert_route batch premium-batch legacy
}

r02_drain_reverse_sync() {
  local run_id="${PAS_CUTOVER_RUN_ID?}"
  cutover_batch --pas.batch.cli.job=reverseSyncJob --pas.batch.cli.drain=true
  local lag
  lag="$(cutover_sql "SELECT count(*) FROM pas.change_log c WHERE c.changed_at > COALESCE((SELECT synced_through FROM pas_batch.sync_watermark WHERE table_name = 'CHANGE_LOG' AND direction = 'REVERSE'), TIMESTAMPTZ '1900-01-01 00:00:00+00')")"
  if [[ "$lag" != "0" ]]; then
    printf 'Reverse-sync drain failed; %s change(s) remain beyond the watermark.\n' "$lag" >&2
    return 1
  fi
  printf '%s\n' "$lag" >"$PAS_CUTOVER_STATE_DIR/reverse-lag-${run_id}.txt"
  log 'PASS reverse-sync drain completed with lag 0'
}

r03_reconcile() {
  cutover_batch --pas.batch.cli.job=reconciliationJob
  local result
  result="$(cutover_sql "SELECT id || '|' || status FROM pas_batch.recon_run ORDER BY id DESC LIMIT 1")"
  if [[ "${result#*|}" != CLEAN ]]; then
    printf 'Rollback reconciliation is not CLEAN; latest run %s.\n' "${result%%|*}" >&2
    return 1
  fi
  printf '%s\n' "$result" >"$PAS_CUTOVER_STATE_DIR/rollback-reconciliation-${PAS_CUTOVER_RUN_ID}.txt"
  log "PASS rollback reconciliation run ${result%%|*} is CLEAN"
}

r04_restore_grants() {
  cutover_sql_file "$PLATFORM_DIR/db/grants/roles.sql"
  cutover_sql_file "$PLATFORM_DIR/db/grants/rollback-revoke.sql"
  cutover_sql_file "$PLATFORM_DIR/db/grants/pre-cutover.sql"
  local service_write batch_write sync_write outbox_write
  service_write="$(cutover_sql "SELECT has_table_privilege('pas_service', 'pas.policies', 'INSERT')")"
  batch_write="$(cutover_sql "SELECT has_table_privilege('pas_batch', 'pas.policies', 'INSERT')")"
  sync_write="$(cutover_sql "SELECT has_table_privilege('pas_sync', 'pas.policies', 'INSERT')")"
  outbox_write="$(cutover_sql "SELECT has_table_privilege('pas_service', 'pas.outbox', 'INSERT')")"
  if [[ "$service_write" != f || "$batch_write" != f || "$sync_write" != t || "$outbox_write" != t ]]; then
    printf 'Rollback grant check failed: pas_service INSERT=%s, pas_batch INSERT=%s, pas_sync INSERT=%s, outbox INSERT=%s.\n' \
      "$service_write" "$batch_write" "$sync_write" "$outbox_write" >&2
    return 1
  fi
  log 'PASS pas_service business writes revoked, pas_sync writes restored, outbox writes preserved'
}

r05_read_routes_legacy() {
  if [[ "$PAS_CUTOVER_INCLUDE_READS" != true ]]; then
    log 'WARN read routes left on the modern service; rerun with --include-reads to roll them back'
    return
  fi
  cutover_apply_routes PAS_ROUTE_BROKER_PORTAL_READ=legacy
  assert_route policy broker-portal-read legacy
  cutover_apply_routes PAS_ROUTE_POLICY_INQUIRY=legacy
  assert_route policy policy-inquiry legacy
  cutover_apply_routes PAS_ROUTING_DAILY_EXTRACT=legacy
  assert_route batch daily-extract legacy
  cutover_apply_routes PAS_ROUTING_MONTHLY_EXPOSURE=legacy
  assert_route batch monthly-exposure legacy
}

r06_resume_delta() {
  cutover_migration_config \
    PAS_MIGRATION_REVERSE_SYNC_ENABLED=false \
    PAS_MIGRATION_DELTA_SYNC_PAUSED=false
}

r07_unfreeze_legacy() {
  cutover_legacy_unfreeze
  log 'PASS legacy CICS write privilege restored'
}

r08_report() {
  local ended elapsed decision_time
  ended="$(date +%s)"
  elapsed=$((ended - rollback_started))
  if [[ -n "$PAS_CUTOVER_DECISION_TIME" ]]; then
    decision_time="$(date -d "$PAS_CUTOVER_DECISION_TIME" +%s)"
    elapsed=$((ended - decision_time))
  fi
  if ((elapsed < 0)); then
    printf 'Decision time is later than the rollback completion time.\n' >&2
    return 1
  fi
  if [[ ! -f "$PAS_CUTOVER_STATE_DIR/reverse-lag-${PAS_CUTOVER_RUN_ID}.txt" ]] \
    || [[ "$(<"$PAS_CUTOVER_STATE_DIR/reverse-lag-${PAS_CUTOVER_RUN_ID}.txt")" != 0 ]]; then
    printf 'RPO verdict: FAIL (reverse-sync lag evidence is missing or non-zero).\n' >&2
    return 1
  fi
  if [[ "$(<"$PAS_CUTOVER_STATE_DIR/rollback-reconciliation-${PAS_CUTOVER_RUN_ID}.txt")" != *'|CLEAN' ]]; then
    printf 'RPO verdict: FAIL (clean reconciliation evidence is missing).\n' >&2
    return 1
  fi
  log 'RPO verdict: PASS (reverse-sync lag 0 and clean reconciliation)'
  if ((elapsed > 14400)); then
    printf 'RTO verdict: FAIL (%s seconds exceeds 14400 seconds).\n' "$elapsed" >&2
    return 1
  fi
  log "RTO verdict: PASS (${elapsed}s, threshold 14400s)"
  log 'Rollback complete; confirm DB2 is the system of record and preserve migration watermarks.'
}

main() {
  parse_common_args "$@"
  assert_execute_environment
  rollback_started="$(date +%s)"
  if [[ "$PAS_CUTOVER_EXECUTE" == true ]]; then
    log "Executing rollback run id $PAS_CUTOVER_RUN_ID"
  else
    log 'Dry run only; use --execute to make changes'
  fi
  step r01 'route writes back to legacy' r01_writes_legacy 'apply legacy to all write and document-event routes together'
  step r02 'drain reverse sync and confirm lag zero' r02_drain_reverse_sync 'run reverseSyncJob in drain mode and verify watermark'
  step r03 'clean reconciliation' r03_reconcile 'run reconciliationJob and require CLEAN'
  step r04 'restore pre-cutover grants' r04_restore_grants 'apply roles, rollback-revoke.sql, then pre-cutover.sql'
  step r05 'optionally route reads back' r05_read_routes_legacy 'apply broker read before policy inquiry, then batch reads'
  step r06 'disable reverse sync and resume delta' r06_resume_delta 'disable reverse sync and resume forward delta'
  step r07 'unfreeze legacy' r07_unfreeze_legacy 'restore legacy write privileges'
  step r08 'report RPO/RTO' r08_report 'require RPO 0 and RTO <= 14400 seconds'
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
