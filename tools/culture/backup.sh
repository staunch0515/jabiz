#!/bin/sh
# Backs up Culture, Unfiltered: the database and the files, together (docs/culture/operations.md, "Backup and restore").
#
#   tools/culture/backup.sh [directory]         (default: ./backups)
#
# Writes <directory>/culture-<UTC time>/ with db.dump (pg_dump, custom format), files.tar.gz (the files directory),
# SHA256SUMS and manifest.txt, readable by the running user only. The database is dumped first, then the files are
# archived: a file uploaded in between is an object without a row (swept later), never a row without its object
# (objects are written before their rows, docs/design/14-files.md section 6). Checks that every file row of the dump
# has its object in the archive. The system keeps running. See lib.sh for CULTURE_BACKUP_MODE (compose or direct).
. "$(dirname -- "$0")/lib.sh"

ROOT=${1:-backups}
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$ROOT"
OUT=$(CDPATH= cd -- "$ROOT" && pwd)/culture-$STAMP
[ -e "$OUT" ] && fail "$OUT exists"
# Minors' personal data: nobody else on the machine reads the backup.
(umask 077 && mkdir "$OUT")
WORK=$(mktemp -d)
trap 'say "backup failed; removing $OUT"; rm -rf "$OUT" "$WORK"' EXIT

say "dumping the database"
# The file rows, read just before the dump: a file uploaded in between is in the dump and the archive, not in this list.
file_rows > "$WORK/rows"
if [ "$MODE" = compose ]; then
    (umask 077 && $COMPOSE exec -T db pg_dump -U culture -d "$DB" -Fc > "$OUT/db.dump")
else
    (umask 077 && pg_dump -d "$DB" -Fc > "$OUT/db.dump")
fi

say "archiving the files"
if [ "$MODE" = compose ]; then
    # The culture service has the files volume; a one-off container of it (as root, to write into the backup
    # directory) archives it while the application keeps running.
    $COMPOSE run --rm --no-deps -T --user root --entrypoint tar -v "$OUT:/backup" culture \
        -C "$FILES_IN_IMAGE" -czf /backup/files.tar.gz .
    $COMPOSE run --rm --no-deps -T --user root --entrypoint chown -v "$OUT:/backup" culture \
        "$(id -u):$(id -g)" /backup/files.tar.gz
    chmod 600 "$OUT/files.tar.gz"
else
    (umask 077 && tar -C "$JABIZ_FILES_LOCAL_ROOT" -czf "$OUT/files.tar.gz" .)
fi

archived_objects "$OUT/files.tar.gz" > "$WORK/objects"
report_missing "$WORK/rows" "$WORK/objects" || fail "the backup is incomplete: run it again"

(cd "$OUT" && sha256sum db.dump files.tar.gz > SHA256SUMS)
{
    echo "created: $STAMP"
    echo "mode: $MODE"
    echo "database migrations: $(sql "SELECT max(version) FROM flyway_schema_history WHERE success" 2>/dev/null || echo unknown)"
    echo "files: $(wc -l < "$WORK/objects" | tr -d ' ')"
    echo "db.dump bytes: $(wc -c < "$OUT/db.dump" | tr -d ' ')"
    echo "files.tar.gz bytes: $(wc -c < "$OUT/files.tar.gz" | tr -d ' ')"
} > "$OUT/manifest.txt"

rm -rf "$WORK"
trap - EXIT
say "backup written to $OUT"
echo "$OUT"
