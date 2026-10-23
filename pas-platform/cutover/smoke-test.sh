#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"

base_url="http://localhost:${PAS_POLICY_HTTP_PORT:-18080}"
batch_url="http://localhost:${PAS_BATCH_HTTP_PORT:-18081}"
extract_cmd=false
while (($#)); do
  case "$1" in
    --base-url)
      (($# >= 2)) || { usage >&2; exit 2; }
      base_url="$2"
      shift
      ;;
    --batch-url)
      (($# >= 2)) || { usage >&2; exit 2; }
      batch_url="$2"
      shift
      ;;
    --extract-cmd)
      extract_cmd=true
      if (($# >= 2)) && [[ "$2" != --* ]]; then
        shift
      fi
      ;;
    --help|-h)
      printf 'Usage: %s [--base-url URL] [--batch-url URL] [--extract-cmd [local]]\n' "${0##*/}"
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      exit 2
      ;;
  esac
  shift
done

artifacts="${PAS_CUTOVER_STATE_DIR}/smoke"
mkdir -p "$artifacts"
response_file="$artifacts/response.json"
metrics_file="$artifacts/policy-metrics.txt"
trap 'rm -f "$response_file"' EXIT

if [[ "$PAS_CUTOVER_ENV" == "local" ]]; then
  # shellcheck disable=SC1091
  source "$PLATFORM_DIR/dev/rehearsal-lib.sh"
fi

smoke_pass() {
  log "PASS $*"
}

smoke_fail() {
  log "FAIL $*"
  return 1
}

request_status() {
  local method="$1" url="$2" body="${3:-}" extra_header="${4:-}"
  local args=(-sS -o /dev/null -w '%{http_code}' -X "$method" "$url")
  args+=(-H "Authorization: Bearer $jwt")
  if [[ -n "$body" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$body")
  fi
  if [[ -n "$extra_header" ]]; then
    args+=(-H "$extra_header")
  fi
  curl "${args[@]}"
}

if [[ -z "${PAS_SMOKE_JWT:-}" ]]; then
  if [[ "$PAS_CUTOVER_ENV" != "local" ]]; then
    log 'FAIL PAS_SMOKE_JWT is required outside local rehearsal mode'
    exit 2
  fi
  jwt="$(mint_rehearsal_jwt ab530-cutover-smoke)"
else
  jwt="$PAS_SMOKE_JWT"
fi

policy_number="${PAS_SMOKE_POLICY:-POL529000001}"
for service_url in "$base_url" "$batch_url"; do
  service_name='policy'
  [[ "$service_url" == "$batch_url" ]] && service_name='batch'
  for health in liveness readiness; do
    status="$(curl -sS -o /dev/null -w '%{http_code}' \
      -H "Authorization: Bearer $jwt" "$service_url/actuator/health/$health")"
    if [[ "$status" != 200 ]]; then
      smoke_fail "$service_name $health returned HTTP $status"
    fi
    smoke_pass "$service_name $health returned HTTP 200"
  done
done

for path in \
  "/api/v1/policies/$policy_number" \
  "/api/v1/policies/$policy_number/coverages"; do
  status="$(request_status GET "$base_url$path")"
  if [[ "$status" != 200 ]]; then
    smoke_fail "GET $path returned HTTP $status"
  fi
  smoke_pass "GET $path returned HTTP 200"
done

idempotency_key="$(python3 -c 'import uuid; print(uuid.uuid4())')"
effective_date="$(date -u -d 'tomorrow' +%F)"
status="$(curl -sS -o "$response_file" -w '%{http_code}' \
  -X POST "$base_url/api/v1/policies" \
  -H "Authorization: Bearer $jwt" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $idempotency_key" \
  -H "X-Correlation-Id: $(python3 -c 'import uuid; print(uuid.uuid4())')" \
  --data "{\"policyType\":\"AUT\",\"policyholderId\":\"C529000001\",\"effectiveDate\":\"$effective_date\",\"agentCode\":\"AGT001\",\"branchCode\":\"B001\",\"coverageLimit\":100000.00,\"deductible\":500.00,\"totalPremium\":1200.00}")"
if [[ "$status" != 201 ]]; then
  smoke_fail "new-business create returned HTTP $status"
fi
created_policy="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["policyNumber"])' "$response_file")"
rm -f "$response_file"
smoke_pass "new-business create returned HTTP 201 for $created_policy"

status="$(request_status POST "$base_url/api/v1/policies/$created_policy/underwriting-decisions")"
if [[ "$status" != 201 && "$status" != 200 ]]; then
  smoke_fail "underwriting returned HTTP $status"
fi
smoke_pass "underwriting returned HTTP $status for $created_policy"

policy_response="$artifacts/policy-response.json"
status="$(curl -sS -o "$policy_response" -w '%{http_code}' \
  -H "Authorization: Bearer $jwt" "$base_url/api/v1/policies/$policy_number")"
if [[ "$status" != 200 ]]; then
  rm -f "$policy_response"
  smoke_fail "renewal policy lookup returned HTTP $status"
fi
term_expiry="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["expiryDate"])' "$policy_response")"
rm -f "$policy_response"

renewal_already_recorded=false
if [[ "$PAS_CUTOVER_ENV" == "local" || -n "${PAS_CUTOVER_PSQL_CMD:-}" ]]; then
  if [[ "$PAS_CUTOVER_ENV" == "local" ]]; then
    renewal_already_recorded="$(target_psql "SELECT EXISTS (SELECT 1 FROM pas.policy_renewals WHERE policy_number = '$policy_number' AND prior_expiry_date = '$term_expiry')")"
  else
    renewal_already_recorded="$(cutover_sql "SELECT EXISTS (SELECT 1 FROM pas.policy_renewals WHERE policy_number = '$policy_number' AND prior_expiry_date = '$term_expiry')")"
  fi
fi
if [[ "$renewal_already_recorded" == true || "$renewal_already_recorded" == t ]]; then
  smoke_pass "renewal already recorded for term ending $term_expiry; request skipped"
else
  status="$(request_status POST "$base_url/api/v1/policies/$policy_number/renewals" \
    "{\"termExpiryDate\":\"$term_expiry\"}")"
  if [[ "$status" != 201 ]]; then
    smoke_fail "renewal returned HTTP $status"
  fi
  smoke_pass "renewal returned HTTP 201 for $policy_number"
fi

policy_response="$artifacts/policy-response.json"
status="$(curl -sS -o "$policy_response" -w '%{http_code}' \
  -H "Authorization: Bearer $jwt" "$base_url/api/v1/policies/$policy_number")"
if [[ "$status" != 200 ]]; then
  rm -f "$policy_response"
  smoke_fail "post-renewal policy lookup returned HTTP $status"
fi
read -r endorsement_date current_limit < <(python3 -c '
import json,sys
policy=json.load(open(sys.argv[1], encoding="utf-8"))
print(policy["effectiveDate"], policy["coverageLimit"])
' "$policy_response")
rm -f "$policy_response"

new_limit="$(python3 -c 'from decimal import Decimal; import sys; print(Decimal(sys.argv[1]) + Decimal("1000"))' "$current_limit")"
status="$(request_status POST "$base_url/api/v1/policies/$policy_number/endorsements" \
  "{\"type\":\"LCH\",\"effectiveDate\":\"$endorsement_date\",\"coverageSeq\":1,\"newLimit\":$new_limit}")"
if [[ "$status" != 201 ]]; then
  smoke_fail "endorsement returned HTTP $status"
fi
smoke_pass "endorsement returned HTTP 201 for $policy_number"

published=false
unpublished=''
for ((attempt = 1; attempt <= 20; attempt++)); do
  curl -fsS -H "Authorization: Bearer $jwt" "$base_url/actuator/prometheus" -o "$metrics_file"
  read -r unpublished published < <(python3 - "$metrics_file" <<'PY'
import sys

unpublished = None
published = False
with open(sys.argv[1], encoding="utf-8") as source:
    for line in source:
        if line.startswith("pas_outbox_unpublished ") or line.startswith("pas_outbox_unpublished{"):
            try:
                unpublished = float(line.rsplit(" ", 1)[1])
            except ValueError:
                pass
        if line.startswith("pas_outbox_published_total ") or line.startswith("pas_outbox_published_total{"):
            try:
                published = published or float(line.rsplit(" ", 1)[1]) > 0
            except ValueError:
                pass
print("" if unpublished is None else unpublished, str(published).lower())
PY
  )
  if [[ "$unpublished" == 0 || "$unpublished" == 0.0 ]] && [[ "$published" == true ]]; then
    break
  fi
  if ((attempt < 20)); then
    sleep 3
  fi
done
if [[ "$unpublished" != 0 && "$unpublished" != 0.0 ]]; then
  smoke_fail "outbox unpublished metric was ${unpublished:-missing} after 60 seconds"
fi
if [[ "$published" != true ]]; then
  smoke_fail 'outbox published counter did not increase'
fi
smoke_pass 'outbox unpublished is zero and published counter is positive'

if [[ "$extract_cmd" == true ]]; then
  business_date="$(date -u +%F)"
  if [[ "$PAS_CUTOVER_ENV" == "local" ]]; then
    # Set LEGACY only for this CLI process to prove the delivery tasklet skips network delivery.
    before="$(target_psql 'SELECT count(*) FROM pas_batch.extract_delivery')"
    docker compose --profile migration run --rm --no-deps \
      -e PAS_ROUTING_DAILY_EXTRACT=legacy pas-batch \
      --pas.batch.cli.job=dailyExtractJob \
      --pas.batch.cli.business-date="$business_date" \
      --spring.main.web-application-type=none
    after="$(target_psql 'SELECT count(*) FROM pas_batch.extract_delivery')"
    if [[ "$before" != "$after" ]]; then
      smoke_fail "daily extract dry-run created delivery records ($before -> $after)"
    fi
  else
    cutover_batch --pas.batch.cli.job=dailyExtractJob \
      --pas.batch.cli.business-date="$business_date"
  fi
  smoke_pass "dailyExtractJob completed for $business_date with legacy/shadow non-delivery semantics"
fi

log 'PASS all cutover smoke checks completed'
