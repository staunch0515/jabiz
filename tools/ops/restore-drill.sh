#!/usr/bin/env bash
# Restore drill (docs/design/13-observability-ops.md section 8): optionally restores the backup with the command of
# your backup tool, then starts the application once on the restored copy in verification mode
# (jabiz.ops.verify-restore.enabled: mail, push, jobs, event delivery and external jobs forced off; the application
# checks the copy and stops) and writes the drill record: RTO, recovery point, every check.
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
#   DRILL_RECORD           where to write the record (default restore-drill-<date>.md in the current directory)
#   DRILL_TIMEOUT          seconds the verification may take (default 1800)
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
chmod 777 "$work"   # the image's user writes the report here

restore_seconds=""
if [[ -n "${DRILL_RESTORE_COMMAND:-}" ]]; then
  echo "restoring: $DRILL_RESTORE_COMMAND"
  t0=$SECONDS
  bash -c "$DRILL_RESTORE_COMMAND"
  restore_seconds=$((SECONDS - t0))
  echo "restored in ${restore_seconds}s"
fi

verify_env=(
  JABIZ_OPS_VERIFY_RESTORE_ENABLED=true
  JABIZ_OPS_VERIFY_RESTORE_TARGET_TIME="$DRILL_TARGET_TIME"
  JABIZ_OPS_VERIFY_RESTORE_TEMPLATES="${DRILL_TEMPLATES:-}"
)
echo "verifying the copy"
t0=$SECONDS
set +e
if [[ -n "${DRILL_IMAGE:-}" ]]; then
  args=()
  for kv in "${verify_env[@]}"; do args+=(-e "$kv"); done
  # shellcheck disable=SC2086 # DRILL_DOCKER_ARGS is a list of arguments
  timeout "${DRILL_TIMEOUT:-1800}" docker run --rm --env-file "$DRILL_ENV_FILE" "${args[@]}" \
    -e JABIZ_OPS_VERIFY_RESTORE_REPORT=/drill/report.json -v "$work:/drill" ${DRILL_DOCKER_ARGS:-} "$DRILL_IMAGE" \
    >"$work/app.log" 2>&1
else
  (set -a; . "$DRILL_ENV_FILE"; set +a
   env "${verify_env[@]}" JABIZ_OPS_VERIFY_RESTORE_REPORT="$work/report.json" \
     timeout "${DRILL_TIMEOUT:-1800}" java -jar "$DRILL_JAR") >"$work/app.log" 2>&1
fi
status=$?
set -e
verify_seconds=$((SECONDS - t0))

if [[ ! -s "$work/report.json" ]]; then
  cp "$work/app.log" "${record%.md}.log"
  grep -E "Platform check failed|APPLICATION FAILED|ERROR" "$work/app.log" | head -20 >&2 || true
  die "the application did not verify the copy (exit $status); its log: ${record%.md}.log"
fi

python3 - "$work/report.json" "$record" "$started" "$DRILL_TARGET_TIME" "${restore_seconds}" "$verify_seconds" \
  "${DRILL_IMAGE:-${DRILL_JAR:-}}" <<'PY'
import json, sys
report_path, record, started, target, restore, verify, app = sys.argv[1:]
report = json.load(open(report_path))
items = report["items"]
passed = all(i["passed"] for i in items)
point = next((i["detail"] for i in items if i["name"] == "last-operation"), "")
rto = f"{int(restore) + int(verify)} s" if restore else f"restore time + {verify} s"
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
    f"- Result: {'PASSED' if passed else 'FAILED'}",
    "",
    "| Check | Result | Found |",
    "|---|---|---|",
]
for i in items:
    detail = i["detail"].replace("|", "\\|")
    lines.append(f"| {i['name']} | {'passed' if i['passed'] else '**FAILED**'} | {detail} |")
lines += ["", "Problems and follow-ups:", "", "- "]
open(record, "w").write("\n".join(lines) + "\n")
print("\n".join(lines))
sys.exit(0 if passed else 1)
PY
