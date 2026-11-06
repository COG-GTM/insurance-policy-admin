#!/usr/bin/env bash
# AB-530 cutover smoke tests SM-01, SM-02, SM-04 (list) and SM-07: read-only, writes nothing.
# Local stack: run with no environment (ports from docker compose, tokens from local-token.sh).
# Shared environments: PAS_API_URL, PAS_MGMT_URL, PAS_READ_JWT (pas.policy.read) and
# PAS_OPS_JWT (pas.ops.read) from the enterprise IdP; optional PAS_SMOKE_POLICY and
# PAS_SMOKE_PREMIUM (CO-02 policy and its DB2 premium), PAS_SMOKE_AGENT, PAS_ALERTMANAGER_URL.
set -euo pipefail
cd "$(dirname "$0")/../.."

fail() { echo "cutover-smoke: FAIL - $*" >&2; exit 1; }
ok() { echo "cutover-smoke: $*"; }

if [[ -z "${PAS_API_URL:-}" ]]; then
  api_port=$(docker compose port pas-policy-service 8080) || fail "pas-policy-service is not running"
  mgmt_port=$(docker compose port pas-policy-service 8081) || fail "pas-policy-service is not running"
  PAS_API_URL="http://localhost:${api_port##*:}"
  PAS_MGMT_URL="http://localhost:${mgmt_port##*:}"
  PAS_READ_JWT=$(pas/scripts/local-token.sh "pas.policy.read" '"pas_book":"*"')
  PAS_OPS_JWT=$(pas/scripts/local-token.sh "pas.ops.read")
fi
: "${PAS_MGMT_URL:?set PAS_MGMT_URL}" "${PAS_READ_JWT:?set PAS_READ_JWT}" "${PAS_OPS_JWT:?set PAS_OPS_JWT}"
policy="${PAS_SMOKE_POLICY:-}"
agent="${PAS_SMOKE_AGENT:-}"

status() { # status <url> [jwt] -> HTTP code, body in $body_file
  local auth=()
  [[ -n "${2:-}" ]] && auth=(-H "Authorization: Bearer $2")
  curl -s -o "$body_file" -w '%{http_code}' "${auth[@]}" "$1"
}
body_file=$(mktemp)
trap 'rm -f "$body_file"' EXIT

# SM-01: readiness and a known policy (premium compared with DB2 when given).
[[ $(status "$PAS_MGMT_URL/actuator/health/readiness") == 200 ]] && grep -q '"UP"' "$body_file" \
  || fail "SM-01 readiness is not UP"
if [[ -n "$policy" ]]; then
  code=$(status "$PAS_API_URL/api/v1/policies/$policy" "$PAS_READ_JWT")
  [[ "$code" == 200 ]] || fail "SM-01 GET policy $policy returned HTTP $code"
  if [[ -n "${PAS_SMOKE_PREMIUM:-}" ]]; then
    python3 - "$body_file" "$PAS_SMOKE_PREMIUM" <<'PY' || fail "SM-01 premium of $policy differs from DB2"
import decimal, json, sys
premium = json.load(open(sys.argv[1])).get("premium")
sys.exit(0 if premium is not None and decimal.Decimal(str(premium)) == decimal.Decimal(sys.argv[2]) else 1)
PY
  fi
  ok "SM-01 readiness UP, policy $policy readable${PAS_SMOKE_PREMIUM:+ with the DB2 premium}"
else
  code=$(status "$PAS_API_URL/api/v1/policies/POL-99999999" "$PAS_READ_JWT")
  [[ "$code" == 404 ]] || fail "SM-01 missing policy returned HTTP $code, expected 404"
  ok "SM-01 readiness UP, inquiry answers (no PAS_SMOKE_POLICY given)"
fi

# SM-02: authentication and scope.
probe="$PAS_API_URL/api/v1/policies/${policy:-POL-00000001}"
[[ $(status "$probe") == 401 ]] || fail "SM-02 inquiry without a token is not 401"
[[ $(status "$probe" "$PAS_OPS_JWT") == 403 ]] || fail "SM-02 inquiry without pas.policy.read is not 403"
ok "SM-02 401 without a token, 403 without scope"

# SM-04: Broker Portal list endpoint.
if [[ -n "$agent" ]]; then
  code=$(status "$PAS_API_URL/api/v1/policies?agentCode=$agent&limit=1" "$PAS_READ_JWT")
  [[ "$code" == 200 ]] && grep -q '"policies"' "$body_file" || fail "SM-04 list for agent $agent returned HTTP $code"
else
  code=$(status "$PAS_API_URL/api/v1/policies?limit=1" "$PAS_READ_JWT")
  [[ "$code" == 400 ]] || fail "SM-04 list without a filter returned HTTP $code, expected 400"
fi
ok "SM-04 Broker Portal list endpoint answers"

# SM-07: metrics behind a token, no cutover-relevant alert firing.
[[ $(status "$PAS_MGMT_URL/actuator/prometheus") == 401 ]] || fail "SM-07 prometheus reachable without a token"
[[ $(status "$PAS_MGMT_URL/actuator/prometheus" "$PAS_OPS_JWT") == 200 ]] || fail "SM-07 prometheus not reachable with pas.ops.read"
if [[ -n "${PAS_ALERTMANAGER_URL:-}" ]]; then
  curl -fsS -o "$body_file" "$PAS_ALERTMANAGER_URL/api/v2/alerts?active=true" || fail "SM-07 Alertmanager not reachable"
  firing=$(python3 -c 'import json,sys; print(" ".join(sorted({a["labels"].get("alertname","") for a in json.load(open(sys.argv[1])) if a["labels"].get("alertname","").startswith("Pas")})))' "$body_file")
  [[ -z "$firing" ]] || fail "SM-07 PAS alerts firing: $firing"
  ok "SM-07 metrics protected, no PAS alert firing"
else
  ok "SM-07 metrics protected (no PAS_ALERTMANAGER_URL, alert check skipped)"
fi
ok "all automated checks passed"
