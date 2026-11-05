#!/usr/bin/env bash
# Runs the facade REST contract (compat/api/check_api.py, snapshots recorded from java-facade)
# against the running local stack: loads the facade seed rows (fictional) into the local database,
# then checks with a whole-book read token. Local stack only; never point it at a shared database.
set -euo pipefail
cd "$(dirname "$0")/../.."

fail() { echo "api-contract: FAIL - $*" >&2; exit 1; }
api_port=$(docker compose port pas-policy-service 8080) || fail "pas-policy-service is not running"
docker compose exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d pas' \
  < pas/pas-policy-service/src/test/resources/contract/facade-seed.sql >/dev/null \
  || fail "could not load the facade seed data"
jwt=$(pas/scripts/local-token.sh "pas.policy.read" '"pas_book":"*"')
COMPAT_AUTH_HEADER="Authorization: Bearer $jwt" \
  python3 compat/api/check_api.py --base-url "http://localhost:${api_port##*:}"
