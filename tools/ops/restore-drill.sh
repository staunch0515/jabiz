#!/usr/bin/env bash
# Restore drill (docs/design/13-observability-ops.md section 8): optionally restores the backup with the command of
# your backup tool, then starts the application once on the restored copy in verification mode
# (jabiz.ops.verify-restore.enabled: mail, push, jobs, event delivery and external jobs forced off; the application
# checks the copy and stops) and writes the drill record: RTO, recovery point, every check. With DRILL_ROTATE=true it
# then rehearses a key rotation on the same copy (decision D78, docs/guide/key-rotation.md): new keys with the
# production ones as the retired keys, the re-keying run to the end; then the new keys alone.
#
# Settings (environment):
#   DRILL_TARGET_TIME      the point in time T restored to (ISO-8601, e.g. 2026-11-06T12:00:00Z); required
#   DRILL_RESTORE_COMMAND  optional: the backup tool's restore to T (pgBackRest, WAL-G, the provider's CLI...), timed
#   DRILL_ENV_FILE         required: the application's settings for the copy - database (SPRING_R2DBC_*,
#                          SPRING_FLYWAY_*), JABIZ_FILES_LOCAL_ROOT (the restored files), and the production values of
#                          JABIZ_INTEGRITY_KEY, JABIZ_MFA_KEY and JABIZ_SEAL_KEY from their offline copy; application
#                          switches that keep the copy away from live services (payment test mode and the like)
#   DRILL_IMAGE            the application's image (the same version as production), run with docker; or
#   DRILL_JAR              the application's jar, run with java
#   DRILL_DOCKER_ARGS      extra docker run arguments: the network that reaches the restored database and nothing
#                          else (an internal network), volumes for the files
#   DRILL_TEMPLATES        the application's checks: templates that return no rows when all is well (comma-separated)
#   DRILL_ROTATE           true: also rehearse a key rotation on the copy (it writes to the copy)
#   DRILL_RECORD           where to write the record (default restore-drill-<date>.md in the current directory)
#   DRILL_TIMEOUT          seconds each verification may take (default 1800)
set -euo pipefail

die() { echo "restore-drill: $*" >&2; exit 2; }

[[ -n "${DRILL_TARGET_TIME:-}" ]] || die "set DRILL_TARGET_TIME (the point in time restored to)"
[[ -f "${DRILL_ENV_FILE:-}" ]] || die "set DRILL_ENV_FILE to the copy's settings"
[[ -n "${DRILL_IMAGE:-}" || -n "${DRILL_JAR:-}" ]] || die "set DRILL_IMAGE or DRILL_JAR"
for key in JABIZ_INTEGRITY_KEY JABIZ_MFA_KEY; do
  grep -q "^$key=." "$DRILL_ENV_FILE" || die "$DRILL_ENV_FILE has no $key: the copy needs production's"
done
date -u -d "$DRILL_TARGET_TIME" >/dev/null 2>&1 || die "DRILL_TARGET_TIME is not a time: $DRILL_TARGET_TIME"

started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
record=${DRILL_RECORD:-restore-drill-$(date -u +%Y-%m-%d).md}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
chmod 777 "$work"   # the image's user writes the reports here

restore_seconds=""
if [[ -n "${DRILL_RESTORE_COMMAND:-}" ]]; then
  echo "restoring: $DRILL_RESTORE_COMMAND"
  t0=$SECONDS
  bash -c "$DRILL_RESTORE_COMMAND"
  restore_seconds=$((SECONDS - t0))
  echo "restored in ${restore_seconds}s"
fi

# verify <name> [NAME=value...]: one run in verification mode with these settings over the env file's; writes
# $work/<name>.json and the run's seconds to $work/<name>.seconds.
verify() {
  local name=$1 t0 status
  shift
  local settings=(JABIZ_OPS_VERIFY_RESTORE_ENABLED=true JABIZ_OPS_VERIFY_RESTORE_TARGET_TIME="$DRILL_TARGET_TIME"
    JABIZ_OPS_VERIFY_RESTORE_TEMPLATES="${DRILL_TEMPLATES:-}" "$@")
  echo "verifying the copy ($name)"
  t0=$SECONDS
  set +e
  if [[ -n "${DRILL_IMAGE:-}" ]]; then
    local args=()
    for kv in "${settings[@]}"; do args+=(-e "$kv"); done
    # shellcheck disable=SC2086 # DRILL_DOCKER_ARGS is a list of arguments
    timeout "${DRILL_TIMEOUT:-1800}" docker run --rm --env-file "$DRILL_ENV_FILE" "${args[@]}" \
      -e JABIZ_OPS_VERIFY_RESTORE_REPORT="/drill/$name.json" -v "$work:/drill" ${DRILL_DOCKER_ARGS:-} "$DRILL_IMAGE" \
      >"$work/$name.log" 2>&1
  else
    (set -a; . "$DRILL_ENV_FILE"; set +a
     env "${settings[@]}" JABIZ_OPS_VERIFY_RESTORE_REPORT="$work/$name.json" \
       timeout "${DRILL_TIMEOUT:-1800}" java -jar "$DRILL_JAR") >"$work/$name.log" 2>&1
  fi
  status=$?
  set -e
  echo $((SECONDS - t0)) >"$work/$name.seconds"
  if [[ ! -s "$work/$name.json" ]]; then
    cp "$work/$name.log" "${record%.md}-$name.log"
    grep -E "Platform check failed|APPLICATION FAILED|ERROR" "$work/$name.log" | head -20 >&2 || true
    die "the application did not verify the copy ($name, exit $status); its log: ${record%.md}-$name.log"
  fi
}

verify restore

runs=(restore)
if [[ "${DRILL_ROTATE:-}" == "true" ]]; then
  old() { grep "^$1=" "$DRILL_ENV_FILE" | head -1 | cut -d= -f2-; }
  fresh() { openssl rand -base64 48 | tr -d '\n'; }
  new_jwt=$(fresh); new_mfa=$(fresh); new_integrity=$(fresh); new_seal=$(fresh)
  rotating=(JABIZ_JWT_SECRET="$new_jwt" JABIZ_JWT_SECRET_PREVIOUS="$(old JABIZ_JWT_SECRET)"
    JABIZ_MFA_KEY="$new_mfa" JABIZ_MFA_KEY_PREVIOUS="$(old JABIZ_MFA_KEY)"
    JABIZ_INTEGRITY_KEY="$new_integrity" JABIZ_INTEGRITY_KEY_PREVIOUS="$(old JABIZ_INTEGRITY_KEY)")
  rotated=(JABIZ_JWT_SECRET="$new_jwt" JABIZ_MFA_KEY="$new_mfa"
    JABIZ_INTEGRITY_KEY="$new_integrity" JABIZ_INTEGRITY_KEY_PREVIOUS="$(old JABIZ_INTEGRITY_KEY)")
  if [[ -n "$(old JABIZ_SEAL_KEY)" ]]; then
    rotating+=(JABIZ_SEAL_KEY="$new_seal" JABIZ_SEAL_KEY_PREVIOUS="$(old JABIZ_SEAL_KEY)")
    rotated+=(JABIZ_SEAL_KEY="$new_seal")
  fi
  [[ -n "$(old JABIZ_JWT_SECRET)" ]] || rotating=("${rotating[@]:2}")
  # New keys, the production ones retired, the re-keying run to the end; then the new keys alone (the integrity key
  # keeps its retired one for good: blocks are never signed again).
  verify rotating "${rotating[@]}" JABIZ_OPS_VERIFY_RESTORE_REKEY=true
  verify rotated "${rotated[@]}"
  runs+=(rotating rotated)
fi

python3 - "$work" "$record" "$started" "$DRILL_TARGET_TIME" "${restore_seconds}" \
  "${DRILL_IMAGE:-${DRILL_JAR:-}}" "${runs[@]}" <<'PY'
import json, sys
work, record, started, target, restore, app, *runs = sys.argv[1:]
titles = {"restore": "Restored copy (production keys)",
          "rotating": "Key rotation: new keys, production keys retired, re-keying to the end",
          "rotated": "Key rotation: new keys alone"}
reports = {run: json.load(open(f"{work}/{run}.json")) for run in runs}
seconds = {run: int(open(f"{work}/{run}.seconds").read()) for run in runs}
passed = all(i["passed"] for r in reports.values() for i in r["items"])
first = reports["restore"]["items"]
point = next((i["detail"] for i in first if i["name"] == "last-operation"), "")
verify = seconds["restore"]
rto = f"{int(restore) + verify} s" if restore else f"restore time + {verify} s"
lines = [
    f"# Restore drill {started[:10]}",
    "",
    f"- Started: {started}",
    f"- Target time T: {target}",
    f"- Application: {app}",
    f"- Restore: {restore + ' s' if restore else 'not timed here (record the backup tool or provider time)'}",
    f"- Verification: {verify} s",
    f"- RTO (restore + verification): {rto}",
    f"- Recovery point: {point}",
    f"- Key rotation rehearsed: {'yes' if 'rotating' in runs else 'no'}",
    f"- Result: {'PASSED' if passed else 'FAILED'}",
]
for run in runs:
    lines += ["", f"## {titles[run]} ({seconds[run]} s)", "", "| Check | Result | Found |", "|---|---|---|"]
    for i in reports[run]["items"]:
        detail = i["detail"].replace("|", "\\|")
        lines.append(f"| {i['name']} | {'passed' if i['passed'] else '**FAILED**'} | {detail} |")
lines += ["", "Problems and follow-ups:", "", "- "]
open(record, "w").write("\n".join(lines) + "\n")
print("\n".join(lines))
sys.exit(0 if passed else 1)
PY
