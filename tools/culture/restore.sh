#!/bin/sh
# Restores Culture, Unfiltered from a backup of backup.sh (docs/culture/operations.md, "Backup and restore").
#
#   tools/culture/restore.sh --yes <backup directory>
#
# REPLACES the database and the files with those of the backup: whatever was added since the backup is lost, and
# whatever was erased since is back (the operations document says how to erase it again). Checks the checksums first.
# compose: stops the application, restores into the `db` service and the files volume, starts the application again.
# direct: the application must be stopped before and started after (the script says so). See lib.sh for the modes.
. "$(dirname -- "$0")/lib.sh"

[ "${1:-}" = --yes ] || fail "this replaces all data of the system with the backup's; run it with --yes <backup directory>"
[ $# -eq 2 ] || fail "usage: tools/culture/restore.sh --yes <backup directory>"
IN=$(CDPATH= cd -- "$2" && pwd)
for f in db.dump files.tar.gz SHA256SUMS; do
    [ -f "$IN/$f" ] || fail "$IN/$f is missing: not a backup of backup.sh"
done

say "checking the backup"
(cd "$IN" && sha256sum -c --quiet SHA256SUMS) || fail "the backup is damaged (checksums differ)"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
archived_objects "$IN/files.tar.gz" > "$WORK/objects"

if [ "$MODE" = compose ]; then
    say "stopping the application"
    $COMPOSE stop culture
    $COMPOSE up -d --wait db
    say "restoring the database"
    # Connected to the maintenance database: the application's own is dropped and created again, empty.
    $COMPOSE exec -T db dropdb -U culture --if-exists --force "$DB"
    $COMPOSE exec -T db createdb -U culture "$DB"
    $COMPOSE exec -T db pg_restore -U culture -d "$DB" --no-owner --exit-on-error < "$IN/db.dump"
    say "restoring the files"
    $COMPOSE run --rm --no-deps -T --user root --entrypoint sh -v "$IN:/backup:ro" culture -c \
        "find $FILES_IN_IMAGE -mindepth 1 -delete && tar -xzf /backup/files.tar.gz -C $FILES_IN_IMAGE \
         && chown -R jabiz $FILES_IN_IMAGE"
else
    say "the application must be stopped now; restoring the database $DB"
    PGOPTIONS="-c client_min_messages=warning" psql -d postgres -v ON_ERROR_STOP=1 -qc "DROP DATABASE IF EXISTS \"$DB\" WITH (FORCE)" -c "CREATE DATABASE \"$DB\""
    pg_restore -d "$DB" --no-owner --exit-on-error "$IN/db.dump"
    say "restoring the files into $JABIZ_FILES_LOCAL_ROOT"
    mkdir -p "$JABIZ_FILES_LOCAL_ROOT"
    find "$JABIZ_FILES_LOCAL_ROOT" -mindepth 1 -delete
    tar -xzf "$IN/files.tar.gz" -C "$JABIZ_FILES_LOCAL_ROOT"
fi

file_rows > "$WORK/rows"
report_missing "$WORK/rows" "$WORK/objects" || say "these files will answer 404 until they are uploaded again"

if [ "$MODE" = compose ]; then
    say "starting the application"
    $COMPOSE up -d --wait culture
    say "restored from $IN"
else
    say "restored from $IN; start the application now"
fi
