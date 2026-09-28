# Shared by backup.sh and restore.sh (docs/culture/operations.md, "Backup and restore"). Sourced, not run.
#
# Two ways to reach the system:
#   CULTURE_BACKUP_MODE=compose (default)  the compose deployment of deploy/culture: the database service `db` and the
#                                          files volume of the `culture` service. CULTURE_COMPOSE is the compose command
#                                          (default: docker compose -f deploy/culture/docker-compose.yml).
#   CULTURE_BACKUP_MODE=direct             a server without compose: PostgreSQL through the usual PG* variables
#                                          (PGHOST, PGPORT, PGUSER, PGPASSWORD), database CULTURE_DB (default culture),
#                                          files in JABIZ_FILES_LOCAL_ROOT; pg_dump, pg_restore and psql 16 installed.

set -eu

CULTURE_TOOLS=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CULTURE_REPO=$(CDPATH= cd -- "$CULTURE_TOOLS/../.." && pwd)
MODE=${CULTURE_BACKUP_MODE:-compose}
COMPOSE=${CULTURE_COMPOSE:-docker compose -f $CULTURE_REPO/deploy/culture/docker-compose.yml}
DB=${CULTURE_DB:-culture}
# Where the application keeps its files inside the culture image (deploy/culture/Dockerfile).
FILES_IN_IMAGE=/var/lib/jabiz/files

say() { printf '%s\n' "culture: $*" >&2; }
fail() { say "$*"; exit 1; }

case "$MODE" in
    compose) command -v docker >/dev/null || fail "docker is not installed" ;;
    direct)
        for tool in pg_dump pg_restore psql tar sha256sum; do
            command -v "$tool" >/dev/null || fail "$tool is not installed"
        done
        [ -n "${JABIZ_FILES_LOCAL_ROOT:-}" ] || fail "set JABIZ_FILES_LOCAL_ROOT to the application's files directory"
        ;;
    *) fail "CULTURE_BACKUP_MODE must be compose or direct, not $MODE" ;;
esac

# psql with one SQL statement, unaligned rows. The database user of the compose deployment is `culture`.
sql() {
    if [ "$MODE" = compose ]; then
        $COMPOSE exec -T db psql -U culture -d "$DB" -v ON_ERROR_STOP=1 -Atc "$1"
    else
        psql -d "$DB" -v ON_ERROR_STOP=1 -Atc "$1"
    fi
}

# The ids of the files whose rows are in the database, and those whose objects are in an archive of the files
# directory: an object lives in <year>/<month>/<file id>/original (docs/design/14-files.md section 6).
file_rows() { sql "SELECT file_id FROM sys_file ORDER BY 1"; }
archived_objects() { tar -tzf "$1" | sed -n 's#^\(\./\)\{0,1\}[0-9]\{4\}/[0-9]\{2\}/\([0-9a-f-]\{36\}\)/original$#\2#p' | sort -u; }

# Rows without an object: files deleted after the database was dumped (the object goes after the row,
# docs/design/14-files.md section 6), or a damaged archive. Prints them; returns 1 when there are any.
report_missing() { # rows file, objects file
    missing=$(comm -23 "$1" "$2")
    [ -z "$missing" ] && return 0
    say "$(printf '%s\n' "$missing" | wc -l | tr -d ' ') file row(s) without an object in the archive:"
    printf '%s\n' "$missing" >&2
    return 1
}
