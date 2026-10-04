#!/usr/bin/env bash
# The operations drills (docs/finance/operations.md §4, install.md §5) with the deployment's own scripts, on a
# throwaway installation: the database of deploy/finance/docker-compose.yml under the project finance-drill (port
# 5439), the application jar run beside it (port 8091).
#
#   drill.sh restore   FIN-NF-005. Journal entries are posted before a base backup (backup.sh base) and after it; the
#                      trial balance is kept and the time noted (the failure point); one more entry follows. Then the
#                      database and its WAL archive are lost (their volumes removed), restore.sh brings the books back
#                      to the failure point from the backup directory alone, and the restored trial balance must equal
#                      the one kept, without the later entry. The time from the loss to the application answering
#                      again is reported (RTO; the target is 4 hours).
#   drill.sh upgrade   FIN-NF-006. The books are set up and posted to by an earlier release (OLD_JAR), the FIN-EXP
#                      reports are read (upgrade-check.sh before), the release of FINANCE_JAR is started on the same
#                      database (its migrations run) and the reports must be the same (upgrade-check.sh after).
#
# Needs Docker, Java 21 and the platform frontend's packages (frontend/node_modules, as tools/finance/e2e.sh).
# Environment: FINANCE_JAR (default the built backend/finance/build/libs/finance-0.0.1-SNAPSHOT.jar), OLD_JAR (upgrade),
# DRILL_DIR (a new temporary directory). Leaves nothing behind but DRILL_DIR (backups, logs, results in drill.md).
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
mode="${1:-}"
jar="${FINANCE_JAR:-$root/backend/finance/build/libs/finance-0.0.1-SNAPSHOT.jar}"
case "$mode" in
  restore) ;;
  upgrade) [ -f "${OLD_JAR:-}" ] || { echo "OLD_JAR: the earlier release's jar" >&2; exit 2; } ;;
  *) echo "usage: $0 restore|upgrade" >&2; exit 2 ;;
esac
dir="${DRILL_DIR:-$(mktemp -d)}"
mkdir -p "$dir"
export COMPOSE_PROJECT_NAME=finance-drill FINANCE_DB_PORT=5439 DB_PASSWORD=drill BACKUP_DIR="$dir/backups"
compose=(docker compose -f "$root/deploy/finance/docker-compose.yml")
port=8091
export E2E_BASE_URL="http://localhost:$port" E2E_ADMIN_USER=admin E2E_ADMIN_PASSWORD=drill-admin-password
app=

cleanup() {
  [ -n "$app" ] && kill "$app" 2>/dev/null || true
  "${compose[@]}" down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

start() { # jar
  JABIZ_JWT_SECRET="$jwt" JABIZ_INTEGRITY_KEY="$integrity" JABIZ_MFA_KEY="$mfa" JABIZ_SECURITY_MFA_ADMINISTRATION=false \
    JABIZ_BOOTSTRAP_ADMIN_USER="$E2E_ADMIN_USER" JABIZ_BOOTSTRAP_ADMIN_PASSWORD="$E2E_ADMIN_PASSWORD" \
    JABIZ_FILES_LOCAL_ROOT="$dir/files" SERVER_PORT=$port \
    SPRING_R2DBC_URL="r2dbc:postgresql://127.0.0.1:$FINANCE_DB_PORT/finance" SPRING_R2DBC_USERNAME=finance \
    SPRING_R2DBC_PASSWORD="$DB_PASSWORD" SPRING_FLYWAY_URL="jdbc:postgresql://127.0.0.1:$FINANCE_DB_PORT/finance" \
    SPRING_FLYWAY_USER=finance SPRING_FLYWAY_PASSWORD="$DB_PASSWORD" \
    java -Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true -jar "$1" >> "$dir/app.log" 2>&1 &
  app=$!
  for _ in $(seq 1 120); do curl -sf "$E2E_BASE_URL/actuator/health" >/dev/null && return; sleep 1; done
  echo "the application did not start: $dir/app.log" >&2; exit 1
}

stop() { kill "$app"; wait "$app" 2>/dev/null || true; app=; }

phase() {
  (cd "$root/frontend" && DRILL_PHASE="$1" DRILL_DIR="$dir" NODE_PATH="$root/frontend/node_modules" \
    node_modules/.bin/playwright test --config "$root/tools/finance/ops/playwright.config.ts" drill.spec.ts)
}

psql_() { "${compose[@]}" exec -T db psql -U finance -d finance -XAtc "$1"; }

jwt=$(openssl rand -base64 48) integrity=$(openssl rand -base64 48) mfa=$(openssl rand -base64 48)
"${compose[@]}" up -d --wait db
# The application's volumes, which backup.sh copies, as Compose would make them (the application runs outside it).
for volume in finance-files finance-secrets; do
  docker volume create --label com.docker.compose.project=finance-drill --label "com.docker.compose.volume=$volume" \
    "finance-drill_$volume" >/dev/null
done

if [ "$mode" = upgrade ]; then
  migrations() { psql_ "SELECT (SELECT count(*) FROM jabiz_schema_history) + (SELECT count(*) FROM flyway_schema_history)"; }
  start "$OLD_JAR"
  phase books
  before=$(migrations)
  # Read as the accountant the drill's books have, who may run the reports.
  export UPGRADE_DIR="$dir" REPORTS_USER=e2e-accountant REPORTS_PASSWORD=e2e-accountant-password-1
  "$root/tools/finance/ops/upgrade-check.sh" before
  stop
  start "$jar"
  after=$(migrations)
  "$root/tools/finance/ops/upgrade-check.sh" after
  stop
  cat > "$dir/drill.md" <<MD
| step | result |
|---|---|
| migrations applied by the upgrade | $(( after - before )) |
| FIN-EXP-01 … 14 reports the same after the upgrade | yes |
MD
  cat "$dir/drill.md"
  exit 0
fi

start "$jar"
phase books
"$root/deploy/finance/backup.sh" base
phase work
target="$(psql_ "SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US+00')")"
sleep 2
phase late
# The archiving that archive_timeout would do within five minutes, at once; then the copy backup.sh wal makes.
psql_ "SELECT pg_switch_wal()" >/dev/null
sleep 3
"$root/deploy/finance/backup.sh" wal

# The server is lost: the application, the database and the archive volume. Only the backup directory is left.
lost=$(date +%s)
stop
"${compose[@]}" down -v
RESTORE_APP=0 "$root/deploy/finance/restore.sh" --yes "" "$target"
start "$jar"
answering=$(( $(date +%s) - lost ))
phase check
stop
cat > "$dir/drill.md" <<MD
| step | result |
|---|---|
| failure point (restore target) | $target |
| from the loss to the application answering | $answering s |
| restored trial balance equals the one at the failure point, the later entry absent | yes |
MD
cat "$dir/drill.md"
