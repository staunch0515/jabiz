#!/bin/sh
# The restore drill (docs/culture/ROADMAP.md, C4; docs/culture/operations.md): a running system with published content
# and uploaded files is backed up, lost entirely (database and files), restored, and must work as before: the same
# public pages and the same file bytes, the administrator signs in, and new content can be added.
#
#   tools/culture/test/backup-restore.test.sh
#
# DRILL_MODE=compose (default, CI): builds and runs deploy/culture as the compose project culture-drill on port 18080,
#   with drill.compose.yml for fixed secrets; `down -v` loses everything, volumes included.
# DRILL_MODE=direct: the packaged jar (backend/culture/build/libs, built with ./gradlew :culture:bootJar) against a
#   local PostgreSQL reached through PG* variables (the user may create databases); losing everything = dropping the
#   database and deleting the files directory.
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO=$(CDPATH= cd -- "$HERE/../../.." && pwd)
MODE=${DRILL_MODE:-compose}
PORT=${DRILL_PORT:-18080}
BASE=http://localhost:$PORT
WORK=$(mktemp -d)
ADMIN_PASSWORD=drill-admin-$(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')
JWT_SECRET=$(head -c 48 /dev/urandom | base64 | tr -d '\n')
export DRILL_ADMIN_PASSWORD="$ADMIN_PASSWORD" DRILL_JWT_SECRET="$JWT_SECRET" CULTURE_PORT="$PORT"

say() { printf '%s\n' "drill: $*" >&2; }
fail() { say "FAILED: $*"; exit 1; }

if [ "$MODE" = compose ]; then
    COMPOSE="docker compose -p culture-drill -f $REPO/deploy/culture/docker-compose.yml -f $HERE/drill.compose.yml"
    export CULTURE_BACKUP_MODE=compose CULTURE_COMPOSE="$COMPOSE"
    start() { $COMPOSE up -d --wait "$@"; }
    stop_app() { :; }
    lose_everything() { $COMPOSE down -v; }
    cleanup() { $COMPOSE down -v >/dev/null 2>&1 || true; rm -rf "$WORK"; }
    logs() { $COMPOSE logs --tail 200 culture >&2 || true; }
else
    DB=culture_drill_$$
    FILES=$WORK/files
    JAR=$(ls "$REPO"/backend/culture/build/libs/culture-*.jar 2>/dev/null | grep -v plain | head -1)
    [ -n "$JAR" ] || fail "no jar in backend/culture/build/libs: ./gradlew :culture:bootJar first"
    export CULTURE_BACKUP_MODE=direct CULTURE_DB="$DB" JABIZ_FILES_LOCAL_ROOT="$FILES"
    APP_PID=
    start() {
        psql -d postgres -qtAc "SELECT 1 FROM pg_database WHERE datname = '$DB'" | grep -q 1 \
            || psql -d postgres -qc "CREATE DATABASE $DB"
        mkdir -p "$FILES"
        (
            export SPRING_R2DBC_URL="r2dbc:postgresql://${PGHOST:-localhost}:${PGPORT:-5432}/$DB"
            export SPRING_R2DBC_USERNAME="${PGUSER:-$(id -un)}" DB_PASSWORD="${PGPASSWORD:-}"
            export SPRING_FLYWAY_URL="jdbc:postgresql://${PGHOST:-localhost}:${PGPORT:-5432}/$DB"
            export SPRING_FLYWAY_USER="${PGUSER:-$(id -un)}"
            export JABIZ_JWT_SECRET="$JWT_SECRET" JABIZ_BOOTSTRAP_ADMIN_USER=admin
            export JABIZ_BOOTSTRAP_ADMIN_PASSWORD="$ADMIN_PASSWORD" JABIZ_PUBLIC_ENABLED=true SERVER_PORT="$PORT"
            exec java -Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true -jar "$JAR"
        ) > "$WORK/app.log" 2>&1 &
        APP_PID=$!
        for _ in $(seq 1 90); do curl -sf "$BASE/actuator/health" >/dev/null && return 0; sleep 2; done
        logs; fail "the application did not start"
    }
    stop_app() { [ -n "$APP_PID" ] && kill "$APP_PID" && wait "$APP_PID" 2>/dev/null || true; APP_PID=; }
    lose_everything() { stop_app; psql -d postgres -qc "DROP DATABASE $DB WITH (FORCE)"; rm -rf "$FILES"; }
    cleanup() {
        stop_app
        psql -d postgres -qc "DROP DATABASE IF EXISTS $DB WITH (FORCE)" >/dev/null 2>&1 || true
        rm -rf "$WORK"
    }
    logs() { tail -n 200 "$WORK/app.log" >&2 || true; }
fi
trap 'status=$?; [ $status -ne 0 ] && logs; cleanup; exit $status' EXIT

# The admin interface, as editors use it. POSIX sh has no pipefail: every response is kept in a variable first, so
# that a failed request stops the drill instead of passing an empty value on.
TOKEN=
login() {
    body=$(curl -sf "$BASE/api/auth/login" -H 'Content-Type: application/json' \
        -d "{\"userName\":\"admin\",\"password\":\"$ADMIN_PASSWORD\"}") || return 1
    TOKEN=$(printf '%s' "$body" | jq -r .accessToken)
    [ -n "$TOKEN" ] && [ "$TOKEN" != null ]
}
api() { # method, path, [json]
    curl -sf -X "$1" "$BASE$2" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' ${3:+-d "$3"} \
        || fail "$1 $2"
}
# Prints a field of a JSON text; fails when it is missing.
field() { # json, jq filter
    value=$(printf '%s' "$1" | jq -er "$2") || fail "no $2 in: $1"
    printf '%s\n' "$value"
}
create() { # entity, attributes (json)
    body=$(api POST "/api/datasets/urn:jabiz:dataset:culture:$1/commit" \
        "{\"changes\":[{\"action\":\"INSERT\",\"attributes\":$2}]}") || exit 1
    field "$body" '.[0].id'
}
run() { api POST "/api/processes/$1/latest" "$2" > /dev/null || exit 1; }
upload() {
    body=$(curl -sf "$BASE/api/files?policy=culture.image" -H "Authorization: Bearer $TOKEN" \
        -F "file=@$HERE/photo.jpg;type=image/jpeg") || fail "upload"
    field "$body" .fileId
}
public() { # template, query string: its rows, keys sorted
    body=$(curl -sf "$BASE/api/public/queries/$1?$2") || fail "public $1"
    items=$(field "$body" .items) || exit 1
    printf '%s' "$items" | jq -S .
}
file_sum() { # the bytes of a public file; an empty or missing file fails
    curl -sf -o "$WORK/file" "$BASE/api/public/files/$1" || fail "public file $1"
    [ -s "$WORK/file" ] || fail "public file $1 is empty"
    sha256sum < "$WORK/file" | cut -d' ' -f1
}
en() { printf '{"en":"%s"}' "$1"; }

say "starting a system ($MODE)"
start
login || fail "the administrator cannot sign in"
run CULTURE_SETUP '{}'

say "adding published content with photographs"
TAG=drill$(date +%s)
PLACE=$(create Location "{\"slug\":\"$TAG-place\",\"name\":$(en "Drill harbour"),\"countryCode\":\"NZ\",\"latitude\":-41.28,\"longitude\":174.77,\"sortOrder\":1,\"visible\":true}")
THEME=$(create Theme "{\"slug\":\"$TAG-theme\",\"icon\":\"🏠\",\"title\":$(en Home),\"question\":$(en "What makes somewhere feel like home?"),\"sortOrder\":1,\"visible\":true}")
PORTRAIT=$(upload)
PERSON=$(create Participant "{\"slug\":\"$TAG-person\",\"displayName\":\"Noa\",\"locationId\":\"$PLACE\",\"adult\":true,\"portraitFileId\":\"$PORTRAIT\",\"portraitAlt\":$(en "A portrait"),\"shortBio\":$(en "Interested in boats."),\"sortOrder\":1}")
create Consent "{\"participantId\":\"$PERSON\",\"party\":\"PARTICIPANT\",\"coversPhoto\":true,\"coversVideo\":true,\"coversVoice\":true,\"signedOn\":\"2026-01-10T00:00:00Z\"}" > /dev/null
run CULTURE_PARTICIPANT_ACTIVATE "{\"participantId\":\"$PERSON\"}"
THUMB=$(upload)
STORY=$(create Story "{\"slug\":\"$TAG-story\",\"title\":$(en "Saturday mornings"),\"summary\":$(en "One morning at the harbour."),\"mediaType\":\"ARTICLE\",\"thumbnailFileId\":\"$THUMB\",\"thumbnailAlt\":$(en "Boats at dawn"),\"body\":$(en "The market opens at six.")}")
create StoryTheme "{\"storyId\":\"$STORY\",\"themeId\":\"$THEME\"}" > /dev/null
create Contribution "{\"storyId\":\"$STORY\",\"participantId\":\"$PERSON\",\"text\":$(en "I help at the market.")}" > /dev/null
run CULTURE_STORY_PUBLISH "{\"storyId\":\"$STORY\"}"

snapshot() { # the public view of the content and its files
    {
        public culture.public.story "p.slug=$TAG-story"
        public culture.public.story_perspectives "p.slug=$TAG-story"
        public culture.public.person "p.slug=$TAG-person"
        public culture.public.stories "p.location=$TAG-place"
        echo "portrait $(file_sum "$PORTRAIT")"
        echo "thumbnail $(file_sum "$THUMB")"
    }
}
snapshot > "$WORK/before"
grep -q "Saturday mornings" "$WORK/before" || fail "the story is not public before the backup"
grep -q "I help at the market" "$WORK/before" || fail "the perspective is not public before the backup"
grep -q "$TAG-story" "$WORK/before" || fail "the story is not listed under its place before the backup"

say "backing up"
BACKUP=$("$REPO/tools/culture/backup.sh" "$WORK/backups")
[ -f "$BACKUP/db.dump" ] && [ -f "$BACKUP/files.tar.gz" ] || fail "no backup written"
[ "$(stat -c %a "$BACKUP")" = 700 ] || fail "the backup directory is readable by others"
grep -q '^files: [1-9]' "$BACKUP/manifest.txt" || fail "the backup has no files"

say "losing everything"
lose_everything
if [ "$MODE" = compose ]; then start db; fi

say "restoring"
"$REPO/tools/culture/restore.sh" --yes "$BACKUP"
if [ "$MODE" = direct ]; then start; fi

say "checking the restored system"
snapshot > "$WORK/after"
diff -u "$WORK/before" "$WORK/after" || fail "the public content or files differ after the restore"
login || fail "the administrator cannot sign in after the restore"
run CULTURE_SETUP '{}'
NEW=$(upload)
[ -n "$NEW" ] && [ "$NEW" != null ] || fail "a new file cannot be uploaded after the restore"

say "a damaged backup is refused"
printf 'x' >> "$BACKUP/files.tar.gz"
if "$REPO/tools/culture/restore.sh" --yes "$BACKUP" 2> "$WORK/refused"; then fail "a damaged backup was restored"; fi
grep -q 'damaged' "$WORK/refused" || fail "the refusal does not say the backup is damaged"
if "$REPO/tools/culture/restore.sh" "$BACKUP" 2> /dev/null; then fail "a restore without --yes ran"; fi

say "passed"
