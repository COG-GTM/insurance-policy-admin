#!/usr/bin/env bash
set -euo pipefail

PLATFORM="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_URL="${PAS_BATCH_URL:-http://localhost:18081}"
: "${PAS_TRIAGE_OPERATOR:?set PAS_TRIAGE_OPERATOR to the operator subject}"

if [[ $# -lt 1 ]]; then
  printf 'Usage: %s create <name> <start-date> <end-date> | cycle <id> | list [date] [classification] | classify <id> <classification> [quirk-id] [note]\n' "$0" >&2
  exit 2
fi

jwt="$("$PLATFORM/dev/dev-jwt.sh" mint --sub "$PAS_TRIAGE_OPERATOR" --scope batch:operate)"
command="$1"
shift

case "$command" in
  create)
    [[ $# -eq 3 ]] || exit 2
    payload="$(python3 -c 'import json,sys; print(json.dumps({"name":sys.argv[1],"startDate":sys.argv[2],"endDate":sys.argv[3]}))' "$@")"
    curl -fsS -X POST "$BASE_URL/api/batch/migration/parallel-run/cycles" \
      -H "Authorization: Bearer $jwt" -H 'Content-Type: application/json' --data "$payload"
    ;;
  cycle)
    [[ $# -eq 1 ]] || exit 2
    curl -fsS "$BASE_URL/api/batch/migration/parallel-run/cycles/$1" \
      -H "Authorization: Bearer $jwt"
    ;;
  list)
    url="$BASE_URL/api/batch/migration/parallel-run/differences"
    if [[ $# -gt 0 ]]; then
      url="$url?businessDate=$1"
      if [[ $# -gt 1 ]]; then
        url="$url&classification=$2"
      fi
    fi
    curl -fsS "$url" -H "Authorization: Bearer $jwt"
    ;;
  classify)
    [[ $# -ge 2 && $# -le 4 ]] || exit 2
    difference_id="$1"
    classification="$2"
    quirk_id="${3:-}"
    note="${4:-}"
    payload="$(python3 -c 'import json,sys; print(json.dumps({"classification":sys.argv[1],"quirkId":sys.argv[2] or None,"note":sys.argv[3] or None}))' "$classification" "$quirk_id" "$note")"
    curl -fsS -X POST \
      "$BASE_URL/api/batch/migration/parallel-run/differences/$difference_id/classification" \
      -H "Authorization: Bearer $jwt" -H 'Content-Type: application/json' --data "$payload"
    ;;
  *)
    printf 'Unknown command: %s\n' "$command" >&2
    exit 2
    ;;
esac
