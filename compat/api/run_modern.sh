#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
make dev-env

cd "$ROOT/pas-platform"
docker compose up -d --wait postgres

eval "$(
  docker compose config --format json |
    python3 -c '
import json
import shlex
import sys

config = json.load(sys.stdin)
postgres = config["services"]["postgres"]
service = config["services"]["pas-policy-service"]
environment = postgres.get("environment", {})
service_environment = service.get("environment", {})
published = postgres["ports"][0]["published"]
values = {
    "PAS_DB_USERNAME": environment["POSTGRES_USER"],
    "PAS_DB_PASSWORD": environment["POSTGRES_PASSWORD"],
    "PAS_DB_HOST_PORT": str(published),
    "PAS_PII_ENCRYPTION_KEY_ID": service_environment["PAS_PII_ENCRYPTION_KEY_ID"],
    "PAS_PII_ENCRYPTION_KEY_VERSION": service_environment["PAS_PII_ENCRYPTION_KEY_VERSION"],
    "PAS_PII_ENCRYPTION_KEY": service_environment["PAS_PII_ENCRYPTION_KEY"],
    "PAS_PII_BLIND_INDEX_KEY": service_environment["PAS_PII_BLIND_INDEX_KEY"],
}
for name, value in values.items():
    print("export " + name + "=" + shlex.quote(str(value)))
'
)"

PORT="${COMPAT_MODERN_PORT:-18095}"
PAS_DB_URL="jdbc:postgresql://localhost:${PAS_DB_HOST_PORT:-5432}/pas"
PAS_DEV_JWT_PUBLIC_KEY="$ROOT/pas-platform/.dev-keys/public.pem"
PAS_ROUTE_POLICY_INQUIRY=new
PAS_DEV_SEED_FACADE_DATA=true
export PAS_DB_URL PAS_DEV_JWT_PUBLIC_KEY PAS_ROUTE_POLICY_INQUIRY PAS_DEV_SEED_FACADE_DATA

"$ROOT/pas-platform/mvnw" -B -ntp -DskipTests -pl pas-policy-service -am package
JAR="$ROOT/pas-platform/pas-policy-service/target/pas-policy-service-0.1.0-SNAPSHOT.jar"
mkdir -p "$ROOT/logs"
java -jar "$JAR" --spring.profiles.active=local --server.port="$PORT" \
  --pas.dev.seed-facade-data=true --pas.routing.policy-inquiry=new \
  > "$ROOT/logs/compat-api-modern.log" 2>&1 &
PID=$!
trap 'kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true' EXIT

READY=false
for _ in $(seq 1 90); do
  if curl -fsS "http://localhost:$PORT/actuator/health/readiness" >/dev/null; then
    READY=true
    break
  fi
  kill -0 "$PID" 2>/dev/null || break
  sleep 2
done
if [[ "$READY" != true ]]; then
  printf 'Modern policy service did not become ready; see ignored logs/compat-api-modern.log\n' >&2
  exit 1
fi

jwt="$("$ROOT/pas-platform/dev/dev-jwt.sh" mint --scope policy:read --claim book=ALL)"
COMPAT_AUTH_HEADER="Authorization: Bearer $jwt" \
  python3 "$ROOT/compat/api/check_api.py" --base-url "http://localhost:$PORT"
