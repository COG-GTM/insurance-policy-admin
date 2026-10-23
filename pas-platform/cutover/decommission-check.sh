#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
mkdir -p "$HOME/ab530"
WORKTREE="$(mktemp -d "$HOME/ab530/decommission-worktree.XXXXXX")"
cleanup() {
  git -C "$REPO_ROOT" worktree remove --force "$WORKTREE" >/dev/null 2>&1 || true
  rmdir "$WORKTREE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

rmdir "$WORKTREE"
git -C "$REPO_ROOT" worktree add --detach "$WORKTREE" HEAD
"$SCRIPT_DIR/decommission-legacy-trees.sh" --execute --commit --skip-tag-check --repo "$WORKTREE"

(
  cd "$WORKTREE"
  COMPAT_BASE_REF=HEAD~1 python3 compat/check_schema.py
  COMPAT_BASE_REF=HEAD~1 python3 compat/check_layouts.py
  COMPAT_BASE_REF=HEAD~1 python3 compat/check_security.py
  cd pas-platform
  ./mvnw -B -ntp -pl pas-common,pas-policy-service -am test
  cd ..
  make -n parity compat >/dev/null
  if git grep -n -E 'cobol/|jcl/|java-facade' -- Makefile .github; then
    printf 'Tracked Makefile/.github files still reference removed legacy paths.\n' >&2
    exit 1
  fi
)
printf 'PASS detached-worktree decommission checks completed; worktree cleanup is registered.\n'
