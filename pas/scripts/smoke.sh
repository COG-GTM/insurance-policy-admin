#!/usr/bin/env bash
# Smoke test of the running local stack (docker compose): probes, metrics, deny-by-default API, JSON logs.
set -euo pipefail

API="http://localhost:${PAS_HTTP_PORT:-8080}"
MGMT="http://localhost:${PAS_MANAGEMENT_PORT:-8081}"
fail() { echo "smoke: FAIL - $*" >&2; exit 1; }

for probe in liveness readiness; do
  body=$(curl -fsS "$MGMT/actuator/health/$probe") || fail "$probe probe not reachable"
  grep -q '"UP"' <<<"$body" || fail "$probe probe is not UP: $body"
  echo "smoke: $probe UP"
done

curl -fsS "$MGMT/actuator/prometheus" | grep -q 'application="pas-policy-service"' \
  || fail "prometheus metrics missing the application tag"
echo "smoke: prometheus metrics OK"

status=$(curl -s -o /dev/null -w '%{http_code}' "$API/api/v1/policies/POL-00000001")
[[ "$status" == "401" || "$status" == "403" ]] || fail "API is not deny-by-default (HTTP $status)"
echo "smoke: API deny-by-default OK (HTTP $status)"

docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d pas -tAc "select version from flyway_schema_history where success order by installed_rank"' \
  | grep -qx 1 || fail "Flyway V1 not applied"
echo "smoke: Flyway V1 applied"

docker compose logs --no-log-prefix pas-policy-service | grep -m1 '^{"@timestamp"' >/dev/null \
  || fail "pas-policy-service logs are not structured JSON"
echo "smoke: structured JSON logs OK"
echo "smoke: all checks passed"
