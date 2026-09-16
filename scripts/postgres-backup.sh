#!/usr/bin/env bash
set -euo pipefail
umask 077

: "${PGHOST:?Set PGHOST}" "${PGPORT:?Set PGPORT}" "${PGDATABASE:?Set PGDATABASE}" "${PGUSER:?Set PGUSER}"
[[ $# == 1 ]] || { echo "Usage: postgres-backup.sh ARCHIVE" >&2; exit 2; }
[[ ! -e "$1" ]] || { echo "Archive already exists" >&2; exit 2; }
archive="$1"
temporary="$(mktemp "${archive}.partial.XXXXXX")"
trap 'rm -f -- "$temporary"' EXIT

pg_dump --no-password --format=custom --file="$temporary"
pg_restore --list "$temporary" >/dev/null
# Publish atomically without overwriting another backup, including a competing invocation.
ln -- "$temporary" "$archive"
echo "Backup archive created"
