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
  state="$BATS_TMPDIR/resume-state-$BATS_TEST_NUMBER"
  count_file="$BATS_TMPDIR/resume-count-$BATS_TEST_NUMBER"
  run bash -c '
    set -Eeuo pipefail
    export PAS_CUTOVER_ENV=local PAS_CUTOVER_STATE_DIR="$1" PAS_CUTOVER_RUN_ID=test-run
    source "$2/lib.sh"
    count_file="$3"
    count_step() { printf x >>"$count_file"; }
    PAS_CUTOVER_EXECUTE=true
    step c01 counted count_step "count"
    step c01 counted count_step "count"
  ' _ "$state" "$CUTOVER_DIR" "$count_file"
  [ "$status" -eq 0 ]
  [[ "$output" == *"already completed"* ]]
  [ "$(cat "$count_file")" = "x" ]
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
