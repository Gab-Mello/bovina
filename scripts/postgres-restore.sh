#!/usr/bin/env bash
set -euo pipefail

: "${PGHOST:?Set PGHOST}" "${PGPORT:?Set PGPORT}" "${PGDATABASE:?Set PGDATABASE}" "${PGUSER:?Set PGUSER}"
[[ $# == 1 && -f "$1" ]] || { echo "Usage: postgres-restore.sh ARCHIVE" >&2; exit 2; }
[[ "$(psql --no-password -X -v ON_ERROR_STOP=1 -Atc "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%' AND c.relkind IN ('r','p','S','v','m','f')")" == 0 ]] || {
  echo "Restore requires an empty, separately provisioned database" >&2
  exit 2
}
# Roles must already exist; ownership and ACLs are preserved. Never clean/drop a live schema.
pg_restore --no-password --exit-on-error --single-transaction --dbname="$PGDATABASE" "$1"
echo "Backup archive restored; run application migration/validation and invariant checks before use"
