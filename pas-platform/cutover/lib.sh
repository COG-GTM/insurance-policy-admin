#!/usr/bin/env bash
set -Eeuo pipefail

CUTOVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM_DIR="$(cd "$CUTOVER_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PLATFORM_DIR/.." && pwd)"
export REPO_ROOT
export PAS_CUTOVER_ENV="${PAS_CUTOVER_ENV:-production}"
export PAS_CUTOVER_STATE_DIR="${PAS_CUTOVER_STATE_DIR:-$HOME/ab530/cutover-state/$PAS_CUTOVER_ENV}"
export PAS_CUTOVER_RUN_ID="${PAS_CUTOVER_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
export PAS_CUTOVER_RUN_KIND="${PAS_CUTOVER_RUN_KIND:-cutover}"
export PAS_CUTOVER_EXECUTE=false
export PAS_CUTOVER_CHECK_PREREQS=false
export PAS_CUTOVER_INCLUDE_READS=false
PAS_CUTOVER_DECISION_TIME="${PAS_CUTOVER_DECISION_TIME:-}"
PAS_CUTOVER_CURRENT_STEP=''
PAS_CUTOVER_STEP_STARTED=''
if [[ "$PAS_CUTOVER_ENV" == "local" ]]; then
  export PAS_CUTOVER_POLICY_URL="${PAS_CUTOVER_POLICY_URL:-http://localhost:${PAS_POLICY_HTTP_PORT:-18080}}"
  export PAS_CUTOVER_BATCH_URL="${PAS_CUTOVER_BATCH_URL:-http://localhost:${PAS_BATCH_HTTP_PORT:-18081}}"
fi

if [[ ! "$PAS_CUTOVER_RUN_ID" =~ ^[A-Za-z0-9_.-]+$ ]]; then
  printf 'Invalid run id; use letters, numbers, dot, underscore, or hyphen.\n' >&2
  exit 2
fi

mkdir -p "$PAS_CUTOVER_STATE_DIR"
PAS_CUTOVER_LOG="${PAS_CUTOVER_LOG:-$PAS_CUTOVER_STATE_DIR/${PAS_CUTOVER_RUN_KIND}-$(date -u +%Y%m%dT%H%M%SZ).log}"
touch "$PAS_CUTOVER_LOG"

log() {
  local stamp line
  stamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  line="[$stamp] [${PAS_CUTOVER_RUN_KIND}] $*"
  printf '%s\n' "$line" | tee -a "$PAS_CUTOVER_LOG"
}

usage() {
  printf 'Usage: %s [--execute] [--run-id ID] [--check-prereqs] [--include-reads] [--decision-time ISO]\n' \
    "${0##*/}"
}

parse_common_args() {
  while (($#)); do
    case "$1" in
      --execute) PAS_CUTOVER_EXECUTE=true ;;
      --run-id)
        (($# >= 2)) || { usage >&2; exit 2; }
        PAS_CUTOVER_RUN_ID="$2"
        shift
        ;;
      --resume)
        (($# >= 2)) || { usage >&2; exit 2; }
        PAS_CUTOVER_RUN_ID="$2"
        shift
        ;;
      --check-prereqs) PAS_CUTOVER_CHECK_PREREQS=true ;;
      --include-reads) PAS_CUTOVER_INCLUDE_READS=true ;;
      --decision-time)
        (($# >= 2)) || { usage >&2; exit 2; }
        PAS_CUTOVER_DECISION_TIME="$2"
        shift
        ;;
      --help|-h) usage; exit 0 ;;
      *) printf 'Unknown argument: %s\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
    shift
  done
  if [[ ! "$PAS_CUTOVER_RUN_ID" =~ ^[A-Za-z0-9_.-]+$ ]]; then
    printf 'Invalid run id; use letters, numbers, dot, underscore, or hyphen.\n' >&2
    exit 2
  fi
}

cutover_error() {
  local status=$?
  trap - ERR
  if [[ -n "$PAS_CUTOVER_CURRENT_STEP" ]]; then
    local elapsed=$(( $(date +%s) - PAS_CUTOVER_STEP_STARTED ))
    log "FAIL step $PAS_CUTOVER_CURRENT_STEP duration=${elapsed}s status=$status"
  else
    log "FAIL command exited with status $status"
  fi
  exit "$status"
}
trap cutover_error ERR

done_file() {
  printf '%s/%s-%s.done' "$PAS_CUTOVER_STATE_DIR" "$PAS_CUTOVER_RUN_KIND" "$PAS_CUTOVER_RUN_ID"
}

step() {
  local id="$1" description="$2" function_name="$3" display_command="$4"
  local started ended elapsed status
  if [[ "$PAS_CUTOVER_EXECUTE" != "true" ]]; then
    log "DRY-RUN step $id $description: would run $display_command"
    return 0
  fi

  if [[ -f "$(done_file)" ]] && rg -Fxq "$id" "$(done_file)"; then
    log "SKIP step $id $description: already completed for run id $PAS_CUTOVER_RUN_ID"
    return 0
  fi

  started="$(date +%s)"
  PAS_CUTOVER_CURRENT_STEP="$id $description"
  PAS_CUTOVER_STEP_STARTED="$started"
  log "START step $id $description"
  "$function_name"
  status=$?
  trap cutover_error ERR
  set -e
  if ((status == 99)); then
    log "SKIP step $id $description: not applicable"
    PAS_CUTOVER_CURRENT_STEP=''
    PAS_CUTOVER_STEP_STARTED=''
    return 0
  fi
  if ((status != 0)); then
    return "$status"
  fi
  ended="$(date +%s)"
  elapsed=$((ended - started))
  printf '%s\n' "$id" >>"$(done_file)"
  log "OK step $id $description duration=${elapsed}s"
  PAS_CUTOVER_CURRENT_STEP=''
  PAS_CUTOVER_STEP_STARTED=''
}

step_not_applicable() {
  trap - ERR
  set +e
  return 99
}

assert_execute_environment() {
  if [[ "$PAS_CUTOVER_EXECUTE" != "true" ]]; then
    return 0
  fi
  if [[ "$PAS_CUTOVER_ENV" == "local" ]]; then
    # shellcheck disable=SC1091
    source "$PLATFORM_DIR/dev/rehearsal-lib.sh"
    prepare_cutover_rehearsal_environment
    return
  fi
  local hook
  local missing=()
  for hook in \
    PAS_CUTOVER_PSQL_CMD PAS_CUTOVER_BATCH_CMD PAS_CUTOVER_APPLY_ROUTES_CMD \
    PAS_CUTOVER_LEGACY_FREEZE_CMD PAS_CUTOVER_LEGACY_UNFREEZE_CMD \
    PAS_CUTOVER_MIGRATION_CONFIG_CMD PAS_CUTOVER_POLICY_URL PAS_CUTOVER_BATCH_URL; do
    if [[ -z "${!hook:-}" ]]; then
      missing+=("$hook")
    fi
  done
  if ((${#missing[@]})); then
    printf 'Refusing execute: missing required hooks: %s\n' "${missing[*]}" >&2
    exit 2
  fi
  if [[ -z "${PAS_SMOKE_JWT:-}" ]]; then
    printf 'Refusing execute: PAS_SMOKE_JWT is required outside local rehearsal mode.\n' >&2
    exit 2
  fi
}

cutover_sql() {
  local sql="$1"
  if [[ "$PAS_CUTOVER_ENV" == "local" ]]; then
    printf '%s\n' "$sql" | docker compose --profile migration exec -T rollback-target \
      psql -U "$PAS_DB_USERNAME" -d pas -v ON_ERROR_STOP=1 -Atq
  else
    printf '%s\n' "$sql" | bash -c "$PAS_CUTOVER_PSQL_CMD"
  fi
}

cutover_sql_file() {
  local sql_file="$1"
  if [[ "$PAS_CUTOVER_ENV" == "local" ]]; then
    docker compose --profile migration exec -T rollback-target \
      psql -U "$PAS_DB_USERNAME" -d pas -v ON_ERROR_STOP=1 -Atq <"$sql_file"
  else
    bash -c "$PAS_CUTOVER_PSQL_CMD" <"$sql_file"
  fi
}

run_hook() {
  local command_prefix="$1"
  shift
  bash -c "$command_prefix \"\$@\"" pas-cutover-hook "$@"
}

cutover_batch() {
  if [[ "$PAS_CUTOVER_ENV" == "local" ]]; then
    run_cli "$@"
  else
    run_hook "$PAS_CUTOVER_BATCH_CMD" "$@"
  fi
}

cutover_apply_routes() {
  if [[ "$PAS_CUTOVER_ENV" != "local" ]]; then
    run_hook "$PAS_CUTOVER_APPLY_ROUTES_CMD" "$@"
    return
  fi
  local assignment
  for assignment in "$@"; do
    case "$assignment" in
      PAS_ROUTE_POLICY_INQUIRY=*|PAS_ROUTE_NEW_BUSINESS=*|PAS_ROUTE_UNDERWRITING=*|\
      PAS_ROUTE_RENEWAL=*|PAS_ROUTE_ENDORSEMENT=*|PAS_ROUTE_DOCUMENT_EVENTS=*|\
      PAS_ROUTE_BROKER_PORTAL_READ=*|PAS_ROUTING_PREMIUM_BATCH=*|\
      PAS_ROUTING_DAILY_EXTRACT=*|PAS_ROUTING_MONTHLY_EXPOSURE=*)
        export "${assignment?}"
        ;;
      *) printf 'Unsupported route assignment: %s\n' "$assignment" >&2; return 2 ;;
    esac
  done
  docker compose --profile migration up -d --wait --no-deps --force-recreate \
    pas-policy-service pas-batch
}

cutover_migration_config() {
  if [[ "$PAS_CUTOVER_ENV" != "local" ]]; then
    run_hook "$PAS_CUTOVER_MIGRATION_CONFIG_CMD" "$@"
    return
  fi
  local assignment
  for assignment in "$@"; do
    case "$assignment" in
      PAS_MIGRATION_DELTA_SYNC_PAUSED=*|PAS_MIGRATION_REVERSE_SYNC_ENABLED=*)
        export "${assignment?}"
        ;;
      *) printf 'Unsupported migration setting: %s\n' "$assignment" >&2; return 2 ;;
    esac
  done
  docker compose --profile migration up -d --wait --no-deps --force-recreate \
    pas-policy-service pas-batch
}

cutover_legacy_freeze() {
  if [[ "$PAS_CUTOVER_ENV" != "local" ]]; then
    bash -c "$PAS_CUTOVER_LEGACY_FREEZE_CMD"
    return
  fi
  source_psql "DO \$\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'acmeins_cics') THEN CREATE ROLE acmeins_cics NOLOGIN; END IF; END \$\$"
  source_psql "GRANT INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA acmeins TO acmeins_cics"
  source_psql "REVOKE INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA acmeins FROM acmeins_cics"
  [[ "$(source_psql "SELECT has_table_privilege('acmeins_cics', 'acmeins.policies', 'UPDATE')")" == "f" ]]
}

cutover_legacy_unfreeze() {
  if [[ "$PAS_CUTOVER_ENV" != "local" ]]; then
    bash -c "$PAS_CUTOVER_LEGACY_UNFREEZE_CMD"
    return
  fi
  source_psql "GRANT INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA acmeins TO acmeins_cics"
  [[ "$(source_psql "SELECT has_table_privilege('acmeins_cics', 'acmeins.policies', 'UPDATE')")" == "t" ]]
}

route_value() {
  local service="$1" key="$2" file url
  file="$PAS_CUTOVER_STATE_DIR/${service}-actuator-info.json"
  local jwt="${PAS_SMOKE_JWT:-}"
  if [[ -z "$jwt" && "$PAS_CUTOVER_ENV" == "local" ]]; then
    jwt="$(mint_rehearsal_jwt ab530-route-check)"
  fi
  if [[ "$service" == "policy" ]]; then
    url="${PAS_CUTOVER_POLICY_URL:-}"
  else
    url="${PAS_CUTOVER_BATCH_URL:-}"
  fi
  if [[ -z "$url" ]]; then
    printf 'Route checks require the %s service URL.\n' "$service" >&2
    return 2
  fi
  curl -fsS -H "Authorization: Bearer $jwt" \
    "$url/actuator/info" -o "$file"
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["routes"][sys.argv[2]])' \
    "$file" "$key"
}

assert_route() {
  local service="$1" key="$2" expected="$3" actual
  actual="$(route_value "$service" "$key")"
  if [[ "$actual" != "$expected" ]]; then
    printf 'Route %s on %s expected %s, got %s.\n' "$key" "$service" "$expected" "$actual" >&2
    return 1
  fi
}
