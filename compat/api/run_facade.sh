#!/usr/bin/env bash
# Build and start the read API with seeded local data, then run the contract check.
# Override COMPAT_API_MODULE / COMPAT_API_START when a replacement service takes over.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
MODULE="${COMPAT_API_MODULE:-java-facade}"
PORT="${COMPAT_PORT:-18080}"
# Local H2 profile credentials for the REST contract gate.
export COMPAT_AUTH_HEADER="${COMPAT_AUTH_HEADER:-Authorization: Basic $(printf 'local-admin:local-dev-password' | base64)}"
(cd "$MODULE" && mvn -B -ntp -q -DskipTests package)
JAR=$(ls "$MODULE"/target/*.jar | grep -v original | head -1)
START="${COMPAT_API_START:-java -jar $JAR --spring.profiles.active=local --server.port=$PORT}"
$START > compat-api.log 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null || true' EXIT
for _ in $(seq 1 90); do
  curl -s -o /dev/null "http://localhost:$PORT/api/v1/policies/POL-00000001" && break
  kill -0 $PID 2>/dev/null || { cat compat-api.log; exit 1; }
  sleep 2
done
python3 compat/api/check_api.py --base-url "http://localhost:$PORT" "$@"
