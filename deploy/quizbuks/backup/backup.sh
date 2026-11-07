#!/usr/bin/env bash
# The backups of the shared PostgreSQL (pgBackRest) and of production's uploaded files (restic), run by the systemd
# timers in ./systemd (deploy/quizbuks/DEPLOY.md, "备份"). One job per call:
#
#   backup.sh pitr       repo1: a full backup on Sundays (UTC), a differential one otherwise; then expire
#   backup.sh monthly    repo2: a full backup, only on the last day of the month (UTC) unless FORCE=true
#   backup.sh files      restic: a snapshot of the files volume, then forget by the retention below
#   backup.sh check      pgbackrest check (WAL reaches both repositories), the newest backups' age, the archiver's
#                        failures, restic check: what the hourly timer runs
#   backup.sh stanza     once, before the first backup: create the stanza in both repositories
#
# Settings come from BACKUP_ENV (default /opt/infra/backup/backup.env). A failure exits non-zero, pings <url>/fail
# when BACKUP_PING_URL_<JOB> is set and mails BACKUP_MAIL_TO; success pings <url>. Secrets are never printed.
#
# For a rehearsal without docker: PGBACKREST="sudo -u postgres -E pgbackrest" PSQL="sudo -u postgres psql"
# RESTIC="restic" FILES_PATH=<dir> backup.sh <job>
# -E: the ERR trap below also fires for a failure inside a function (run, restic_run).
set -Eeuo pipefail

job=${1:-}
case "$job" in pitr|monthly|files|check|stanza) ;; *) echo "usage: backup.sh pitr|monthly|files|check|stanza" >&2; exit 2;; esac

env_file=${BACKUP_ENV:-/opt/infra/backup/backup.env}
[[ -r "$env_file" ]] || { echo "backup: cannot read $env_file" >&2; exit 2; }
set -a
# shellcheck disable=SC1090
. "$env_file"
set +a

container=${POSTGRES_CONTAINER:-postgres}
pgbackrest=${PGBACKREST:-docker exec -u postgres $container pgbackrest}
psql=${PSQL:-docker exec -u postgres $container psql}
restic_image=${RESTIC_IMAGE:-restic/restic:0.17.3}
max_backup_age_hours=${MAX_BACKUP_AGE_HOURS:-26}
stanza=${STANZA:-main}

ping_var="BACKUP_PING_URL_${job^^}"
ping_url=${!ping_var:-}

ping() {
  [[ -n "$ping_url" ]] || return 0
  curl -fsS -m 10 --retry 3 -o /dev/null "$ping_url$1" || echo "backup: ping $1 failed (the run itself is not affected)" >&2
}

mail_failure() {
  local subject="[backup] $job failed on $(hostname)" body=$1
  [[ -n "${BACKUP_MAIL_SMTP_URL:-}" && -n "${BACKUP_MAIL_TO:-}" ]] || { echo "backup: no BACKUP_MAIL_* to tell anyone" >&2; return 0; }
  local message
  message=$(mktemp)
  {
    printf 'From: %s\r\nTo: %s\r\nSubject: %s\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n' \
      "$BACKUP_MAIL_FROM" "$BACKUP_MAIL_TO" "$subject"
    printf '%s\r\n\r\nThe last lines of the run:\r\n' "$body"
    # SMTP allows lines up to 998 characters; pgBackRest's command line is longer (its secrets show as <redacted>).
    # Printable ASCII only: a failed decryption can print raw bytes.
    tail -n 40 "$log" | LC_ALL=C tr -cd '\11\12\40-\176' | cut -c1-300 | sed 's/$/\r/'
  } >"$message"
  local auth=()
  [[ -n "${BACKUP_MAIL_USER:-}" ]] && auth=(--user "$BACKUP_MAIL_USER:${BACKUP_MAIL_PASSWORD:-}")
  curl -fsS -m 30 --ssl-reqd --url "$BACKUP_MAIL_SMTP_URL" "${auth[@]}" --mail-from "$BACKUP_MAIL_FROM" \
    --mail-rcpt "$BACKUP_MAIL_TO" --upload-file "$message" -o /dev/null || echo "backup: the mail could not be sent" >&2
  rm -f "$message"
}

log=$(mktemp)
trap 'rm -f "$log"' EXIT
failed() {
  local status=$?
  echo "backup: $job failed (exit $status)" | tee -a "$log" >&2
  ping /fail
  mail_failure "The backup job '$job' failed with exit status $status. Nothing was removed; the previous backups stay."
  exit "$status"
}
trap failed ERR

run() { "$@" 2>&1 | tee -a "$log"; return "${PIPESTATUS[0]}"; }

restic_run() {
  if [[ -n "${RESTIC:-}" ]]; then
    run $RESTIC "$@"
  else
    run docker run --rm --env-file "$env_file" -v "${FILES_VOLUME}:/data:ro" "$restic_image" "$@"
  fi
}

ping /start
case "$job" in
  stanza)
    run $pgbackrest --stanza="$stanza" stanza-create
    run $pgbackrest --stanza="$stanza" check
    ;;
  pitr)
    type=diff
    [[ "$(date -u +%u)" == 7 || "${FORCE_FULL:-false}" == true ]] && type=full
    run $pgbackrest --stanza="$stanza" --repo=1 --type="$type" backup
    ;;
  monthly)
    if [[ "${FORCE:-false}" != true && "$(date -u -d tomorrow +%d)" != 01 ]]; then
      echo "backup: not the last day of the month (UTC); nothing to do" | tee -a "$log"
    else
      run $pgbackrest --stanza="$stanza" --repo=2 --type=full backup
    fi
    ;;
  files)
    source_dir=/data
    [[ -n "${FILES_PATH:-}" ]] && source_dir=$FILES_PATH
    restic_run snapshots --latest 1 >/dev/null 2>&1 || restic_run init
    restic_run backup --host quizbuks-files --tag files "$source_dir"
    restic_run forget --host quizbuks-files --keep-hourly 48 --keep-daily 14 --keep-monthly 120 --prune
    ;;
  check)
    run $pgbackrest --stanza="$stanza" check
    # The newest backup of repo1 must be younger than MAX_BACKUP_AGE_HOURS.
    # The latest backup's stop time (epoch seconds) from pgBackRest's JSON, without a JSON tool on the host.
    newest=$($pgbackrest --stanza="$stanza" --repo=1 --output=json info | grep -o '"stop":[0-9]*' \
      | grep -o '[0-9]*$' | sort -n | tail -n 1)
    newest=${newest:-0}
    age=$(( ($(date -u +%s) - newest) / 3600 ))
    echo "backup: newest backup in repo1 is ${age} h old" | tee -a "$log"
    (( newest > 0 && age < max_backup_age_hours )) || { echo "backup: older than ${max_backup_age_hours} h" | tee -a "$log"; false; }
    # The archiver: a failure after the last success means WAL is not reaching the bucket.
    archiver=$($psql -XAtc "select coalesce(last_failed_time > coalesce(last_archived_time, 'epoch'), false) from pg_stat_archiver")
    [[ "$archiver" == f ]] || { echo "backup: WAL archiving is failing (pg_stat_archiver)" | tee -a "$log"; false; }
    restic_run check
    ;;
esac
ping ""
