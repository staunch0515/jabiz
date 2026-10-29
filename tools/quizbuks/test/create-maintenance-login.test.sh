#!/usr/bin/env bash
# Tests of deploy/quizbuks/server/create-maintenance-login.sh (deploy/quizbuks/DEPLOY.md, "账号删除的维护连接"),
# offline: a stand-in for docker records what would reach the server's psql and answers its two lookups.
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
script="$root/deploy/quizbuks/server/create-maintenance-login.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

mkdir "$work/bin"
cat >"$work/bin/docker" <<'STUB'
#!/usr/bin/env bash
# docker exec -i postgres psql ... [-c <query>]: answers the lookups from $STUB_DATABASES / $STUB_ROLES, records the rest.
query=""
while [ $# -gt 0 ]; do
  if [ "$1" = "-c" ]; then query="$2"; shift; fi
  shift
done
if [ -n "$query" ]; then
  case "$query" in
    *pg_database*) for d in $STUB_DATABASES; do case "$query" in *"'$d'"*) echo 1 ;; esac; done ;;
    *pg_roles*) for r in $STUB_ROLES; do case "$query" in *"'$r'"*) echo 1 ;; esac; done ;;
  esac
  exit 0
fi
cat >>"$STUB_SQL"
STUB
chmod +x "$work/bin/docker"

# run <database> [existing databases] [existing roles]: the script; its output in $out, its status in $status, the
# SQL it sent in $sql.
run() {
  : >"$work/sql"
  status=0
  out="$(PATH="$work/bin:$PATH" STUB_SQL="$work/sql" STUB_DATABASES="${2:-}" STUB_ROLES="${3:-}" \
    bash "$script" "$1" 2>&1)" || status=$?
  sql="$(cat "$work/sql")"
}

check() {
  local name="$1"; shift
  if "$@"; then echo "ok   $name"; else echo "FAIL $name: status $status, output: $out"; failures=$((failures + 1)); fi
}

run quizbuks_staging quizbuks_staging
check "creates the login and prints the R2DBC lines" test "$status" = 0
check "an R2DBC URL, not JDBC" grep -qx 'JABIZ_ERASURE_DATASOURCE_URL=r2dbc:postgresql://postgres:5432/quizbuks_staging' <<<"$out"
check "the login's name" grep -qx 'JABIZ_ERASURE_DATASOURCE_USERNAME=quizbuks_staging_maint' <<<"$out"
check "a 48-character password" grep -qE '^JABIZ_ERASURE_DATASOURCE_PASSWORD=[0-9a-f]{48}$' <<<"$out"
password="$(sed -n 's/^JABIZ_ERASURE_DATASOURCE_PASSWORD=//p' <<<"$out")"
check "a new login with that password" grep -qF "CREATE ROLE \"quizbuks_staging_maint\" LOGIN PASSWORD '$password';" <<<"$sql"
check "a member of jabiz_maintenance" grep -qF 'GRANT jabiz_maintenance TO "quizbuks_staging_maint";' <<<"$sql"
check "the role made once per server" grep -qF "CREATE ROLE jabiz_maintenance NOLOGIN;" <<<"$sql"
check "refuses an application login in the role" grep -qF "pg_has_role('quizbuks_staging', 'jabiz_maintenance', 'MEMBER')" <<<"$sql"
check "reads, inserts and updates the tables" grep -qF 'GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO "quizbuks_staging_maint";' <<<"$sql"
check "and the later migrations' tables" grep -qF 'ALTER DEFAULT PRIVILEGES FOR ROLE "quizbuks_staging" IN SCHEMA public GRANT SELECT, INSERT, UPDATE ON TABLES' <<<"$sql"
check "never deletes or truncates" bash -c '! grep -qiE "DELETE|TRUNCATE" <<<"$1"' _ "$sql"
check "the DO block keeps its dollar quotes" grep -qx 'DO \$\$ BEGIN' <<<"$sql"

run quizbuks_staging quizbuks_staging quizbuks_staging_maint
check "run again: a new password for the same login" grep -qF "ALTER ROLE \"quizbuks_staging_maint\" LOGIN PASSWORD" <<<"$sql"

run quizbuks_staging ""
check "no database: refused, nothing sent" test "$status" = 1 -a -z "$sql"
check "says to create it first" grep -q "create-db.sh quizbuks_staging" <<<"$out"

run "quizbuks-staging"
check "an invalid name: refused" test "$status" = 1 -a -z "$sql"

if [ "$failures" -gt 0 ]; then
  echo "$failures failed"
  exit 1
fi
