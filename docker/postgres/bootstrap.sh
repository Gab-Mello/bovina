#!/bin/sh
set -eu
: "${BOVINA_MIGRATION_PASSWORD:?Migration password required}"
: "${BOVINA_RUNTIME_PASSWORD:?Runtime password required}"
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=ON_ERROR_STOP=1 \
  --set=migration_password="$BOVINA_MIGRATION_PASSWORD" \
  --set=runtime_password="$BOVINA_RUNTIME_PASSWORD" <<'SQL'
CREATE ROLE bovina_migration LOGIN PASSWORD :'migration_password';
CREATE ROLE bovina_runtime LOGIN PASSWORD :'runtime_password';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO bovina_migration;
GRANT USAGE ON SCHEMA public TO bovina_runtime;
SQL
