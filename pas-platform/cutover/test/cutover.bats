#!/usr/bin/env bats

setup() {
  CUTOVER_DIR="$(cd "$BATS_TEST_DIRNAME/.." && pwd)"
  REPO_ROOT="$(cd "$CUTOVER_DIR/../.." && pwd)"
}

@test "cutover and rollback default to a dry run" {
  run "$CUTOVER_DIR/cutover.sh" --run-id bats-dry-run
  [ "$status" -eq 0 ]
  [[ "$output" == *"DRY-RUN"* ]]

  run "$CUTOVER_DIR/rollback.sh" --run-id bats-dry-run
  [ "$status" -eq 0 ]
  [[ "$output" == *"DRY-RUN"* ]]
}

@test "cutover rejects unknown arguments" {
  run "$CUTOVER_DIR/cutover.sh" --not-a-real-option
  [ "$status" -eq 2 ]
}

@test "resume requires an explicit run id" {
  run "$CUTOVER_DIR/cutover.sh" --resume
  [ "$status" -eq 2 ]
  [[ "$output" == *"--run-id"* ]]
}

@test "decommission defaults to a non-mutating dry run" {
  run "$CUTOVER_DIR/decommission-legacy-trees.sh"
  [ "$status" -eq 0 ]
  [[ "$output" == *"DRY-RUN"* ]]
}

@test "decommission refuses the active checkout without the explicit approval gate" {
  run "$CUTOVER_DIR/decommission-legacy-trees.sh" --execute --repo "$REPO_ROOT" --skip-tag-check
  [ "$status" -eq 1 ]
  [[ "$output" == *"Refusing the real checkout"* ]]
}

@test "decommission execute requires an explicit repo path" {
  run "$CUTOVER_DIR/decommission-legacy-trees.sh" --execute
  [ "$status" -eq 2 ]
  [[ "$output" == *"requires --repo"* ]]
}

@test "smoke test rejects execute outside local or configured adapters" {
  run env PAS_CUTOVER_ENV=production PAS_CUTOVER_EXECUTE=true \
    "$CUTOVER_DIR/smoke-test.sh"
  [ "$status" -eq 2 ]
}

@test "non-local execute reports every missing deployment hook" {
  run env PAS_CUTOVER_ENV=staging "$CUTOVER_DIR/cutover.sh" --execute
  [ "$status" -eq 2 ]
  [[ "$output" == *"PAS_CUTOVER_PSQL_CMD"* ]]
  [[ "$output" == *"PAS_CUTOVER_MIGRATION_CONFIG_CMD"* ]]
  [[ "$output" == *"PAS_CUTOVER_POLICY_URL"* ]]
  [[ "$output" == *"PAS_CUTOVER_BATCH_URL"* ]]
}

@test "non-local cutover refuses missing smoke customer before running any step" {
  hook_dir="$(mktemp -d "$BATS_TMPDIR/cutover-smoke-gate.XXXXXX")"
  hook="$hook_dir/fake-hook"
  invoked="$hook_dir/invoked"
  printf '#!/usr/bin/env bash\n: >"$HOOK_MARKER"\n' >"$hook"
  chmod +x "$hook"

  run env PAS_CUTOVER_ENV=staging PAS_CUTOVER_STATE_DIR="$hook_dir/state" \
    PAS_CUTOVER_PSQL_CMD="$hook" PAS_CUTOVER_BATCH_CMD="$hook" \
    PAS_CUTOVER_APPLY_ROUTES_CMD="$hook" PAS_CUTOVER_LEGACY_FREEZE_CMD="$hook" \
    PAS_CUTOVER_LEGACY_UNFREEZE_CMD="$hook" PAS_CUTOVER_MIGRATION_CONFIG_CMD="$hook" \
    PAS_CUTOVER_POLICY_URL=https://policy.example PAS_CUTOVER_BATCH_URL=https://batch.example \
    PAS_SMOKE_POLICY=POLICY PAS_SMOKE_CUSTOMER= PAS_SMOKE_JWT=placeholder \
    HOOK_MARKER="$invoked" "$CUTOVER_DIR/cutover.sh" --execute
  [ "$status" -eq 2 ]
  [[ "$output" == *"PAS_SMOKE_CUSTOMER"* ]]
  [[ "$output" != *"PAS_CUTOVER_PSQL_CMD"* ]]
  [[ "$output" != *"START step c01"* ]]
  [ ! -e "$invoked" ]
}

@test "production hook command prefixes receive job, route, and config arguments" {
  hook_dir="$BATS_TMPDIR/hooks-$BATS_TEST_NUMBER"
  mkdir -p "$hook_dir"
  hook="$hook_dir/record-args"
  args_file="$hook_dir/args"
  : >"$args_file"
  printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$@" >>"$HOOK_ARGS_FILE"\n' >"$hook"
  chmod +x "$hook"

  run env PAS_CUTOVER_ENV=production \
    PAS_CUTOVER_BATCH_CMD="$hook" PAS_CUTOVER_APPLY_ROUTES_CMD="$hook" \
    PAS_CUTOVER_MIGRATION_CONFIG_CMD="$hook" HOOK_ARGS_FILE="$args_file" \
    PAS_CUTOVER_STATE_DIR="$hook_dir/state" \
    bash -c '
      source "$1/lib.sh"
      cutover_batch --pas.batch.cli.job=deltaSyncJob
      cutover_apply_routes PAS_ROUTE_NEW_BUSINESS=new
      cutover_migration_config PAS_MIGRATION_DELTA_SYNC_PAUSED=true
    ' _ "$CUTOVER_DIR"
  [ "$status" -eq 0 ]
  mapfile -t hook_args <"$args_file"
  [ "${#hook_args[@]}" -eq 3 ]
  [ "${hook_args[0]}" = "--pas.batch.cli.job=deltaSyncJob" ]
  [ "${hook_args[1]}" = "PAS_ROUTE_NEW_BUSINESS=new" ]
  [ "${hook_args[2]}" = "PAS_MIGRATION_DELTA_SYNC_PAUSED=true" ]
}

@test "step logs include timestamps and failures stop the run" {
  state="$BATS_TMPDIR/step-state-$BATS_TEST_NUMBER"
  run bash -c '
    set -Eeuo pipefail
    export PAS_CUTOVER_STATE_DIR="$1"
    source "$2/lib.sh"
    PAS_CUTOVER_EXECUTE=true
    fail_step() { return 17; }
    step c01 failing fail_step "fail intentionally"
    step c02 unreachable fail_step "must not run"
  ' _ "$state" "$CUTOVER_DIR"
  [ "$status" -eq 17 ]
  [[ "$output" =~ \[[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z\].*START\ step\ c01 ]]
  [[ "$output" == *"FAIL step c01 failing"* ]]
  [[ "$output" != *"START step c02"* ]]
}

@test "completed steps are skipped when resumed with the same run id" {
  state="$(mktemp -d "$BATS_TMPDIR/resume-state.XXXXXX")"
  count_file="$state/count"
  log_file="$state/cutover.log"
  run bash -c '
    set -Eeuo pipefail
    export PAS_CUTOVER_ENV=local PAS_CUTOVER_STATE_DIR="$1" PAS_CUTOVER_RUN_ID=test-run
    export PAS_CUTOVER_LOG="$4"
    source "$2/lib.sh"
    count_file="$3"
    count_step() { printf x >>"$count_file"; }
    PAS_CUTOVER_EXECUTE=true
    step c01 counted count_step "count"
    step c01 counted count_step "count"
  ' _ "$state" "$CUTOVER_DIR" "$count_file" "$log_file"
  [ "$status" -eq 0 ]
  [[ "$(cat "$log_file")" == *"already completed"* ]]
  [ "$(cat "$count_file")" = "x" ]
}

@test "rollback r05 can be resumed with include-reads after a not-applicable skip" {
  state="$(mktemp -d "$BATS_TMPDIR/rollback-read-state.XXXXXX")"
  calls="$state/routes-applied"
  run bash -c '
    set -Eeuo pipefail
    export PAS_CUTOVER_ENV=local PAS_CUTOVER_STATE_DIR="$1" PAS_CUTOVER_RUN_ID=read-resume
    source "$2/rollback.sh"
    PAS_CUTOVER_EXECUTE=true
    calls="$3"
    cutover_apply_routes() { printf "%s\n" "$*" >>"$calls"; }
    assert_route() { :; }
    step r05 "optionally route reads back" r05_read_routes_legacy "read routes"
    PAS_CUTOVER_INCLUDE_READS=true
    step r05 "optionally route reads back" r05_read_routes_legacy "read routes"
  ' _ "$state" "$CUTOVER_DIR" "$calls"
  [ "$status" -eq 0 ]
  [[ "$output" == *"SKIP step r05 optionally route reads back: not applicable"* ]]
  [[ "$output" != *"already completed for run id read-resume"* ]]
  mapfile -t applied <"$calls"
  [ "${#applied[@]}" -eq 4 ]
  [[ "${applied[0]}" == "PAS_ROUTE_BROKER_PORTAL_READ=legacy" ]]
  [[ "${applied[3]}" == "PAS_ROUTING_MONTHLY_EXPOSURE=legacy" ]]
}

@test "Flyway comparison accepts exact history and rejects failed, pending, and unknown versions" {
  state="$BATS_TMPDIR/flyway-state-$BATS_TEST_NUMBER"
  mkdir -p "$state"
  printf '1\n2\n' >"$state/expected"
  printf '1|true\n2|true\n' >"$state/applied"
  run bash -c '
    export PAS_CUTOVER_STATE_DIR="$1"
    source "$2/lib.sh"
    source "$2/flyway-check.sh"
    check_versions "$1/expected" "$1/applied"
  ' _ "$state" "$CUTOVER_DIR"
  [ "$status" -eq 0 ]

  printf '1|t\n2|t\n' >"$state/applied"
  run bash -c 'export PAS_CUTOVER_STATE_DIR="$1"; source "$2/lib.sh"; source "$2/flyway-check.sh"; check_versions "$1/expected" "$1/applied"' _ "$state" "$CUTOVER_DIR"
  [ "$status" -eq 0 ]

  printf '1|true\n2|false\n' >"$state/applied"
  run bash -c 'export PAS_CUTOVER_STATE_DIR="$1"; source "$2/lib.sh"; source "$2/flyway-check.sh"; check_versions "$1/expected" "$1/applied"' _ "$state" "$CUTOVER_DIR"
  [ "$status" -ne 0 ]

  printf '2\n' >"$state/applied"
  run bash -c 'export PAS_CUTOVER_STATE_DIR="$1"; source "$2/lib.sh"; source "$2/flyway-check.sh"; check_versions "$1/expected" "$1/applied"' _ "$state" "$CUTOVER_DIR"
  [ "$status" -ne 0 ]
  [[ "$output" == *"outOfOrder is not enabled"* ]]

  printf '1|true\n2|true\n3|true\n' >"$state/applied"
  run bash -c 'export PAS_CUTOVER_STATE_DIR="$1"; source "$2/lib.sh"; source "$2/flyway-check.sh"; check_versions "$1/expected" "$1/applied"' _ "$state" "$CUTOVER_DIR"
  [ "$status" -ne 0 ]
  [[ "$output" == *"unknown applied Flyway version 3"* ]]
}

@test "production cutover requires dedicated smoke policy and customer fixtures" {
  run env PAS_CUTOVER_ENV=staging PAS_CUTOVER_POLICY_URL=https://policy.example \
    PAS_CUTOVER_BATCH_URL=https://batch.example PAS_SMOKE_JWT=placeholder \
    "$CUTOVER_DIR/smoke-test.sh"
  [ "$status" -eq 2 ]
  [[ "$output" == *"PAS_SMOKE_POLICY"* ]]
  [[ "$output" == *"PAS_SMOKE_CUSTOMER"* ]]
}

@test "parallel-run cutover gate rejects a running cycle with no differences" {
  state="$BATS_TMPDIR/parallel-state-$BATS_TEST_NUMBER"
  run env PAS_CUTOVER_ENV=staging PAS_CUTOVER_STATE_DIR="$state" \
    bash -c '
      source "$1/cutover.sh"
      cutover_sql() {
        case "$1" in
          *"SELECT cycle_id FROM"*) printf "cycle-running\n" ;;
          *"SELECT status FROM"*) printf "RUNNING\n" ;;
          *"SELECT count(*) FROM"*) printf "0\n" ;;
          *) return 2 ;;
        esac
      }
      c03_parallel_run
    ' _ "$CUTOVER_DIR"
  [ "$status" -ne 0 ]
  [[ "$output" == *"expected CLEAN"* ]]
}

@test "coverage drain waits for a full post-freeze cursor traversal across multiple pages" {
  state="$BATS_TMPDIR/coverage-drain-state-$BATS_TEST_NUMBER"
  mkdir -p "$state"
  psql="$state/fake-psql"
  batch="$state/fake-batch"
  config="$state/fake-config"
  cursor="$state/cursor"
  fresh="$state/fresh"
  lag="$state/lag"
  passes="$state/passes"
  config_args="$state/config-args"
  printf 'POL000002\n' >"$cursor"
  printf 'false\n' >"$fresh"
  printf '1\n' >"$lag"
  printf '0\n' >"$passes"
  cat >"$psql" <<'PSQL'
#!/usr/bin/env bash
set -eu
sql="$(cat)"
if [[ "$sql" == *coverage_cursor* && "$sql" == *"||"* ]]; then
  printf '%s|%s\n' "$(cat "$COVERAGE_CURSOR_FILE")" "$(cat "$COVERAGE_FRESH_FILE")"
elif [[ "$sql" == *coverage_cursor* ]]; then
  cat "$COVERAGE_CURSOR_FILE"
elif [[ "$sql" == *"SELECT count(*) FROM (VALUES"* ]]; then
  cat "$COVERAGE_LAG_FILE"
elif [[ "$sql" == *"SELECT count(*) FROM pas.policies"* ]]; then
  printf '1\n'
else
  printf 'Unexpected SQL: %s\n' "$sql" >&2
  exit 2
fi
PSQL
  cat >"$batch" <<'BATCH'
#!/usr/bin/env bash
set -eu
[[ "$*" == *"--pas.batch.cli.job=deltaSyncJob"* ]]
count="$(cat "$COVERAGE_PASSES_FILE")"
count=$((count + 1))
printf '%s\n' "$count" >"$COVERAGE_PASSES_FILE"
if ((count == 1)); then
  printf 'POL000003\n' >"$COVERAGE_CURSOR_FILE"
  printf '1\n' >"$COVERAGE_LAG_FILE"
elif ((count == 2)); then
  printf 'POL000001\n' >"$COVERAGE_CURSOR_FILE"
  printf '1\n' >"$COVERAGE_LAG_FILE"
else
  printf 'POL000002\n' >"$COVERAGE_CURSOR_FILE"
  printf '0\n' >"$COVERAGE_LAG_FILE"
fi
printf 'true\n' >"$COVERAGE_FRESH_FILE"
BATCH
  cat >"$config" <<'CONFIG'
#!/usr/bin/env bash
printf '%s\n' "$@" >"$COVERAGE_CONFIG_ARGS_FILE"
CONFIG
  chmod +x "$psql" "$batch" "$config"
  printf '%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"$state/freeze-test.timestamp"

  run env PAS_CUTOVER_ENV=production PAS_CUTOVER_STATE_DIR="$state" \
    PAS_CUTOVER_RUN_ID=test PAS_CUTOVER_DRAIN_MAX_PASSES=3 \
    PAS_CUTOVER_PSQL_CMD="$psql" PAS_CUTOVER_BATCH_CMD="$batch" \
    PAS_CUTOVER_MIGRATION_CONFIG_CMD="$config" \
    COVERAGE_CURSOR_FILE="$cursor" COVERAGE_FRESH_FILE="$fresh" \
    COVERAGE_LAG_FILE="$lag" COVERAGE_PASSES_FILE="$passes" \
    COVERAGE_CONFIG_ARGS_FILE="$config_args" \
    bash -c 'source "$1/cutover.sh"; c05_drain_delta' _ "$CUTOVER_DIR"
  [ "$status" -eq 0 ]
  [ "$(cat "$passes")" = "3" ]
  [[ "$output" == *"PASS forward delta watermark"* ]]
  [ "$(cat "$config_args")" = "PAS_MIGRATION_DELTA_SYNC_PAUSED=true" ]
}

@test "coverage drain completes after a fresh sweep when the source has no policies" {
  state="$(mktemp -d "$BATS_TMPDIR/empty-coverage-state.XXXXXX")"
  coverage_passes_file="$state/passes"
  config_args="$state/config-args"
  printf '0\n' >"$coverage_passes_file"
  printf '%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"$state/freeze-empty.timestamp"

  run bash -c '
    set -Eeuo pipefail
    export PAS_CUTOVER_ENV=local PAS_CUTOVER_STATE_DIR="$1" PAS_CUTOVER_RUN_ID=empty
    source "$2/cutover.sh"
    coverage_passes_file="$3"
    config_args="$4"
    cutover_sql() {
      if [[ "$1" == *coverage_cursor* && "$1" == *"||"* ]]; then
        printf "|true\n"
      elif [[ "$1" == *coverage_cursor* ]]; then
        printf "\n"
      elif [[ "$1" == *"SELECT count(*) FROM pas.policies"* ]]; then
        printf "0\n"
      elif [[ "$1" == *"VALUES"* ]]; then
        printf "0\n"
      else
        return 2
      fi
    }
    cutover_batch() {
      pass_count="$(<"$coverage_passes_file")"
      printf "%s\n" "$((pass_count + 1))" >"$coverage_passes_file"
    }
    cutover_migration_config() { printf "%s\n" "$@" >"$config_args"; }
    c05_drain_delta
  ' _ "$state" "$CUTOVER_DIR" "$coverage_passes_file" "$config_args"
  [ "$status" -eq 0 ]
  [ "$(cat "$coverage_passes_file")" = "1" ]
  [[ "$output" == *"PASS forward delta watermark"* ]]
  [ "$(cat "$config_args")" = "PAS_MIGRATION_DELTA_SYNC_PAUSED=true" ]
}

@test "coverage drain does not treat a null cursor as complete when policies exist" {
  state="$(mktemp -d "$BATS_TMPDIR/null-coverage-state.XXXXXX")"
  coverage_passes_file="$state/passes"
  printf '0\n' >"$coverage_passes_file"
  printf '%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"$state/freeze-null.timestamp"

  run bash -c '
    set -Eeuo pipefail
    export PAS_CUTOVER_ENV=local PAS_CUTOVER_STATE_DIR="$1" PAS_CUTOVER_RUN_ID=null \
      PAS_CUTOVER_DRAIN_MAX_PASSES=2
    source "$2/cutover.sh"
    coverage_passes_file="$3"
    cutover_sql() {
      if [[ "$1" == *coverage_cursor* && "$1" == *"||"* ]]; then
        printf "|true\n"
      elif [[ "$1" == *coverage_cursor* ]]; then
        printf "\n"
      elif [[ "$1" == *"SELECT count(*) FROM pas.policies"* ]]; then
        printf "1\n"
      elif [[ "$1" == *"VALUES"* ]]; then
        printf "0\n"
      else
        return 2
      fi
    }
    cutover_batch() {
      pass_count="$(<"$coverage_passes_file")"
      printf "%s\n" "$((pass_count + 1))" >"$coverage_passes_file"
    }
    c05_drain_delta
  ' _ "$state" "$CUTOVER_DIR" "$coverage_passes_file"
  [ "$status" -ne 0 ]
  [ "$(cat "$coverage_passes_file")" = "2" ]
  [[ "$output" == *"2-pass limit"* ]]
  [[ "$output" == *"full coverage pass=false"* ]]
}

@test "cutover applies read routes in order and groups all write routes" {
  calls="$BATS_TMPDIR/routes-$BATS_TEST_NUMBER"
  : >"$calls"
  run bash -c '
    calls="$2"
    source "$1/cutover.sh"
    cutover_apply_routes() { printf "%s\n" "$*" >>"$calls"; }
    assert_route() { :; }
    c08_read_routes
    c09_write_routes
  ' _ "$CUTOVER_DIR" "$calls"
  [ "$status" -eq 0 ]
  mapfile -t applied <"$calls"
  [ "${#applied[@]}" -eq 5 ]
  [[ "${applied[0]}" == "PAS_ROUTE_POLICY_INQUIRY=new" ]]
  [[ "${applied[1]}" == "PAS_ROUTE_BROKER_PORTAL_READ=new" ]]
  [[ "${applied[4]}" == *"PAS_ROUTE_NEW_BUSINESS=new PAS_ROUTE_UNDERWRITING=new PAS_ROUTE_RENEWAL=new PAS_ROUTE_ENDORSEMENT=new PAS_ROUTE_DOCUMENT_EVENTS=new PAS_ROUTING_PREMIUM_BATCH=new"* ]]
}

@test "decommission checks tag and dirty-tree guards in a temporary clone" {
  fixture_root="$(mktemp -d "$BATS_TMPDIR/decommission.XXXXXX")"
  fixture="$fixture_root/repo"
  git clone --quiet --shared "$REPO_ROOT" "$fixture"
  run "$CUTOVER_DIR/decommission-legacy-trees.sh" --execute --repo "$fixture"
  [ "$status" -eq 1 ]
  [[ "$output" == *"missing tag pas-legacy-final"* ]]

  touch "$fixture/dirty-marker"
  run "$CUTOVER_DIR/decommission-legacy-trees.sh" --execute --repo "$fixture" --skip-tag-check
  [ "$status" -eq 1 ]
  [[ "$output" == *"uncommitted changes"* ]]
}
