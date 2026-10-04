#!/usr/bin/env bash
# Backups of the finance installation of docker-compose.yml beside this file (docs/finance/operations.md; FIN-NF-005).
#
#   backup.sh base   a base backup of the database and copies of the files and secrets volumes, into a directory of
#                    its own under BACKUP_DIR, then what `wal` does; older base backups beyond BACKUP_KEEP are removed
#                    with the WAL only they need. Run it daily.
#   backup.sh wal    copies the WAL archived since the last run into BACKUP_DIR/wal. Run it every five minutes: with
#                    the database's archive_timeout of five minutes, at most ten minutes of work is then lost with the
#                    server (FIN-NF-005: 15). Runs of either kind wait for each other.
#
# Environment: BACKUP_DIR (default ./backups beside this file; put it on another disk or machine, or copy it there),
# BACKUP_KEEP (7 base backups, at least 1), COMPOSE_PROJECT_NAME (finance). The secrets volume holds the integrity and
# MFA keys: the backup directory is made readable by its owner only.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
project="${COMPOSE_PROJECT_NAME:-finance}"
keep="${BACKUP_KEEP:-7}"
[[ "$keep" =~ ^[1-9][0-9]*$ ]] || { echo "BACKUP_KEEP must be 1 or more" >&2; exit 2; }
umask 077
backups="${BACKUP_DIR:-$here/backups}"
mkdir -p "$backups"
backups="$(cd "$backups" && pwd)"   # absolute: Docker mounts it
chmod 700 "$backups"
exec 9> "$backups/.lock"
flock 9
compose=(docker compose -f "$here/docker-compose.yml" -p "$project")
image=postgres:16

wal() {
  mkdir -p "$backups/wal"
  # The archive's finished segments not copied yet (a .part is one being written).
  local archived missing
  archived="$("${compose[@]}" exec -T db ls /var/lib/postgresql/wal)"
  missing="$(grep -v '\.part$' <<<"$archived" | grep -vxF -f <(ls -A "$backups/wal"; echo /) || true)"
  if [ -n "$missing" ]; then
    # Into a directory of its own first, moved into place only once the whole copy has arrived: a copy cut short
    # leaves nothing that a later run would take as already copied.
    rm -rf "$backups/wal.tmp" && mkdir "$backups/wal.tmp"
    printf '%s\n' "$missing" | "${compose[@]}" exec -T db tar -cf - -C /var/lib/postgresql/wal -T - \
      | tar -xf - -C "$backups/wal.tmp"
    mv "$backups/wal.tmp"/* "$backups/wal/"
    rmdir "$backups/wal.tmp"
  fi
  echo "WAL: $(printf '%s' "$missing" | grep -c . || true) new, $(ls -A "$backups/wal" | wc -l) in $backups/wal"
}

volume() { # name target.tar.gz
  docker run --rm -v "${project}_$1:/v:ro" "$image" tar -C /v -czf - . > "$2"
}

base() {
  local stamp target
  # Base backups that never completed (a failed run; runs wait for each other, so none is in progress).
  find "$backups" -maxdepth 1 -type d -name 'base-*' ! -exec test -e '{}/complete' ';' -exec rm -rf '{}' +
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  target="$backups/base-$stamp"
  mkdir -p "$target"
  # pg_basebackup waits until the WAL it needs is archived, so the base backup and the archive restore together.
  "${compose[@]}" exec -T db pg_basebackup -U finance -D - -Ft -X none -c fast --label "finance $stamp" \
    | gzip > "$target/base.tar.gz"
  volume finance-files "$target/files.tar.gz"
  volume finance-secrets "$target/secrets.tar.gz"
  tar -xzOf "$target/base.tar.gz" backup_label > "$target/backup_label"
  wal
  touch "$target/complete"
  echo "base backup: $target"

  # Base backups beyond the newest $keep are removed, then the WAL older than the oldest one kept.
  mapfile -t complete < <(find "$backups" -maxdepth 2 -name complete -path '*/base-*' -printf '%h\n' | sort)
  local drop=$(( ${#complete[@]} - keep ))
  for ((i = 0; i < drop; i++)); do rm -rf "${complete[$i]}"; done
  # The WAL before the earliest start of the backups kept goes. Segment names are compared without their timeline
  # (as pg_archivecleanup does): after a restore to a point in time, a newer backup may start on a later timeline
  # at an earlier segment than an older one.
  local first="" start
  for dir in $(find "$backups" -maxdepth 2 -name complete -path '*/base-*' -printf '%h\n'); do
    start="$(sed -n 's/^START WAL LOCATION: .* (file \([0-9A-F]\{24\}\))$/\1/p' "$dir/backup_label")"
    [ -n "$start" ] || { echo "no start WAL in $dir/backup_label: nothing pruned" >&2; return 0; }
    if [ -z "$first" ] || [[ "${start:8}" < "${first:8}" ]]; then first="$start"; fi
  done
  if [ -n "$first" ]; then
    docker run --rm -v "$backups/wal:/w" "$image" pg_archivecleanup /w "$first"
    "${compose[@]}" exec -T db pg_archivecleanup /var/lib/postgresql/wal "$first"
  fi
}

case "${1:-}" in
  base) base ;;
  wal) wal ;;
  *) echo "usage: $0 base|wal" >&2; exit 2 ;;
esac
