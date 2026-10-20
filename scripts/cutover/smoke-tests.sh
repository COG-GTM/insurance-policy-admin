#!/usr/bin/env bash
# AB-530 per-capability smoke tests for the cutover and rollback runbooks.
#
#   scripts/cutover/smoke-tests.sh [capability ...]     (default: every capability)
#
# Prints one "SMOKE <capability> PASS|FAIL|PENDING|SKIP <evidence>" line per capability and exits 1
# if any is FAIL. PENDING = the capability's code has not merged (pendingMerge in route-flags.json); SKIP = a hook
# below is not configured for this environment. Neither counts as a pass at go/no-go.
#
# Environment (local defaults target `make dev` / the cutover rehearsal):
#   PAS_POLICY_URL         pas-policy-service base URL (default http://127.0.0.1:$PAS_POLICY_HTTP_PORT)
#   PAS_SMOKE_TOKEN_CMD    prints a bearer JWT for "<scopes>" "<extra claims JSON fragment>"
#                          (default pas-platform/dev/dev-jwt.sh mint; production: the smoke-test
#                          IdP client, Pending: Security)
#   PAS_SMOKE_POLICY       existing policy readable by PAS_SMOKE_AGENT (default POL-00000001)
#   PAS_SMOKE_AGENT / PAS_SMOKE_BRANCH / PAS_SMOKE_HOLDER  smoke-test agent, branch and policyholder
#                          (defaults AG1001 / CHI1 / C000000001; production values Pending: Business owner)
#   PAS_SMOKE_OTHER_AGENT  agent that must NOT see PAS_SMOKE_POLICY (default AG9999)
#   PAS_SMOKE_PSQL         command reading SQL on stdin against the pas database (read-only checks)
#   PAS_SMOKE_BATCH_CMD    command that runs a one-shot pas-batch CLI with the given arguments
#   PAS_SMOKE_EXTRACT_DIR  pas.extract.output-dir of that CLI (to check the extract files)
#   PAS_SMOKE_KAFKA_CMD    command printing "key<TAB>value" records of the topic given as $1
#   BUSINESS_DATE          batch run date (default today in America/New_York)
#   PAS_SMOKE_STATE_FILE   where the new-business smoke records its policy number for later steps
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
URL="${PAS_POLICY_URL:-http://127.0.0.1:${PAS_POLICY_HTTP_PORT:-8080}}"
TOKEN_CMD="${PAS_SMOKE_TOKEN_CMD:-$REPO_ROOT/pas-platform/dev/dev-jwt.sh mint}"
POLICY="${PAS_SMOKE_POLICY:-POL-00000001}"
AGENT="${PAS_SMOKE_AGENT:-AG1001}"
BRANCH="${PAS_SMOKE_BRANCH:-CHI1}"
HOLDER="${PAS_SMOKE_HOLDER:-C000000001}"
OTHER_AGENT="${PAS_SMOKE_OTHER_AGENT:-AG9999}"
BUSINESS_DATE="${BUSINESS_DATE:-$(TZ=America/New_York date +%F)}"
STATE_FILE="${PAS_SMOKE_STATE_FILE:-${TMPDIR:-/tmp}/pas-smoke-state}"
ALL="inquiry broker-read new-business underwriting renewal endorsement premium-batch claims-extract actuarial-exposure document-events"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
failed=0

result() {
  echo "SMOKE $1 $2 $3"
  [ "$2" = FAIL ] && failed=1
  return 0
}
token() { $TOKEN_CMD "$1" "${2:-}"; }
# http METHOD PATH TOKEN [BODY] [extra curl args...] -> prints status, body in $WORK/body
http() {
  local method="$1" path="$2" tok="$3" body="${4:-}"
  shift 4 2>/dev/null || shift $#
  local args=(-sS -o "$WORK/body" -w '%{http_code}' -X "$method" "$URL$path")
  [ -n "$tok" ] && args+=(-H "Authorization: Bearer $tok")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' --data "$body")
  curl "${args[@]}" "$@" 2>"$WORK/curl.err" || echo "000"
}
sql() { [ -n "${PAS_SMOKE_PSQL:-}" ] && echo "$1" | $PAS_SMOKE_PSQL; }
new_policy() { [ -f "$STATE_FILE" ] && cat "$STATE_FILE"; }

smoke_inquiry() {
  local tok s anon
  tok=$(token "policy:read" '"book":["*"]')
  s=$(http GET "/api/v1/policies/$POLICY" "$tok")
  if [ "$s" != 200 ] || [ "$(jq -r .policyNumber "$WORK/body")" != "$POLICY" ]; then
    result inquiry FAIL "GET $POLICY -> $s"; return
  fi
  s=$(http GET "/api/v1/policies/$POLICY/coverages" "$tok")
  [ "$s" = 200 ] || { result inquiry FAIL "GET $POLICY/coverages -> $s"; return; }
  anon=$(http GET "/api/v1/policies/$POLICY" "")
  [ "$anon" = 401 ] || { result inquiry FAIL "no token -> $anon (want 401)"; return; }
  result inquiry PASS "GET policy + coverages 200 (book:*), no token 401"
}

smoke_broker_read() {
  local own other
  own=$(http GET "/api/v1/policies/$POLICY" "$(token "policy:read" "\"agent_code\":\"$AGENT\"")")
  other=$(http GET "/api/v1/policies/$POLICY" "$(token "policy:read" "\"agent_code\":\"$OTHER_AGENT\"")")
  local book
  book=$(http GET "/api/v1/policies?agentCode=$AGENT" "$(token "policy:read" "\"agent_code\":\"$AGENT\"")")
  if [ "$own" = 200 ] && [ "$other" = 404 ] && [ "$book" = 200 ]; then
    result broker-read PASS "agent $AGENT -> 200, agent $OTHER_AGENT -> 404, book listing ?agentCode=$AGENT -> 200 (broker-portal-migration.md PQ-01..PQ-06; portal switch Pending: Broker Portal team)"
  else
    result broker-read FAIL "agent $AGENT -> $own (want 200), agent $OTHER_AGENT -> $other (want 404), book -> $book (want 200)"
  fi
}

smoke_new_business() {
  local eff exp body s number
  eff=$(TZ=America/New_York date -d '+5 days' +%F)
  exp=$(TZ=America/New_York date -d '+5 days +1 year' +%F)
  body=$(printf '{"policyholderId":"%s","policyType":"HOM","effectiveDate":"%s","expiryDate":"%s","coverageLimit":250000,"totalPremium":1200,"deductible":500,"agentCode":"%s","branchCode":"%s"}' \
    "$HOLDER" "$eff" "$exp" "$AGENT" "$BRANCH")
  s=$(http POST /api/v1/policies "$(token "policy:write policy:read" "\"agent_code\":\"$AGENT\"")" "$body" \
    -H "Idempotency-Key: cutover-smoke-$(date +%s)-$$")
  number=$(jq -r '.policy.policyNumber // empty' "$WORK/body" 2>/dev/null)
  if [ "$s" != 201 ] || [ -z "$number" ]; then
    result new-business FAIL "POST /api/v1/policies -> $s $(head -c 300 "$WORK/body")"; return
  fi
  echo "$number" > "$STATE_FILE"
  s=$(http GET "/api/v1/policies/$number" "$(token "policy:read" "\"agent_code\":\"$AGENT\"")")
  [ "$s" = 200 ] || { result new-business FAIL "created $number but GET -> $s"; return; }
  result new-business PASS "POST /api/v1/policies 201 -> $number, readable by $AGENT"
}

smoke_underwriting() {
  local number s code
  number=$(new_policy)
  [ -n "$number" ] || { result underwriting FAIL "no policy from the new-business smoke"; return; }
  s=$(http POST "/api/v1/policies/$number/underwriting-decisions" \
    "$(token "underwriting:decide policy:read" "\"agent_code\":\"$AGENT\"")" "")
  code=$(jq -r '.decisionCode // empty' "$WORK/body" 2>/dev/null)
  if [ "$s" = 201 ] && [ -n "$code" ]; then
    result underwriting PASS "POST underwriting-decisions 201 -> $code for $number"
  else
    result underwriting FAIL "POST underwriting-decisions -> $s $(head -c 300 "$WORK/body") (503 = pas.claims.adapter unavailable)"
  fi
}

smoke_document_events() {
  local number counts published total i
  number=$(new_policy)
  [ -n "$number" ] || { result document-events FAIL "no policy from the new-business smoke"; return; }
  [ -n "${PAS_SMOKE_PSQL:-}" ] || { result document-events SKIP "PAS_SMOKE_PSQL not set"; return; }
  for i in $(seq 1 30); do
    counts=$(sql "SELECT COUNT(*) FILTER (WHERE published_at IS NOT NULL) || '/' || COUNT(*) FROM pas.outbox WHERE message_key = '$number'" | tr -d '[:space:]')
    published="${counts%/*}"; total="${counts#*/}"
    [ -n "$total" ] && [ "$total" -gt 0 ] && [ "$published" = "$total" ] && break
    sleep 2
  done
  if [ -z "$total" ] || [ "$total" = 0 ] || [ "$published" != "$total" ]; then
    result document-events FAIL "outbox rows for $number published $counts after 60s"; return
  fi
  local evidence="outbox $counts published for $number" docs
  docs=$(sql "SELECT COUNT(*) FROM pas.outbox WHERE message_key = '$number' AND topic = 'pas.document.requests.v1' AND event_type = 'DocumentRequested'" | tr -d '[:space:]')
  [ "$docs" = 1 ] || { result document-events FAIL "$evidence but $docs DocumentRequested rows (want 1)"; return; }
  evidence="$evidence; DocumentRequested on pas.document.requests.v1"
  if [ -n "${PAS_SMOKE_KAFKA_CMD:-}" ]; then
    if $PAS_SMOKE_KAFKA_CMD pas.policy.events.v1 2>/dev/null | grep -q "^$number"; then
      evidence="$evidence; PolicyCreated on pas.policy.events.v1"
    else
      result document-events FAIL "$evidence but no record keyed $number on pas.policy.events.v1"; return
    fi
  fi
  result document-events PASS "$evidence (bridges: PAS_INTEGRATION_BRIDGES_ENABLED; FileNet MQ transport Pending: Document/FileNet team)"
}

batch() {
  [ -n "${PAS_SMOKE_BATCH_CMD:-}" ] || return 99
  $PAS_SMOKE_BATCH_CMD "$@" >"$WORK/batch.log" 2>&1
}

smoke_premium_batch() {
  local rc row
  batch --pas.batch.premium.cli.run=true --pas.batch.premium.cli.run-date="$BUSINESS_DATE"; rc=$?
  [ "$rc" = 99 ] && { result premium-batch SKIP "PAS_SMOKE_BATCH_CMD not set (check the first scheduled run instead)"; return; }
  [ "$rc" = 0 ] || { result premium-batch FAIL "premium CLI exit $rc (4 = completed with errors, 16 = failed)"; return; }
  row=$(sql "SELECT write_target || ' ' || outcome || ' read=' || policies_read || ' updated=' || policies_updated FROM pas_batch.premium_run_summary WHERE run_date = DATE '$BUSINESS_DATE' ORDER BY job_execution_id DESC LIMIT 1" | xargs)
  case "$row" in
    "PAS COMPLETED "*) result premium-batch PASS "premiumRecalculationJob $BUSINESS_DATE: $row" ;;
    *) result premium-batch FAIL "premium_run_summary: '${row:-none}' (want write_target PAS, COMPLETED)" ;;
  esac
}

extract_job() {
  local cap="$1" job="$2" date="$3"; shift 3
  local rc f missing=""
  batch --spring.batch.job.enabled=true --spring.batch.job.name="$job" "businessDate=$date" "launchedAt=$(date +%s%N)"; rc=$?
  [ "$rc" = 99 ] && { result "$cap" SKIP "PAS_SMOKE_BATCH_CMD not set (check the first scheduled run instead)"; return; }
  [ "$rc" = 0 ] || { result "$cap" FAIL "$job exit $rc"; return; }
  if [ -n "${PAS_SMOKE_EXTRACT_DIR:-}" ]; then
    for f in "$@"; do [ -s "$PAS_SMOKE_EXTRACT_DIR/$job/$date/$f" ] || missing="$missing $f"; done
    [ -z "$missing" ] || { result "$cap" FAIL "$job $date missing or empty:$missing"; return; }
  fi
  result "$cap" PASS "$job $date COMPLETED: $*"
}

smoke_claims_extract() { extract_job claims-extract dailyExtractJob "$BUSINESS_DATE" policy_extract.dat coverage_extract.dat; }
smoke_actuarial_exposure() { extract_job actuarial-exposure monthlyExposureJob "${BUSINESS_DATE%-*}-01" exposure_monthly.csv; }
# Renewal and endorsement change PAS_SMOKE_POLICY: in production it must be the designated smoke policy.
smoke_renewal() {
  local s expiry
  s=$(http POST "/api/v1/policies/$POLICY/renewals" "$(token "policy:write policy:read" "\"agent_code\":\"$AGENT\"")" "")
  expiry=$(jq -r '.. | objects | .expiryDate? // empty' "$WORK/body" 2>/dev/null | head -1)
  if [ "$s" = 201 ]; then
    result renewal PASS "POST /api/v1/policies/$POLICY/renewals 201${expiry:+ -> expiry $expiry}"
  else
    result renewal FAIL "POST renewals -> $s $(head -c 300 "$WORK/body") (503 = pas.claims.adapter unavailable)"
  fi
}

smoke_endorsement() {
  local s
  s=$(http POST "/api/v1/policies/$POLICY/endorsements" "$(token "policy:write policy:read" "\"agent_code\":\"$AGENT\"")" \
    '{"endorsementType":"ACH"}')
  if [ "$s" = 201 ]; then
    result endorsement PASS "POST /api/v1/policies/$POLICY/endorsements 201 (ACH)"
  else
    result endorsement FAIL "POST endorsements -> $s $(head -c 300 "$WORK/body")"
  fi
}

for cap in ${*:-$ALL}; do
  case "$cap" in
    inquiry|broker-read|new-business|underwriting|renewal|endorsement|premium-batch|claims-extract|actuarial-exposure|document-events)
      "smoke_${cap//-/_}" ;;
    *) result "$cap" FAIL "unknown capability" ;;
  esac
done
exit "$failed"
