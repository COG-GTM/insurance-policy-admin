#!/usr/bin/env bash
set -euo pipefail

: "${PAS_MIGRATION_SOURCE_PASSWORD:?}"
: "${PAS_MIGRATION_REVERSE_PASSWORD:?}"
: "${PAS_MIGRATION_SOURCE_USERNAME:?}"
: "${PAS_MIGRATION_REVERSE_USERNAME:?}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv source_username PAS_MIGRATION_SOURCE_USERNAME
\getenv reverse_username PAS_MIGRATION_REVERSE_USERNAME
\getenv source_password PAS_MIGRATION_SOURCE_PASSWORD
\getenv reverse_password PAS_MIGRATION_REVERSE_PASSWORD
CREATE ROLE :"source_username" LOGIN PASSWORD :'source_password';
CREATE ROLE :"reverse_username" LOGIN PASSWORD :'reverse_password';
GRANT USAGE ON SCHEMA acmeins, sysibm TO :"source_username", :"reverse_username";
GRANT SELECT ON ALL TABLES IN SCHEMA acmeins, sysibm TO :"source_username";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA acmeins TO :"reverse_username";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA acmeins TO :"reverse_username";
SQL
