#!/usr/bin/env bash
# AB-529 local helpers shared by reconcile-local.sh and rollback-rehearsal.sh. Local Compose only;
# the DB2 stand-in is a PostgreSQL schema (acmeins_src) seeded from the java-facade ACMEINS seed.
set -euo pipefail

PAS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REPO_ROOT="$(cd "$PAS_DIR/.." && pwd)"
COMPOSE=(docker compose -f "$PAS_DIR/docker-compose.yml")
OUT_DIR="${PAS_MIGRATION_LOCAL_OUT:-$PAS_DIR/target/migration-local}"
SOURCE_SCHEMA=acmeins_src

ensure_env() {
  if [ ! -f "$PAS_DIR/.env" ]; then
    cp "$PAS_DIR/.env.example" "$PAS_DIR/.env"
    echo "Created $PAS_DIR/.env from .env.example (local-only defaults)"
  fi
  for key in PAS_PII_ENCRYPTION_KEY PAS_PII_BLIND_INDEX_KEY; do
    if ! grep -Eq "^${key}=.+" "$PAS_DIR/.env"; then
      # Throwaway local key in the gitignored .env; never used outside this dev stack.
      sed -i "/^${key}=/d" "$PAS_DIR/.env"
      echo "${key}=$(openssl rand -base64 32)" >> "$PAS_DIR/.env"
      echo "Generated local ${key} in $PAS_DIR/.env"
    fi
  done
  # Like Compose: shell variables win over .env; quoted values verbatim, unquoted values trimmed
  # and cut at an inline " #" comment (same rules as dev/dev-jwt.sh).
  local line name val
  while IFS= read -r line || [ -n "$line" ]; do
    [[ "$line" =~ ^[[:space:]]*(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*=(.*)$ ]] || continue
    name="${BASH_REMATCH[2]}"
    val="${BASH_REMATCH[3]}"
    val="${val#"${val%%[![:space:]]*}"}"
    case "$val" in
      \"*) val="${val#\"}"; val="${val%%\"*}" ;;
      \'*) val="${val#\'}"; val="${val%%\'*}" ;;
      *) val="${val%%[[:space:]]#*}"; val="${val%"${val##*[![:space:]]}"}" ;;
    esac
    if [ -z "${!name+x}" ]; then
      export "$name=$val"
    fi
  done < "$PAS_DIR/.env"
}

stack_up() {
  "$PAS_DIR/dev/dev-jwt.sh" keygen >/dev/null
  # pas-policy-service applies the pas Flyway migrations (V1..V7) on start.
  # Rebuild so the image always carries the current migrations, not a stale V-set. The Dockerfile
  # reads Maven settings (mirror) from the optional maven_settings build secret.
  if [ -f "$HOME/.m2/settings.xml" ]; then
    docker build -q --secret id=maven_settings,src="$HOME/.m2/settings.xml" \
      --build-arg MVNW_REPOURL="${MVNW_REPOURL:-}" \
      -f "$PAS_DIR/pas-policy-service/Dockerfile" -t pas-policy-service:local "$PAS_DIR" >/dev/null
  else
    "${COMPOSE[@]}" build pas-policy-service
  fi
  "${COMPOSE[@]}" up -d --wait --wait-timeout 300 postgres pas-policy-service
}

psql_pas() {
  "${COMPOSE[@]}" exec -T postgres psql -U "${PAS_DB_USERNAME:-pas}" -d pas -v ON_ERROR_STOP=1 -q "$@"
}

seed_source() {
  python3 "$PAS_DIR/dev/migration/acmeins_seed.py" --dialect postgres --schema "$SOURCE_SCHEMA" \
    | psql_pas >/dev/null
}

batch_jar() {
  local jar
  jar=$(ls "$PAS_DIR"/pas-batch/target/pas-batch-*.jar 2>/dev/null | grep -v plain | head -1 || true)
  if [ -z "$jar" ] || [ -n "${PAS_REBUILD:-}" ]; then
    (cd "$PAS_DIR" && ./mvnw -B -ntp -q -pl pas-batch -am package -DskipTests -Dspotbugs.skip) >&2
    jar=$(ls "$PAS_DIR"/pas-batch/target/pas-batch-*.jar | grep -v plain | head -1)
  fi
  echo "$jar"
}

# migration_cli <commands> : runs the pas-batch CLI profile on the host against the Compose DB.
migration_cli() {
  local pg_port db_url
  pg_port=$("${COMPOSE[@]}" port postgres 5432 | awk -F: '{print $NF}')
  db_url="jdbc:postgresql://127.0.0.1:${pg_port}/pas"
  mkdir -p "$OUT_DIR"
  PAS_DB_URL="$db_url" \
  PAS_MIGRATION_ENABLED=true \
  PAS_MIGRATION_SOURCE_URL="$db_url" \
  PAS_MIGRATION_SOURCE_USERNAME="${PAS_DB_USERNAME:-pas}" \
  PAS_MIGRATION_SOURCE_PASSWORD="${PAS_DB_PASSWORD}" \
  PAS_MIGRATION_SOURCE_SCHEMA="$SOURCE_SCHEMA" \
  PAS_MIGRATION_REPORT_DIR="$OUT_DIR/reports" \
  PAS_MIGRATION_LEGACY_OUTPUT_DIR="$OUT_DIR/legacy-outputs" \
  PAS_EXTRACT_OUTPUT_DIR="$OUT_DIR/extracts" \
  PAS_MIGRATION_TRIAGE_REGISTER="$REPO_ROOT/parity/parallel-run/triage-register.json" \
  PAS_MIGRATION_QUIRK_REGISTER="$REPO_ROOT/docs/modernization/quirk-register.md" \
  PAS_TRANSFER_ENABLED=false \
  JAVA_HOME="${JAVA_HOME_21_X64:-${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}}" \
    "${JAVA_HOME_21_X64:-${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}}/bin/java" \
    -jar "$(batch_jar)" \
    --spring.main.web-application-type=none \
    --pas.migration.cli="$1" \
    --pas.migration.business-date="${BUSINESS_DATE:-$(date +%F)}" >"$OUT_DIR/cli-$(echo "$1" | tr ',' '_').log" 2>&1
}

# batch_cli <args...> : one-shot pas-batch CLI (premium run, extract jobs) on the host against the
# Compose DB, as the cutover runbook runs them after the flip (write target pas, no SFTP delivery).
batch_cli() {
  local pg_port java
  pg_port=$("${COMPOSE[@]}" port postgres 5432 | awk -F: '{print $NF}')
  java="${JAVA_HOME_21_X64:-${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}}/bin/java"
  mkdir -p "$OUT_DIR"
  PAS_DB_URL="jdbc:postgresql://127.0.0.1:${pg_port}/pas" \
  PAS_MIGRATION_ENABLED=false \
  PAS_TRANSFER_ENABLED=false \
  PAS_SCHEDULE_ENABLED=false \
  PAS_BATCH_PREMIUM_WRITE_TARGET="${PAS_BATCH_PREMIUM_WRITE_TARGET:-pas}" \
  PAS_BATCH_REPORT_DIR="$OUT_DIR/premium-reports" \
  PAS_EXTRACT_OUTPUT_DIR="${PAS_EXTRACT_OUTPUT_DIR:-$OUT_DIR/extracts}" \
    "$java" -jar "$(batch_jar)" --spring.main.web-application-type=none "$@"
}
