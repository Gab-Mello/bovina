#!/usr/bin/env bash
set -euo pipefail

# Every resource belongs to this fresh project; never stop or reset the developer's stack.
script_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
project="bovina-phase0-smoke-$(openssl rand -hex 6)"
restore_container="${project}-restore"
failed_container="${project}-failed"
export POSTGRES_PASSWORD="$(openssl rand -hex 24)"
export BOVINA_MIGRATION_PASSWORD="$(openssl rand -hex 24)"
export BOVINA_RUNTIME_PASSWORD="$(openssl rand -hex 24)"
export BOVINA_SECURITY_ISSUER="https://smoke.invalid"
export BOVINA_SECURITY_JWK_SET_URI="https://smoke.invalid/jwks"
export BOVINA_SECURITY_AUDIENCE="bovina-smoke"
export POSTGRES_PORT=0 API_PORT=0
export BOVINA_PROFILE=prod

dc() {
  docker compose --project-name "$project" --env-file /dev/null -f "$script_root/compose.yaml" "$@"
}
finish() {
  local result=$? cleanup_result container
  trap - EXIT
  set +e
  if (( result != 0 )); then
    echo "Smoke failed (exit $result). Diagnostics for $project before cleanup:" >&2
    docker ps --all --filter "label=com.docker.compose.project=$project" \
      --format '{{.ID}} {{.Names}} {{.Status}}' >&2
    for container in $(docker ps --all --quiet --filter "label=com.docker.compose.project=$project"); do
      docker inspect --format '{{.Name}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}}{{if .State.Health}} health={{.State.Health.Status}}{{end}}' "$container" >&2
      docker logs --timestamps --tail 200 "$container" >&2 2>&1
    done
  fi
  dc down --volumes --remove-orphans --timeout 40 >/dev/null 2>&1
  cleanup_result=$?
  if (( cleanup_result != 0 )); then
    echo "Cleanup failed for $project (exit $cleanup_result)." >&2
    if (( result == 0 )); then result=$cleanup_result; fi
  fi
  exit "$result"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fail() { echo "FAIL: $*" >&2; exit 1; }
http_status() { curl --silent --output /dev/null --max-time 8 --write-out '%{http_code}' "$1" || true; }
wait_status() {
  local url="$1" expected="$2"
  for attempt in {1..90}; do
    if [[ "$(http_status "$url")" == "$expected" ]]; then return; fi
    sleep 1
  done
  fail "Expected HTTP $expected from $url"
}
sql() { dc exec -T postgres psql -U bovina_bootstrap -d bovina -v ON_ERROR_STOP=1 -Atc "$1"; }

dc config --quiet
dc up --detach --no-build --wait --wait-timeout 90
api="$(dc ps -q api)"
base="http://$(dc port api 8080)"
wait_status "$base/actuator/health/readiness" 200
wait_status "$base/actuator/health/liveness" 200
[[ "$(docker exec "$api" id -u)" == 10001 ]] || fail "API is not running as UID 10001"
[[ "$(docker inspect --format '{{.HostConfig.ReadonlyRootfs}}' "$api")" == true ]] || fail "Writable image filesystem"
[[ "$(sql "SELECT to_regclass('public.organization')")" == organization ]] || fail "Organization schema missing"
[[ "$(sql "SELECT tableowner FROM pg_tables WHERE tablename='flyway_schema_history'")" == bovina_migration ]] || fail "Wrong schema owner"
migration_count="$(sql "SELECT count(*) FROM flyway_schema_history WHERE success")"
(( migration_count > 0 )) || fail "Production migrations missing"
[[ "$(http_status "$base/api/v1/clients")" == 401 ]] || fail "Anonymous API access"
runtime_user="$(dc exec -T postgres sh -c 'PGPASSWORD="$BOVINA_RUNTIME_PASSWORD" psql -h 127.0.0.1 -U bovina_runtime -d bovina -Atc "SELECT current_user"')"
[[ "$runtime_user" == bovina_runtime ]] || fail "Runtime password authentication failed"
echo "PASS: empty database, Flyway, non-root/read-only runtime and probes"

dc stop postgres
wait_status "$base/actuator/health/readiness" 503
wait_status "$base/actuator/health/liveness" 200
dc start postgres
wait_status "$base/actuator/health/readiness" 200
echo "PASS: database outage affects readiness, not liveness"

dc restart api
base="http://$(dc port api 8080)"
wait_status "$base/actuator/health/readiness" 200
[[ "$(sql "SELECT count(*) FROM flyway_schema_history WHERE success")" == "$migration_count" ]] || fail "Restart changed schema history"

sql "CREATE DATABASE bovina_restore" >/dev/null
dc exec -T postgres pg_dump -U bovina_bootstrap -d bovina --format=custom |
  dc exec -T postgres pg_restore -U bovina_bootstrap -d bovina_restore --exit-on-error
[[ "$(dc exec -T postgres psql -U bovina_bootstrap -d bovina_restore -Atc "SELECT tableowner FROM pg_tables WHERE tablename='flyway_schema_history'")" == bovina_migration ]] || fail "Restore lost ownership"
dc run --detach --no-deps --name "$restore_container" --publish 127.0.0.1::8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/bovina_restore \
  -e SPRING_FLYWAY_URL=jdbc:postgresql://postgres:5432/bovina_restore api >/dev/null
restored_base="http://$(docker port "$restore_container" 8080/tcp)"
wait_status "$restored_base/actuator/health/readiness" 200
echo "PASS: restart and isolated backup/restore boot"

dc run --detach --no-deps --name "$failed_container" \
  -e SPRING_FLYWAY_PASSWORD="$(openssl rand -hex 24)" api >/dev/null
for attempt in {1..90}; do
  if [[ "$(docker inspect --format '{{.State.Status}}' "$failed_container")" == exited ]]; then break; fi
  sleep 1
done
[[ "$(docker inspect --format '{{.State.Status}}' "$failed_container")" == exited ]] || fail "Migration failure did not stop startup"
[[ "$(docker inspect --format '{{.State.ExitCode}}' "$failed_container")" != 0 ]] || fail "Migration failure exited successfully"
docker logs "$failed_container" 2>&1 | grep 'flywayInitializer' >/dev/null || fail "Expected Flyway startup failure"
echo "PASS: invalid migration credentials prevent startup"

dc stop api
docker logs "$api" 2>&1 | grep 'Graceful shutdown complete' >/dev/null || fail "Graceful shutdown not observed"
docker logs "$api" 2>&1 | grep '"traceId".*"httpMethod"' >/dev/null || fail "Structured correlated request logs missing"
echo "PASS: graceful shutdown and structured request logs"
echo "Phase 0 runtime smoke passed; isolated containers and volume will be removed."
