#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"
export PAS_CUTOVER_RUN_KIND=cutover

capabilities=(
  policy-inquiry new-business underwriting renewal endorsement premium-batch
  daily-extract monthly-exposure document-events broker-portal-read
)

c01_prerequisites() {
  local signoff="${PAS_CUTOVER_SIGNOFF_FILE:-$SCRIPT_DIR/signoff.example.json}"
  python3 - "$REPO_ROOT/pas-platform/routing/route-flags.json" "${capabilities[@]}" <<'PY'
import json
import sys

path = sys.argv[1]
required = sys.argv[2:]
with open(path, encoding="utf-8") as source:
    document = json.load(source)
actual = set(document.get("capabilities", {}))
missing = sorted(set(required) - actual)
extra = sorted(actual - set(required))
if missing or extra:
    raise SystemExit(f"route-flags.json capability mismatch; missing={missing}, extra={extra}")
print(f"PASS route-flags.json defines all {len(required)} ADR capabilities")
PY
  if [[ "$PAS_CUTOVER_ENV" == "local" ]]; then
    log 'WARN local rehearsal: sign-offs are not required'
    return
  fi
  if [[ ! -f "$signoff" || "$signoff" == "$SCRIPT_DIR/signoff.example.json" ]]; then
    printf 'Refusing cutover: provide a completed PAS_CUTOVER_SIGNOFF_FILE; signoff.example.json is not accepted.\n' >&2
    return 1
  fi
  python3 - "$signoff" "${capabilities[@]}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    document = json.load(source)
required = sys.argv[2:]
if not document.get("arbTicket") or document["arbTicket"] == "ARB-TBD":
    raise SystemExit("Sign-off file requires an approved arbTicket")
if not document.get("parallelRunCycleId") or document["parallelRunCycleId"] == "TBD":
    raise SystemExit("Sign-off file requires parallelRunCycleId")
capabilities = document.get("capabilities", {})
for key in required:
    checks = capabilities.get(key, {})
    if checks.get("signoff") is not True or checks.get("d9") is not True:
        raise SystemExit(f"Sign-off and D9 checks must both be true for {key}")
print("PASS sign-off file has ARB, parallel-run cycle, and all capability checks")
PY
}

c02_flyway() {
  "$SCRIPT_DIR/flyway-check.sh"
}

c03_parallel_run() {
  local cycle_id="${PAS_CUTOVER_PARALLEL_RUN_CYCLE_ID:-}"
  if [[ -z "$cycle_id" && -n "${PAS_CUTOVER_SIGNOFF_FILE:-}" && -f "$PAS_CUTOVER_SIGNOFF_FILE" ]]; then
    cycle_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("parallelRunCycleId", ""))' "$PAS_CUTOVER_SIGNOFF_FILE")"
    [[ "$cycle_id" == "TBD" ]] && cycle_id=""
  fi
  if [[ -z "$cycle_id" ]]; then
    cycle_id="$(cutover_sql "SELECT cycle_id FROM pas_batch.parallel_run_cycle ORDER BY created_at DESC LIMIT 1")"
  fi
  if [[ -z "$cycle_id" ]]; then
    if [[ "$PAS_CUTOVER_ENV" == "local" ]]; then
      log 'WARN no parallel-run cycle exists in local rehearsal; check skipped'
      return
    fi
    printf 'No parallel-run cycle was found; set PAS_CUTOVER_PARALLEL_RUN_CYCLE_ID.\n' >&2
    return 1
  fi
  local untriaged status
  status="$(cutover_sql "SELECT status FROM pas_batch.parallel_run_cycle WHERE cycle_id = '$cycle_id'")"
  [[ -n "$status" ]] || { printf 'Parallel-run cycle %s was not found.\n' "$cycle_id" >&2; return 1; }
  if [[ "$status" != "CLEAN" ]]; then
    printf 'Parallel-run cycle %s has status %s; expected CLEAN.\n' "$cycle_id" "$status" >&2
    return 1
  fi
  untriaged="$(cutover_sql "SELECT count(*) FROM pas_batch.parallel_run_difference WHERE cycle_id = '$cycle_id' AND present AND classification IN ('UNTRIAGED', 'BUG')")"
  if [[ "$untriaged" != "0" ]]; then
    printf 'Parallel-run cycle %s has %s untriaged/BUG differences.\n' "$cycle_id" "$untriaged" >&2
    return 1
  fi
  log "PASS parallel-run cycle $cycle_id has no untriaged or BUG differences (status=$status)"
}

c04_freeze_legacy() {
  local run_id="${PAS_CUTOVER_RUN_ID?}"
  cutover_legacy_freeze
  date -u +%Y-%m-%dT%H:%M:%SZ >"$PAS_CUTOVER_STATE_DIR/freeze-${run_id}.timestamp"
  log 'Legacy write freeze confirmed; local adapter uses a simulated CICS database role'
}

c05_drain_delta() {
  local freeze_time pass lag max_passes
  local coverage_start_cursor previous_cursor coverage_state coverage_cursor sweep_after_freeze
  local coverage_wrapped=false
  local coverage_pass_complete=false
  freeze_time="$(<"$PAS_CUTOVER_STATE_DIR/freeze-${PAS_CUTOVER_RUN_ID}.timestamp")"
  max_passes="${PAS_CUTOVER_DRAIN_MAX_PASSES:-10000}"
  if [[ ! "$max_passes" =~ ^[1-9][0-9]*$ ]]; then
    printf 'PAS_CUTOVER_DRAIN_MAX_PASSES must be a positive integer.\n' >&2
    return 2
  fi
  coverage_start_cursor="$(cutover_sql "SELECT COALESCE(coverage_cursor, '') FROM pas_batch.sync_watermark WHERE table_name = 'COVERAGE_SWEEP' AND direction = 'FORWARD'")"
  previous_cursor="$coverage_start_cursor"
  lag=1
  sweep_after_freeze=false
  for ((pass = 1; pass <= max_passes; pass++)); do
    log "Forward delta drain pass $pass"
    cutover_batch --pas.batch.cli.job=deltaSyncJob
    coverage_state="$(
      cutover_sql "SELECT COALESCE(coverage_cursor, '') || '|' || CASE WHEN synced_through >= '$freeze_time'::timestamptz THEN 'true' ELSE 'false' END FROM pas_batch.sync_watermark WHERE table_name = 'COVERAGE_SWEEP' AND direction = 'FORWARD'"
    )"
    IFS='|' read -r coverage_cursor sweep_after_freeze <<<"$coverage_state"
    if [[ -n "$coverage_cursor" ]]; then
      if [[ -n "$previous_cursor" ]] \
        && { [[ "$coverage_cursor" < "$previous_cursor" ]] || [[ "$coverage_cursor" == "$previous_cursor" ]]; }; then
        coverage_wrapped=true
      fi
      previous_cursor="$coverage_cursor"
    fi
    if [[ "$coverage_wrapped" == true ]] \
      && { [[ -z "$coverage_start_cursor" ]] || [[ "$coverage_cursor" > "$coverage_start_cursor" ]] \
        || [[ "$coverage_cursor" == "$coverage_start_cursor" ]]; }; then
      coverage_pass_complete=true
    fi
    lag="$(cutover_sql "SELECT count(*) FROM (VALUES ('POLICY_HOLDERS'), ('POLICIES'), ('PREMIUMS'), ('ENDORSEMENTS'), ('UNDERWRITING_DECISIONS'), ('COVERAGE_SWEEP')) AS expected(table_name) LEFT JOIN pas_batch.sync_watermark watermark ON watermark.table_name = expected.table_name AND watermark.direction = 'FORWARD' AND watermark.synced_through >= '$freeze_time'::timestamptz WHERE watermark.table_name IS NULL")"
    if [[ "$lag" == "0" && "$coverage_pass_complete" == true && "$sweep_after_freeze" == true ]]; then
      break
    fi
  done
  if [[ "$lag" != "0" || "$coverage_pass_complete" != true || "$sweep_after_freeze" != true ]]; then
    printf 'Forward delta drain reached the %s-pass limit (watermark lag=%s, full coverage pass=%s, sweep after freeze=%s).\n' \
      "$max_passes" "$lag" "$coverage_pass_complete" "$sweep_after_freeze" >&2
    return 1
  fi
  cutover_migration_config PAS_MIGRATION_DELTA_SYNC_PAUSED=true
  log 'PASS forward delta watermark is at or beyond the legacy freeze timestamp for every forward table'
}

c06_reconcile() {
  cutover_batch --pas.batch.cli.job=reconciliationJob
  local result
  result="$(cutover_sql "SELECT id || '|' || status FROM pas_batch.recon_run ORDER BY id DESC LIMIT 1")"
  if [[ "${result#*|}" != "CLEAN" ]]; then
    printf 'Final reconciliation is not CLEAN; latest run %s; report directory %s.\n' \
      "${result%%|*}" '/var/lib/pas-batch/reports/recon' >&2
    return 1
  fi
  log "PASS reconciliation run ${result%%|*} is CLEAN; reports: /var/lib/pas-batch/reports/recon"
}

c07_grants() {
  cutover_sql_file "$PLATFORM_DIR/db/grants/roles.sql"
  cutover_sql_file "$PLATFORM_DIR/db/grants/post-cutover.sql"
  local service_write sync_write
  service_write="$(cutover_sql "SELECT has_table_privilege('pas_service', 'pas.policies', 'INSERT')")"
  sync_write="$(cutover_sql "SELECT has_table_privilege('pas_sync', 'pas.policies', 'INSERT')")"
  if [[ "$service_write" != "t" || "$sync_write" != "f" ]]; then
    printf 'Cutover grant check failed: pas_service INSERT=%s, pas_sync INSERT=%s.\n' \
      "$service_write" "$sync_write" >&2
    return 1
  fi
  log 'PASS pas_service has business-table writes and pas_sync does not'
}

c08_read_routes() {
  cutover_apply_routes PAS_ROUTE_POLICY_INQUIRY=new
  assert_route policy policy-inquiry new
  cutover_apply_routes PAS_ROUTE_BROKER_PORTAL_READ=new
  assert_route policy broker-portal-read new
  cutover_apply_routes PAS_ROUTING_DAILY_EXTRACT=new
  assert_route batch daily-extract new
  cutover_apply_routes PAS_ROUTING_MONTHLY_EXPOSURE=new
  assert_route batch monthly-exposure new
}

c09_write_routes() {
  cutover_apply_routes \
    PAS_ROUTE_NEW_BUSINESS=new \
    PAS_ROUTE_UNDERWRITING=new \
    PAS_ROUTE_RENEWAL=new \
    PAS_ROUTE_ENDORSEMENT=new \
    PAS_ROUTE_DOCUMENT_EVENTS=new \
    PAS_ROUTING_PREMIUM_BATCH=new
  assert_route policy new-business new
  assert_route policy underwriting new
  assert_route policy renewal new
  assert_route policy endorsement new
  assert_route policy document-events new
  assert_route batch premium-batch new
}

c10_reverse_sync() {
  cutover_migration_config PAS_MIGRATION_REVERSE_SYNC_ENABLED=true
  cutover_batch --pas.batch.cli.job=reverseSyncJob --pas.batch.cli.drain=false
}

c11_smoke() {
  local args=()
  if [[ "$PAS_CUTOVER_ENV" == "local" ]]; then
    args+=(--extract-cmd local)
  fi
  "$SCRIPT_DIR/smoke-test.sh" "${args[@]}"
}

c12_route_proposal() {
  local source="$REPO_ROOT/pas-platform/routing/route-flags.json"
  local proposal="$PAS_CUTOVER_STATE_DIR/route-flags.cutover.json"
  python3 - "$source" "$proposal" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    document = json.load(source)
for capability in document["capabilities"].values():
    capability["route"] = "new"
with open(sys.argv[2], "w", encoding="utf-8") as output:
    json.dump(document, output, indent=2)
    output.write("\n")
PY
  log "Route proposal written to $proposal; update the gateway audit file by PR"
}

main() {
  parse_common_args "$@"
  assert_execute_environment
  if [[ "$PAS_CUTOVER_EXECUTE" == "true" ]]; then
    log "Executing cutover run id $PAS_CUTOVER_RUN_ID"
  else
    log 'Dry run only; use --execute to make changes'
  fi
  step c01 'prerequisites and capability flags' c01_prerequisites 'validate sign-offs, D9 evidence, and route flags'
  step c02 'Flyway history check' c02_flyway 'compare repository migrations with pas and pas_batch history'
  step c03 'clean parallel-run cycle' c03_parallel_run 'check latest parallel-run differences'
  step c04 'legacy write freeze' c04_freeze_legacy 'freeze CICS writes and CA-7 jobs'
  step c05 'drain delta sync' c05_drain_delta 'run deltaSyncJob until all forward watermarks reach freeze time'
  step c06 'final reconciliation' c06_reconcile 'run reconciliationJob and require CLEAN'
  step c07 'cutover grants' c07_grants 'apply roles.sql and post-cutover.sql; verify write grants'
  step c08 'route reads and extracts' c08_read_routes 'apply policy-inquiry, broker-portal-read, daily-extract, monthly-exposure in order'
  step c09 'route writes together' c09_write_routes 'apply new-business, underwriting, renewal, endorsement, document-events, premium-batch together'
  step c10 'start reverse sync' c10_reverse_sync 'enable reverse sync and run one non-draining reverseSyncJob pass'
  step c11 'smoke tests' c11_smoke 'run authenticated policy, event, and optional extract dry-run checks'
  step c12 'route-flags proposal' c12_route_proposal 'write route-flags.cutover.json'
  log 'GO/NO-GO: review all PASS evidence before authorizing production traffic; begin the two-week hypercare period after go-live.'
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
