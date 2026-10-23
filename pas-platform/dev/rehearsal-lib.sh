#!/usr/bin/env bash
set -euo pipefail

REHEARSAL_PLATFORM="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

validate_rehearsal_environment() {
  if [[ -f "$REHEARSAL_PLATFORM/.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    source "$REHEARSAL_PLATFORM/.env"
    set +a
  fi

  : "${PAS_DB_USERNAME:?run make dev-env}"
  : "${PAS_DB_PASSWORD:?run make dev-env}"
  : "${PAS_PII_ENCRYPTION_KEY_ID:?run make dev-env}"
  : "${PAS_PII_ENCRYPTION_KEY_VERSION:?run make dev-env}"
  : "${PAS_PII_ENCRYPTION_KEY:?run make dev-env}"
  : "${PAS_PII_BLIND_INDEX_KEY:?run make dev-env}"
  : "${PAS_RECON_FINGERPRINT_KEY:?run make dev-env}"
  : "${PAS_MIGRATION_SOURCE_PASSWORD:?run make dev-env}"
  : "${PAS_MIGRATION_REVERSE_PASSWORD:?run make dev-env}"

  if [[ "${PAS_ENV:-}" != "local" ]]; then
    printf 'Refusing rehearsal: PAS_ENV must be local; run make dev-env.\n' >&2
    return 1
  fi
  if [[ "$PAS_PII_ENCRYPTION_KEY_ID" != "local-v1" ]]; then
    printf 'Refusing rehearsal: PAS_PII_ENCRYPTION_KEY_ID must be local-v1; run make dev-env.\n' >&2
    return 1
  fi
  if [[ ! "${PAS_MIGRATION_SOURCE_URL:-}" =~ ^jdbc:postgresql://acmeins-source(:[0-9]+)?/ ]]; then
    printf 'Refusing rehearsal: PAS_MIGRATION_SOURCE_URL must use the Compose acmeins-source host.\n' >&2
    return 1
  fi

}

configure_rehearsal_connections() {
  export PAS_MIGRATION_SOURCE_USERNAME="${PAS_MIGRATION_SOURCE_USERNAME:-acmeins_readonly}"
  export PAS_MIGRATION_REVERSE_USERNAME="${PAS_MIGRATION_REVERSE_USERNAME:-acmeins_reverse}"
  export PAS_DB_URL="jdbc:postgresql://rollback-target:5432/pas"
  export PAS_MIGRATION_ENABLED=true
  export PAS_MIGRATION_SOURCE_SSL=false
  export PAS_MIGRATION_SOURCE_SCHEMA=ACMEINS
  export PAS_BATCH_HTTP_PORT="${PAS_BATCH_HTTP_PORT:-18081}"
  export PAS_POLICY_HTTP_PORT="${PAS_POLICY_HTTP_PORT:-18080}"
}

load_rehearsal_environment() {
  validate_rehearsal_environment
  configure_rehearsal_connections
  export PAS_MIGRATION_REVERSE_SYNC_ENABLED=true
  export PAS_ROUTE_NEW_BUSINESS=new
  export PAS_ROUTE_RENEWAL=new
  export PAS_ROUTE_ENDORSEMENT=new
}

prepare_cutover_rehearsal_environment() {
  validate_rehearsal_environment
  configure_rehearsal_connections
}

run_cli() {
  docker compose --profile migration run --rm --no-deps pas-batch "$@" \
    --spring.main.web-application-type=none
}

target_psql() {
  docker compose --profile migration exec -T rollback-target \
    psql -U "$PAS_DB_USERNAME" -d pas -v ON_ERROR_STOP=1 -Atqc "$@"
}

source_psql() {
  docker compose --profile migration exec -T acmeins-source \
    psql -U acmeins -d acmeins -v ON_ERROR_STOP=1 -Atqc "$@"
}

mint_rehearsal_jwt() {
  "$REHEARSAL_PLATFORM/dev/dev-jwt.sh" mint \
    --sub "${1:-ab529-rollback-rehearsal}" \
    --scope "policy:read policy:write underwriting:decide policy:renew policy:endorse" \
    --claim book=AGENT \
    --claim agent_id=AGT001 \
    --claim-json 'branch_codes=["B001"]'
}

api_create_policy() {
  local jwt="$1"
  local effective_date="$2"
  local response
  response="$(
    curl -fsS -X POST "http://localhost:${PAS_POLICY_HTTP_PORT}/api/v1/policies" \
      -H "Authorization: Bearer $jwt" \
      -H 'Content-Type: application/json' \
      -H "X-Correlation-Id: $(python3 -c 'import uuid; print(uuid.uuid4())')" \
      --data "{\"policyType\":\"AUT\",\"policyholderId\":\"C529000001\",\"effectiveDate\":\"$effective_date\",\"agentCode\":\"AGT001\",\"branchCode\":\"B001\",\"coverageLimit\":100000.00,\"deductible\":500.00,\"totalPremium\":1200.00}"
  )"
  printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["policyNumber"])'
}

api_endorse_policy() {
  local jwt="$1"
  local policy_number="$2"
  local effective_date="$3"
  local new_limit="$4"
  curl -fsS -o /dev/null -w '%{http_code}' -X POST \
    "http://localhost:${PAS_POLICY_HTTP_PORT}/api/v1/policies/$policy_number/endorsements" \
    -H "Authorization: Bearer $jwt" \
    -H 'Content-Type: application/json' \
    -H "X-Correlation-Id: $(python3 -c 'import uuid; print(uuid.uuid4())')" \
    --data "{\"type\":\"LCH\",\"effectiveDate\":\"$effective_date\",\"coverageSeq\":1,\"newLimit\":$new_limit}"
}

api_renew_policy() {
  local jwt="$1"
  local policy_number="$2"
  local expiry_date="$3"
  curl -fsS -o /dev/null -w '%{http_code}' -X POST \
    "http://localhost:${PAS_POLICY_HTTP_PORT}/api/v1/policies/$policy_number/renewals" \
    -H "Authorization: Bearer $jwt" \
    -H 'Content-Type: application/json' \
    -H "X-Correlation-Id: $(python3 -c 'import uuid; print(uuid.uuid4())')" \
    --data "{\"termExpiryDate\":\"$expiry_date\"}"
}
