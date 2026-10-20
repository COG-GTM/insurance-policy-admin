#!/usr/bin/env bash
# AB-529 rollback rehearsal — LOCAL REHEARSAL ONLY (Compose + PostgreSQL stand-in for DB2).
# The production rehearsal (real gateway, real DB2/CDC, agreed window) is Pending: Mainframe/Platform/Ops.
#
# Steps (docs/modernization/data-migration.md#rollback):
#   1. route flags -> legacy (local file standing in for the gateway route-flag config)
#   2. pause the DB2 -> pas sync (pas.migration_sync_control)
#   3. DB2 takes writes again; prove the paused sync applies nothing
#   4. reverse-delta check: pas holds no rows DB2 lacks and nothing pas-side changed after the flip
#   5. time against the window (ROLLBACK_WINDOW_MINUTES, default 60 — Pending: Ops to agree)
#   6. re-entry: resume sync, delta catches up, reconciliation PASS
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

WINDOW_MIN="${ROLLBACK_WINDOW_MINUTES:-60}"
RUN_DIR="$OUT_DIR/rollback-rehearsal"
mkdir -p "$RUN_DIR"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
RESULT="$RUN_DIR/result-$STAMP.md"
FLAGS="$RUN_DIR/route-flags.json"
declare -a STEPS=()
fail=0
step() { STEPS+=("| $1 | $2 | $3 |"); [ "$2" = PASS ] || fail=1; echo "[$2] $1 — $3"; }

ensure_env
stack_up
seed_source
migration_cli bulk-load,delta-sync,reconcile || { echo "baseline reconcile failed; run make reconcile"; exit 1; }
step "0. baseline: bulk load + delta + reconcile" PASS "pas.* matches the DB2 stand-in"

caps='inquiry new-business underwriting renewal endorsement premium-batch claims-extract actuarial-exposure document-events broker-read'
write_flags() { printf '{\n'; for c in $caps; do printf '  "%s": "%s",\n' "$c" "$1"; done; printf '  "updatedAt": "%s"\n}\n' "$(date -u +%FT%TZ)"; }
write_flags shadow > "$FLAGS"

T0=$(date +%s)
FLIP_TS=$(date -u '+%F %T')
write_flags legacy > "$FLAGS"
if grep -q '"new"\|"shadow"' "$FLAGS"; then step "1. route flags -> legacy" FAIL "$FLAGS"; else step "1. route flags -> legacy" PASS "all capabilities legacy (local file; gateway config Pending: Platform)"; fi

migration_cli pause-sync
paused=$(psql_pas -tAc "SELECT paused FROM pas.migration_sync_control WHERE sync_name = 'DB2_TO_PAS'")
[ "$paused" = t ] && step "2. pause DB2 -> pas sync" PASS "migration_sync_control.paused = true" || step "2. pause DB2 -> pas sync" FAIL "paused=$paused"

before=$(psql_pas -tAc "SELECT COALESCE(SUM(rows_applied),0) || '/' || COALESCE(MAX(watermark_ts)::text,'') FROM pas.migration_watermarks")
psql_pas -c "UPDATE ${SOURCE_SCHEMA}.policies SET updated_by = 'ROLLBACK', last_updated = LOCALTIMESTAMP WHERE policy_number = 'POL-00000001'"
migration_cli delta-sync
after=$(psql_pas -tAc "SELECT COALESCE(SUM(rows_applied),0) || '/' || COALESCE(MAX(watermark_ts)::text,'') FROM pas.migration_watermarks")
[ "$before" = "$after" ] && step "3. DB2 takes writes; paused sync applies nothing" PASS "watermarks unchanged ($after)" || step "3. DB2 takes writes; paused sync applies nothing" FAIL "$before -> $after"

# Reverse delta: every migrated table — target-only keys, plus rows whose canonical content differs
# from DB2 but did not come from a DB2-side change made after the flip (DB2 ahead is fine).
reverse_sql() {
  local t="$1" k="$2" ts="${3:-}"
  local on; on=$(echo "$k" | sed -E 's/([a-z_]+)/p.\1 = s.\1/g; s/,/ AND/g')
  echo "(SELECT COUNT(*) FROM pas.$t p WHERE NOT EXISTS (SELECT 1 FROM ${SOURCE_SCHEMA}.$t s WHERE $on))"
  if [ -n "$ts" ]; then
    echo "+ (SELECT COUNT(*) FROM pas.$t p WHERE p.$ts > TIMESTAMP '${FLIP_TS}')"
  fi
}
reverse=$(psql_pas -tAc "SELECT $(reverse_sql policy_holders cust_id last_updated) + $(reverse_sql policies policy_number last_updated) + $(reverse_sql coverages policy_number,sequence_num) + $(reverse_sql premiums policy_number,coverage_seq,term_effective_date) + $(reverse_sql endorsements policy_number,endorsement_seq processed_date) + $(reverse_sql underwriting_decisions policy_number,decision_date created_timestamp)")
[ "$reverse" = 0 ] && step "4. reverse-delta check (pas ahead of DB2)" PASS "0 rows to replicate back to DB2" || step "4. reverse-delta check (pas ahead of DB2)" FAIL "$reverse rows need reverse replication"

T1=$(date +%s)
elapsed=$((T1 - T0))
[ "$elapsed" -le $((WINDOW_MIN * 60)) ] && step "5. inside rollback window" PASS "${elapsed}s <= ${WINDOW_MIN}m (window value Pending: Ops)" || step "5. inside rollback window" FAIL "${elapsed}s > ${WINDOW_MIN}m"

migration_cli resume-sync
set +e
migration_cli delta-sync,reconcile
rc=$?
set -e
[ "$rc" = 0 ] && step "6. re-entry: resume sync, delta, reconcile" PASS "reconciliation PASS after catching up the DB2-side write" || step "6. re-entry: resume sync, delta, reconcile" FAIL "reconcile exit $rc"

{
  echo "# Rollback rehearsal — LOCAL REHEARSAL ONLY"
  echo
  echo "- When: $STAMP (UTC) · host: $(hostname) · result: $([ $fail = 0 ] && echo PASS || echo FAIL)"
  echo "- Environment: local Docker Compose; DB2 is a PostgreSQL stand-in (\`${SOURCE_SCHEMA}\`); the gateway is a local route-flag file."
  echo "- Not covered: real gateway, real DB2 / CDC, production data volumes, reverse replication tooling."
  echo "  Production rehearsal is **Pending: Mainframe/Platform/Ops**."
  echo
  echo "| Step | Result | Evidence |"
  echo "|---|---|---|"
  printf '%s\n' "${STEPS[@]}"
} > "$RESULT"
cat "$RESULT"
exit "$fail"
