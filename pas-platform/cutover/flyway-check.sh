#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"

check_versions() {
  local expected_file="$1" applied_file="$2"
  local -A expected=() applied=()
  local version success failed=0 pending=()

  while IFS= read -r version; do
    if [[ -n "$version" ]]; then
      expected["$version"]=1
    fi
  done < <(sort -Vu "$expected_file")
  while IFS='|' read -r version success; do
    [[ -n "$version" ]] || continue
    applied["$version"]="$success"
    if [[ "$success" != "t" && "$success" != "true" ]]; then
      log "FAIL Flyway version $version has success=false"
      failed=1
    fi
    if [[ -z "${expected[$version]:-}" ]]; then
      log "FAIL unknown applied Flyway version $version is not present in the repository"
      failed=1
    fi
  done < <(sort -t '|' -k1,1V "$applied_file")

  while IFS= read -r version; do
    [[ -n "$version" ]] || continue
    if [[ -z "${applied[$version]:-}" ]]; then
      pending+=("$version")
    fi
  done < <(sort -Vu "$expected_file")

  if ((${#pending[@]})); then
    local pending_version max_applied=""
    for pending_version in "${pending[@]}"; do
      log "FAIL pending or ignored Flyway version $pending_version"
      failed=1
    done
    while IFS= read -r version; do
      [[ -n "$version" ]] && max_applied="$version"
    done < <(sort -Vu <(printf '%s\n' "${!applied[@]}"))
    for pending_version in "${pending[@]}"; do
      if [[ -n "$max_applied" ]] && [[ "$(printf '%s\n' "$pending_version" "$max_applied" | sort -V | tail -n 1)" == "$max_applied" ]]; then
        log "FAIL pending version $pending_version is below an applied version; outOfOrder is not enabled; apply via a reviewed fix-forward migration / baseline plan, see runbook"
      fi
    done
  fi

  if ((failed)); then
    return 1
  fi
  log 'PASS Flyway repository versions match successful applied history'
}

collect_expected_versions() {
  local migration_dir="$1" output_file="$2" file version
  : >"$output_file"
  while IFS= read -r file; do
    version="${file##*/}"
    version="${version#V}"
    version="${version%%__*}"
    printf '%s\n' "$version" >>"$output_file"
  done < <(find "$migration_dir" -maxdepth 1 -type f -name 'V*__*.sql' | sort -V)
}

run_schema_check() {
  local schema="$1" migration_dir="$2"
  local expected_file="$PAS_CUTOVER_STATE_DIR/${schema}-expected.txt"
  local applied_file="$PAS_CUTOVER_STATE_DIR/${schema}-applied.txt"
  collect_expected_versions "$migration_dir" "$expected_file"
  cutover_sql "SELECT version || '|' || success FROM ${schema}.flyway_schema_history ORDER BY installed_rank" \
    >"$applied_file"
  check_versions "$expected_file" "$applied_file"
}

main() {
  local policy_dir="$PLATFORM_DIR/pas-policy-service/src/main/resources/db/migration"
  local batch_dir="$PLATFORM_DIR/pas-batch/src/main/resources/db/migration/batch"
  run_schema_check pas "$policy_dir"
  run_schema_check pas_batch "$batch_dir"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
