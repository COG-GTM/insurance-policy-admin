#!/usr/bin/env bash
# Applies .github/rulesets/main-branch-protection.json to the repository as a GitHub ruleset.
# Dry run by default: prints what it would do. Needs a token with repository administration rights:
#   tools/github/apply_branch_protection.sh --apply
set -euo pipefail

REPO="${GITHUB_REPOSITORY:-COG-GTM/insurance-policy-admin}"
RULESET="$(dirname "$0")/../../.github/rulesets/main-branch-protection.json"
NAME=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["name"])' "$RULESET")

existing=$(gh api "repos/$REPO/rulesets" --jq ".[] | select(.name == \"$NAME\") | .id" 2>/dev/null || true)
if [[ -n "$existing" ]]; then
  method=PUT; path="repos/$REPO/rulesets/$existing"
else
  method=POST; path="repos/$REPO/rulesets"
fi

if [[ "${1:-}" != "--apply" ]]; then
  echo "dry run: would $method $path with $RULESET"
  python3 -c 'import json,sys; r=json.load(open(sys.argv[1])); print("required checks:", ", ".join(c["context"] for x in r["rules"] if x["type"]=="required_status_checks" for c in x["parameters"]["required_status_checks"]))' "$RULESET"
  exit 0
fi
gh api --method "$method" "$path" --input "$RULESET"
echo "ruleset '$NAME' applied to $REPO"
