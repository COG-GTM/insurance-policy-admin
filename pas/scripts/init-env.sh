#!/usr/bin/env bash
# Creates the git-ignored .env for docker compose from .env.example, with a random local database
# password. Leaves an existing .env alone.
set -euo pipefail
cd "$(dirname "$0")/../.."
[[ -f .env ]] && exit 0
# PostgreSQL only sets the password when it creates its data volume; a new password would not match it.
if docker volume inspect pas_pas-postgres >/dev/null 2>&1; then
  echo "init-env: .env is missing but the local database volume pas_pas-postgres still exists." >&2
  echo "init-env: restore the previous .env, or drop the local data with 'docker compose down -v' and rerun." >&2
  exit 1
fi
generated=$(openssl rand -hex 16)
grep -v '^PAS_DB_PASSWORD' .env.example > .env
echo "PAS_DB_PASSWORD=${generated}" >> .env
chmod 600 .env
echo "created .env"
