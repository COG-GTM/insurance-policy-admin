#!/usr/bin/env bash
# Creates the git-ignored .env for docker compose from .env.example, with a random local database
# password. Leaves an existing .env alone.
set -euo pipefail
cd "$(dirname "$0")/../.."
[[ -f .env ]] && exit 0
generated=$(openssl rand -hex 16)
grep -v '^PAS_DB_PASSWORD' .env.example > .env
echo "PAS_DB_PASSWORD=${generated}" >> .env
chmod 600 .env
echo "created .env"
