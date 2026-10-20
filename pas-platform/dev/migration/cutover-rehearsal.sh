#!/usr/bin/env bash
# AB-530 cutover rehearsal — LOCAL REHEARSAL ONLY (Compose + PostgreSQL stand-in for DB2).
# Runs the cutover runbook end to end, then the rollback runbook, and exits non-zero on any failure:
#   pre-cutover steady state -> freeze -> final delta + reconcile -> POLICY_SEQ -> pause sync
#   -> route flags new -> smoke tests -> rollback: flags legacy -> reverse replication (stand-in)
#   -> resume sync -> delta + reconcile.
# Runbooks: docs/modernization/runbooks/cutover.md and rollback.md. The production cutover, real
# gateway, real DB2/CDC and reverse replication tooling are Pending: Mainframe/Platform/Ops.
#
# Env: CUTOVER_REHEARSAL_RESET=1 (default) starts from an empty local stack;
#      ROLLBACK_WINDOW_MINUTES (default 60, Pending: Ops); BUSINESS_DATE (default today, New York).
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

WINDOW_MIN="${ROLLBACK_WINDOW_MINUTES:-60}"
export BUSINESS_DATE="${BUSINESS_DATE:-$(TZ=America/New_York date +%F)}"
RUN_DIR="$OUT_DIR/cutover-rehearsal"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
RESULT="$RUN_DIR/result-$STAMP.md"
FLAGS="$RUN_DIR/route-flags.json"
ROUTE_FLAGS=(python3 "$REPO_ROOT/scripts/cutover/route_flags.py")
SMOKE_LOG="$RUN_DIR/smoke-$STAMP.log"
declare -a STEPS=()
fail=0
step() { STEPS+=("| $1 | $2 | $3 |"); [ "$2" = PASS ] || fail=1; echo "[$2] $1 — $3"; }
q() { psql_pas -tAc "$1" | tr -d '\r'; }

# Migrated tables: name, DB2 primary key. pas-only columns (version, decision_id) and encrypted PII
# columns are not in the DB2 stand-in, so comparisons use the columns both sides have.
TABLES="policies:policy_number coverages:policy_number,sequence_num premiums:policy_number,coverage_seq,term_effective_date endorsements:policy_number,endorsement_seq underwriting_decisions:policy_number,decision_date"
shared_cols() {
  q "SELECT string_agg(column_name, ',' ORDER BY ordinal_position) FROM information_schema.columns s
     WHERE s.table_schema = '${SOURCE_SCHEMA}' AND s.table_name = '$1'
       AND EXISTS (SELECT 1 FROM information_schema.columns p
                   WHERE p.table_schema = 'pas' AND p.table_name = '$1' AND p.column_name = s.column_name)"
}
# Canonical comparison like the reconciliation: every column as text, CHAR padding trimmed (pas
# stores some DB2 CHAR(n) columns as VARCHAR).
canon() { echo "$1" | tr ',' '\n' | sed "s/.*/rtrim(&::text)${2:+ AS &}/" | paste -sd, -; }
# DB2 keys of rows that pas holds but DB2 lacks or holds differently (what reverse replication carries).
diff_keys() {
  local t="$1" pk="$2" cols; cols=$(canon "$(shared_cols "$t")" alias)
  echo "SELECT $pk FROM (SELECT $cols FROM pas.$t EXCEPT SELECT $cols FROM ${SOURCE_SCHEMA}.$t) d"
}
pas_ahead() {
  local total=0 spec n
  for spec in $TABLES policy_holders:cust_id; do
    n=$(q "SELECT COUNT(*) FROM ($(diff_keys "${spec%%:*}" "${spec#*:}")) k" || true)
    [[ "$n" =~ ^[0-9]+$ ]] || { echo "pas_ahead: comparison failed for ${spec%%:*}" >&2; echo ERR; return 0; }
    total=$((total + n))
  done
  echo "$total"
}
# Local stand-in for PostgreSQL -> DB2 reverse replication (ADR 0001 D3 step 5): upsert every pas
# row DB2 lacks or holds differently. Production tooling is Pending: Mainframe team.
reverse_replicate() {
  local spec t pk cols set
  for spec in $TABLES; do
    t="${spec%%:*}"; pk="${spec#*:}"; cols=$(shared_cols "$t")
    set=$(echo "$cols" | tr ',' '\n' | grep -vxF -f <(echo "$pk" | tr ',' '\n') | sed 's/.*/& = EXCLUDED.&/' | paste -sd, -)
    psql_pas -c "INSERT INTO ${SOURCE_SCHEMA}.$t ($cols)
      SELECT $cols FROM pas.$t WHERE ($(canon "$pk")) IN ($(diff_keys "$t" "$pk"))
      ON CONFLICT ($pk) DO UPDATE SET $set"
  done
}
next_val() { q "SELECT CASE WHEN is_called THEN last_value + 1 ELSE last_value END FROM $1"; }

mkdir -p "$RUN_DIR"
ensure_env
if [ "${CUTOVER_REHEARSAL_RESET:-1}" = 1 ]; then
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
fi
stack_up
seed_source

# 0. Steady state before the window: parallel run with merged capabilities in shadow.
cp "$REPO_ROOT/pas-platform/routing/route-flags.json" "$FLAGS"
"${ROUTE_FLAGS[@]}" set --file "$FLAGS" --all-merged shadow >/dev/null
migration_cli resume-sync
if migration_cli bulk-load,delta-sync,reconcile; then
  step "0. pre-cutover: bulk load + delta + reconcile, flags shadow" PASS "pas.* matches the DB2 stand-in"
else
  step "0. pre-cutover: bulk load + delta + reconcile, flags shadow" FAIL "see $OUT_DIR/cli-bulk-load_delta-sync_reconcile.log"
fi

# 1. Freeze: last CICS write lands just before the freeze; nothing changes in DB2 afterwards.
psql_pas -c "UPDATE ${SOURCE_SCHEMA}.policies SET updated_by = 'PREFRZ', last_updated = LOCALTIMESTAMP WHERE policy_number = 'POL-00000002'"
sleep 1
FREEZE_TS=$(q "SELECT LOCALTIMESTAMP")
step "1. freeze window opened" PASS "CICS/JCL write freeze stand-in at $FREEZE_TS (last pre-freeze write: POL-00000002)"

# 2. Final delta sync + reconciliation (AB-529 tooling).
if migration_cli delta-sync,reconcile; then
  synced=$(q "SELECT updated_by FROM pas.policies WHERE policy_number = 'POL-00000002'" | xargs)
  after_freeze=$(q "SELECT COUNT(*) FROM ${SOURCE_SCHEMA}.policies WHERE last_updated > TIMESTAMP '$FREEZE_TS'")
  if [ "$synced" = PREFRZ ] && [ "$after_freeze" = 0 ]; then
    step "2. final delta + reconcile" PASS "reconciliation PASS; pre-freeze write synced; 0 DB2 writes after freeze"
  else
    step "2. final delta + reconcile" FAIL "pre-freeze write synced=$synced, DB2 writes after freeze=$after_freeze"
  fi
else
  step "2. final delta + reconcile" FAIL "reconcile failed: $OUT_DIR/reports/reconciliation/latest.md"
fi

# 3. pas.policy_seq starts at or above DB2 POLICY_SEQ.
db2_next=$(next_val "${SOURCE_SCHEMA}.policy_seq")
q "SELECT setval('pas.policy_seq', GREATEST($(next_val pas.policy_seq), $db2_next), false)" >/dev/null
pas_next=$(next_val pas.policy_seq)
[ "$pas_next" -ge "$db2_next" ] && step "3. pas.policy_seq >= DB2 POLICY_SEQ" PASS "next pas $pas_next, next DB2 $db2_next" \
  || step "3. pas.policy_seq >= DB2 POLICY_SEQ" FAIL "next pas $pas_next < DB2 $db2_next"

# 4. System-of-record flip: stop DB2 -> pas sync.
migration_cli pause-sync
paused=$(q "SELECT paused FROM pas.migration_sync_control WHERE sync_name = 'DB2_TO_PAS'")
[ "$paused" = t ] && step "4. pause DB2 -> pas sync (pas is system of record)" PASS "migration_sync_control.paused = true" \
  || step "4. pause DB2 -> pas sync (pas is system of record)" FAIL "paused=$paused"

# 5. Route flags -> new for every merged capability (any still pendingMerge stays legacy).
if "${ROUTE_FLAGS[@]}" set --file "$FLAGS" --all-merged new >/dev/null; then
  live=$(python3 -c "import json,sys; c=json.load(open(sys.argv[1]))['capabilities']; print(' '.join(n for n,v in c.items() if v['state']=='new'))" "$FLAGS")
  step "5. route flags -> new" PASS "new: $live"
else
  step "5. route flags -> new" FAIL "route_flags.py refused the flip"
fi
pending=$(python3 -c "import json,sys; c=json.load(open(sys.argv[1]))['capabilities']; print(' '.join(n for n,v in c.items() if v.get('pendingMerge')))" "$FLAGS")
prod_note="${pending:+not live until their tickets merge: $pending}"
prod_note="${prod_note:-every capability live}"

# 6. Smoke tests per capability against the flipped platform.
export PAS_SMOKE_PSQL="${COMPOSE[*]} exec -T postgres psql -U ${PAS_DB_USERNAME:-pas} -d pas -tA"
export PAS_SMOKE_BATCH_CMD="$PAS_DIR/dev/migration/batch-cli.sh"
export PAS_SMOKE_EXTRACT_DIR="$OUT_DIR/extracts"
export PAS_SMOKE_KAFKA_CMD="${COMPOSE[*]} exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --from-beginning --timeout-ms 15000 --property print.key=true --topic"
export PAS_SMOKE_STATE_FILE="$RUN_DIR/smoke-state"
export PAS_POLICY_HTTP_PORT
PAS_POLICY_HTTP_PORT=$("${COMPOSE[@]}" port pas-policy-service 8080 | awk -F: '{print $NF}')
rm -f "$PAS_SMOKE_STATE_FILE"
set +e
"$REPO_ROOT/scripts/cutover/smoke-tests.sh" | tee "$SMOKE_LOG"
smoke_rc=${PIPESTATUS[0]}
set -e
counts=$(awk '{print $3}' "$SMOKE_LOG" | sort | uniq -c | awk '{printf "%s %s, ", $1, $2}')
[ "$smoke_rc" = 0 ] && step "6. smoke tests" PASS "${counts%, } ($prod_note; details below)" \
  || step "6. smoke tests" FAIL "${counts%, } (details below)"

# 7. The new platform took writes: pas is now ahead of DB2.
ahead=$(pas_ahead)
[ "$ahead" -gt 0 ] && step "7. post-cutover: pas ahead of DB2" PASS "$ahead rows written by the new platform since the flip" \
  || step "7. post-cutover: pas ahead of DB2" FAIL "no new-platform writes detected ($ahead)"

# Rollback (docs/modernization/runbooks/rollback.md).
T0=$(date +%s)
"${ROUTE_FLAGS[@]}" set --file "$FLAGS" --all legacy >/dev/null \
  && step "8. rollback: route flags -> legacy" PASS "all capabilities legacy; CICS/JCL authoritative" \
  || step "8. rollback: route flags -> legacy" FAIL "route_flags.py refused"

reverse_replicate >/dev/null
q "SELECT setval('${SOURCE_SCHEMA}.policy_seq', GREATEST($(next_val "${SOURCE_SCHEMA}.policy_seq"), $(next_val pas.policy_seq)), false)" >/dev/null
left=$(pas_ahead)
[ "$left" = 0 ] && step "9. reverse replication (local stand-in) + DB2 POLICY_SEQ" PASS "$ahead rows carried back to DB2; 0 left; DB2 POLICY_SEQ next $(next_val "${SOURCE_SCHEMA}.policy_seq")" \
  || step "9. reverse replication (local stand-in) + DB2 POLICY_SEQ" FAIL "$left rows still only in pas"

migration_cli resume-sync
if migration_cli delta-sync,reconcile; then
  step "10. resume sync, delta + reconcile" PASS "reconciliation PASS with DB2 as reference"
else
  step "10. resume sync, delta + reconcile" FAIL "see $OUT_DIR/reports/reconciliation/latest.md"
fi
elapsed=$(( $(date +%s) - T0 ))
[ "$elapsed" -le $((WINDOW_MIN * 60)) ] && step "11. rollback inside window" PASS "${elapsed}s <= ${WINDOW_MIN}m (window Pending: Ops)" \
  || step "11. rollback inside window" FAIL "${elapsed}s > ${WINDOW_MIN}m"

{
  echo "# Cutover + rollback rehearsal — LOCAL REHEARSAL ONLY"
  echo
  echo "- When: $STAMP (UTC) · business date $BUSINESS_DATE · host: $(hostname) · result: $([ $fail = 0 ] && echo PASS || echo FAIL)"
  echo "- Environment: local Docker Compose; DB2 is a PostgreSQL stand-in (\`${SOURCE_SCHEMA}\`); the gateway is a"
  echo "  local copy of \`pas-platform/routing/route-flags.json\`; reverse replication is a SQL stand-in."
  echo "- Not covered: real gateway, CICS/CA-7, real DB2/CDC, reverse replication tooling, production volumes,"
  echo "  SFTP delivery. Production cutover and rollback: **Pending: Mainframe/Platform/Ops**."
  echo
  echo "| Step | Result | Evidence |"
  echo "|---|---|---|"
  printf '%s\n' "${STEPS[@]}"
  echo
  echo "Smoke tests (\`scripts/cutover/smoke-tests.sh\`):"
  echo
  echo '```text'
  cat "$SMOKE_LOG"
  echo '```'
} > "$RESULT"
cp "$RESULT" "$RUN_DIR/latest.md"
cat "$RESULT"
exit "$fail"
