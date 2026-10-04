#!/usr/bin/env bash
# Restores the finance installation of docker-compose.yml beside this file from backup.sh's backups
# (docs/finance/operations.md; FIN-NF-005), replacing its database:
#
#   restore.sh --yes [BASE_DIR] [TARGET_TIME]
#
# BASE_DIR is a base backup (BACKUP_DIR/base-…; default the newest complete one). Without TARGET_TIME the database is
# recovered through all the WAL there is (the archive volume, if it survived, and BACKUP_DIR/wal); with it, to that
# moment and no further, e.g. '2026-10-04 09:30:00+00' (a time after the base backup ended). The files and secrets
# volumes are filled from the base backup where they lost files; files they still hold are kept. The application is
# stopped first and started again at the end (not with RESTORE_APP=0). Environment as for backup.sh. The database
# being replaced is removed: copy its volume first if it may still be needed. After an earlier restore to a point in
# time, RECOVERY_TIMELINE (a timeline number, or latest) chooses the timeline to follow, and BASE_DIR must be a backup
# on that timeline's history.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
project="${COMPOSE_PROJECT_NAME:-finance}"
backups="$(cd "${BACKUP_DIR:-$here/backups}" && pwd)"   # absolute: Docker mounts it
compose=(docker compose -f "$here/docker-compose.yml" -p "$project")
image=postgres:16

[ "${1:-}" = --yes ] || { echo "usage: $0 --yes [BASE_DIR] [TARGET_TIME]  (replaces the database of $project)" >&2; exit 2; }
shift
base="${1:-$(find "$backups" -maxdepth 2 -name complete -path '*/base-*' -printf '%h\n' | sort | tail -1)}"
target="${2:-}"
if [ -n "$target" ] && ! [[ "$target" =~ ^[0-9T:.\ +-]+Z?$ ]]; then
  echo "TARGET_TIME '$target' is not a timestamp such as '2026-10-04 09:30:00+00'" >&2; exit 2
fi
if [ -n "${RECOVERY_TIMELINE:-}" ] && ! [[ "$RECOVERY_TIMELINE" =~ ^([0-9]+|latest)$ ]]; then
  echo "RECOVERY_TIMELINE '$RECOVERY_TIMELINE' is not a timeline number or latest" >&2; exit 2
fi
[ -f "$base/complete" ] || { echo "no complete base backup at '$base'" >&2; exit 1; }
base="$(cd "$base" && pwd)"
started=$(date +%s)
echo "restoring $base${target:+ to $target}"

"${compose[@]}" stop finance db
"${compose[@]}" rm -f db
docker volume rm -f "${project}_finance-db" >/dev/null
volume() { # name: created as Compose would, so that it takes the volume as its own
  docker volume create --label "com.docker.compose.project=$project" --label "com.docker.compose.volume=$1" \
    "${project}_$1" >/dev/null
}
volume finance-db
volume finance-wal

# The base backup into the empty database volume, with the archive's missing segments from the backup's copy and
# the recovery settings: recovery.signal and the target, after which the database is promoted.
recovery="recovery_target_action = 'promote'"
[ -n "$target" ] && recovery="$recovery
recovery_target_time = '$target'"
[ -n "${RECOVERY_TIMELINE:-}" ] && recovery="$recovery
recovery_target_timeline = '$RECOVERY_TIMELINE'"
docker run --rm -e RECOVERY="$recovery" -v "${project}_finance-db:/d" -v "${project}_finance-wal:/w" \
  -v "$base:/b:ro" -v "$backups/wal:/bw:ro" "$image" sh -ec '
    tar -xzf /b/base.tar.gz -C /d
    printf "%s\n" "$RECOVERY" >> /d/postgresql.auto.conf
    touch /d/recovery.signal
    for f in /bw/*; do [ -e "$f" ] || continue; [ -e "/w/${f##*/}" ] || cp "$f" /w/; done
    chown -R postgres:postgres /d /w && chmod 700 /d /w'

for volume in files secrets; do
  volume "finance-$volume"
  docker run --rm -v "${project}_finance-$volume:/v" -v "$base:/b:ro" "$image" \
    sh -ec "tar -xzf /b/$volume.tar.gz -C /v --skip-old-files; chown -R 10001 /v"
done

"${compose[@]}" up -d db
echo "recovering…"
for _ in $(seq 1 720); do
  state="$("${compose[@]}" exec -T db psql -U finance -d finance -XAtc 'SELECT pg_is_in_recovery()' 2>/dev/null || true)"
  [ "$state" = f ] && break
  sleep 5
done
[ "$state" = f ] || { echo "the database is still recovering after an hour: see 'docker compose logs db'" >&2; exit 1; }
# The recovery settings are dropped, so that a later restart does not look for them.
"${compose[@]}" exec -T db psql -U finance -d finance -XAq -c "ALTER SYSTEM RESET recovery_target_time" \
  -c "ALTER SYSTEM RESET recovery_target_action" -c "ALTER SYSTEM RESET recovery_target_timeline" \
  -c "SELECT pg_reload_conf()" >/dev/null
# Where the replay ended, for the operator to check against what was expected.
"${compose[@]}" logs db 2>&1 | grep -E 'recovery stopping|redo done|last completed transaction|selected new timeline' \
  | tail -4 || true

if [ "${RESTORE_APP:-1}" = 1 ]; then
  "${compose[@]}" up -d finance
  echo "restored in $(( $(date +%s) - started )) s; the application is starting (docker compose logs -f finance)"
else
  echo "restored in $(( $(date +%s) - started )) s; the application was not started (RESTORE_APP=0)"
fi
