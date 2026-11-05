#!/usr/bin/env bash
# Smoke test of the running local stack (docker compose): probes, metrics, deny-by-default API, JSON logs.
set -euo pipefail

fail() { echo "smoke: FAIL - $*" >&2; exit 1; }
# Host ports as actually published by Compose (they follow PAS_HTTP_PORT / PAS_MANAGEMENT_PORT in .env).
api_port=$(docker compose port pas-policy-service 8080) || fail "pas-policy-service is not running"
mgmt_port=$(docker compose port pas-policy-service 8081) || fail "pas-policy-service is not running"
API="http://localhost:${api_port##*:}"
MGMT="http://localhost:${mgmt_port##*:}"

for probe in liveness readiness; do
  body=$(curl -fsS "$MGMT/actuator/health/$probe") || fail "$probe probe not reachable"
  grep -q '"UP"' <<<"$body" || fail "$probe probe is not UP: $body"
  echo "smoke: $probe UP"
done

# Matchers read the whole stream (no -q / -m1) so an early exit cannot break the pipe under pipefail.
curl -fsS "$MGMT/actuator/prometheus" | grep 'application="pas-policy-service"' >/dev/null \
  || fail "prometheus metrics missing the application tag"
echo "smoke: prometheus metrics OK"

status=$(curl -s -o /dev/null -w '%{http_code}' "$API/api/v1/policies/POL-00000001")
[[ "$status" == "401" || "$status" == "403" ]] || fail "API is not deny-by-default (HTTP $status)"
echo "smoke: API deny-by-default OK (HTTP $status)"

docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d pas -tAc "select version from flyway_schema_history where success order by installed_rank"' \
  | grep -x 1 >/dev/null || fail "Flyway V1 not applied"
echo "smoke: Flyway V1 applied"

docker compose logs --no-log-prefix pas-policy-service | grep '^{"@timestamp"' >/dev/null \
  || fail "pas-policy-service logs are not structured JSON"
echo "smoke: structured JSON logs OK"
echo "smoke: all checks passed"
