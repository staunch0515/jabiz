#!/usr/bin/env bash
# Test-fires the platform's alerts (docker/grafana/alerting, docs/design/13-observability-ops.md section 7).
# Run it against a staging stack, never production: "fire" pushes made-up metrics under the application's service name.
#
#   alert-fire-test.sh rules           every rule is loaded and evaluates without error
#   alert-fire-test.sh notify          one test alert through the on-call route (checks the mail really arrives)
#   alert-fire-test.sh fire [uid...]   made-up data that meets each rule (all rules that can be faked by default),
#                                      then waits until every one is pending or firing
#   alert-fire-test.sh disk <dir>      fills the file system of <dir> to just above the threshold with a temporary
#                                      file until the disk alert fires, then deletes the file
#
# Settings (environment): GRAFANA_URL (http://localhost:3000), GRAFANA_USER and GRAFANA_PASSWORD (required),
# OTLP_URL (http://localhost:4318), JABIZ_ALERT_SERVICE (jabiz), JABIZ_ALERT_DISK_PERCENT (80), WAIT_SECONDS (300).
# Made-up series carry the instance "alert-test" and names "alert-test"; rules over an hour clear within the hour.
#
# An application's own rules (its file beside the platform's, rendered into the folder named after the file):
# ALERT_FOLDERS lists the folders `rules` and `fire` cover (space-separated, default "jabiz"), and ALERT_FAKES names
# the application's file of made-up data for its rules, one line per series (blank lines and # comments ignored):
#   <uid> timer <metric> <count> [key=value...]         a timer growing by <count> (Micrometer's histogram, ms)
#   <uid> gauge <metric> <unit or -> <value> [key=value...]
#   <uid> log <message...>                               an ERROR log line
#   <uid> skip <reason...>                               cannot be faked; the reason is printed
# A rule with several lines gets all of them. Values must not contain spaces or quotes.
set -euo pipefail

GRAFANA_URL=${GRAFANA_URL:-http://localhost:3000}
OTLP_URL=${OTLP_URL:-http://localhost:4318}
SERVICE=${JABIZ_ALERT_SERVICE:-jabiz}
DISK_PERCENT=${JABIZ_ALERT_DISK_PERCENT:-80}
FOLDERS=${ALERT_FOLDERS:-jabiz}
FAKES=${ALERT_FAKES:-}
WAIT_SECONDS=${WAIT_SECONDS:-300}

die() { echo "alert-fire-test: $*" >&2; exit 1; }

grafana() {
  [[ -n "${GRAFANA_USER:-}" && -n "${GRAFANA_PASSWORD:-}" ]] || die "set GRAFANA_USER and GRAFANA_PASSWORD"
  curl -fsS -u "$GRAFANA_USER:$GRAFANA_PASSWORD" -H 'Content-Type: application/json' "$@"
}

# Prints "uid state health" for every rule of the folders in ALERT_FOLDERS.
rule_states() {
  grafana "$GRAFANA_URL/api/prometheus/grafana/api/v1/rules" | python3 -c '
import json, sys
folders = sys.argv[1].split()
for group in json.load(sys.stdin)["data"]["groups"]:
    if group.get("file") not in folders:
        continue
    for rule in group["rules"]:
        labels = rule.get("labels") or {}
        print(rule.get("uid", labels.get("__alert_rule_uid__", "?")), rule["state"], rule["health"])
' "$FOLDERS"
}

cmd_rules() {
  local states
  states=$(rule_states)
  [[ -n "$states" ]] || die "no rules in the folder(s) $FOLDERS: is docker/grafana/alerting mounted?"
  echo "$states"
  if awk '$3 != "ok" { bad = 1 } END { exit !bad }' <<<"$states"; then
    die "some rules do not evaluate (health is not ok)"
  fi
  echo "all $(wc -l <<<"$states") rules evaluate"
}

# Sends a test notification through the on-call contact point (jabiz-oncall) and fails unless Grafana delivered it.
cmd_notify() {
  local api="$GRAFANA_URL/apis/notifications.alerting.grafana.app/v1beta1/namespaces/default/receivers" name body result
  name=$(grafana "$api" | python3 -c '
import json, sys
print(next((i["metadata"]["name"] for i in json.load(sys.stdin)["items"] if i["spec"]["title"] == "jabiz-oncall"), ""))')
  [[ -n "$name" ]] || die "no contact point jabiz-oncall: is docker/grafana/alerting mounted?"
  body=$(grafana "$api/$name" | python3 -c '
import json, sys
integration = json.load(sys.stdin)["spec"]["integrations"][0]
print(json.dumps({"integration": integration, "alert": {
    "labels": {"alertname": "jabiz alert test", "severity": "page", "area": "test"},
    "annotations": {"summary": "Test alert sent by tools/ops/alert-fire-test.sh: delivery to the on-call works."}}}))')
  result=$(grafana -X POST "$api/$name/test" -d "$body")
  python3 -c '
import json, sys
result = json.loads(sys.argv[1])
if result.get("status") != "success":
    sys.exit("alert-fire-test: the test notification was not delivered: " + result.get("error", sys.argv[1]))
' "$result"
  echo "Grafana delivered a test notification to jabiz-oncall: check that it arrived"
}

nanos() { echo "$(date +%s)000000000"; }

attrs() {  # key=value ... -> OTLP attribute list
  local out="" kv
  for kv in "$@"; do
    out+="${out:+,}{\"key\":\"${kv%%=*}\",\"value\":{\"stringValue\":\"${kv#*=}\"}}"
  done
  echo "[$out]"
}

push_metric() {  # json of one metric
  curl -fsS -X POST "$OTLP_URL/v1/metrics" -H 'Content-Type: application/json' -d "{\"resourceMetrics\":[{
    \"resource\":{\"attributes\":$(attrs "service.name=$SERVICE" "service.instance.id=alert-test")},
    \"scopeMetrics\":[{\"scope\":{\"name\":\"alert-fire-test\"},\"metrics\":[$1]}]}]}" >/dev/null
}

# A timer as Micrometer exports it (a cumulative histogram in milliseconds), pushed at count 0 and then at $2, so that
# increase() and rate() see it grow.
push_timer() {  # name count key=value...
  local name=$1 count=$2 start t n
  shift 2
  start=$(nanos)
  for n in 0 "$count"; do
    t=$(nanos)
    push_metric "{\"name\":\"$name\",\"unit\":\"ms\",\"histogram\":{\"aggregationTemporality\":2,\"dataPoints\":[{
      \"attributes\":$(attrs "$@"),\"startTimeUnixNano\":\"$start\",\"timeUnixNano\":\"$t\",
      \"count\":\"$n\",\"sum\":$n,\"bucketCounts\":[\"$n\"],\"explicitBounds\":[]}]}}"
    sleep 1
  done
}

push_gauge() {  # name unit value key=value...
  local name=$1 unit=$2 value=$3
  shift 3
  push_metric "{\"name\":\"$name\",\"unit\":\"$unit\",\"gauge\":{\"dataPoints\":[{
    \"attributes\":$(attrs "$@"),\"timeUnixNano\":\"$(nanos)\",\"asDouble\":$value}]}}"
}

push_log() {  # message
  curl -fsS -X POST "$OTLP_URL/v1/logs" -H 'Content-Type: application/json' -d "{\"resourceLogs\":[{
    \"resource\":{\"attributes\":$(attrs "service.name=$SERVICE" "service.instance.id=alert-test")},
    \"scopeLogs\":[{\"scope\":{\"name\":\"alert-fire-test\"},\"logRecords\":[{
      \"timeUnixNano\":\"$(nanos)\",\"severityText\":\"ERROR\",\"body\":{\"stringValue\":\"$1\"}}]}]}]}" >/dev/null
}

fake() {  # uid
  case $1 in
    jabiz-platform-check-failed) push_log "Platform check failed (alert test: not a real failure)" ;;
    jabiz-process-errors) push_timer jabiz.process 3 process=ALERT_TEST version=1 outcome=error status=500 ;;
    jabiz-admission-overload) push_timer jabiz.process.admission 3 family=alert-test process=ALERT_TEST result=QUEUE_FULL ;;
    jabiz-outbox-failed) push_timer jabiz.outbox.delivery 3 consumer=alert-test event=alert-test result=FAILED outcome=error status=500 ;;
    jabiz-job-failed) push_timer jabiz.job.run 1 job=alert-test result=FAILED outcome=error status=500 ;;
    jabiz-job-locked) push_timer jabiz.job.run 1 job=alert-test-locked result=LOCKED outcome=success status=none ;;
    jabiz-job-item-failed) push_timer jabiz.job.item 1 job=alert-test result=FAILED outcome=error status=500 ;;
    jabiz-inbound-rejected) push_timer jabiz.inbound.receive 1 provider=alert-test result=REJECTED reason=no_match outcome=rejected status=401 ;;
    jabiz-external-call-errors) push_timer jabiz.external.call 50 service=alert-test outcome=5xx ;;
    jabiz-external-job-failed) push_timer jabiz.external.job 1 job_type=alert-test outcome=FAILED ;;
    jabiz-refresh-reuse-granted)
      push_timer http.server.requests 1000 uri=/api/auth/refresh method=POST status=200 outcome=SUCCESS exception=none
      push_timer jabiz.auth.refresh.reuse 200 result=granted outcome=success status=none ;;
    jabiz-refresh-reuse-revoked) push_timer jabiz.auth.refresh.reuse 200 result=revoked outcome=success status=none ;;
    jabiz-geoip-stale) push_gauge jabiz.geoip.stale "" 1 ;;
    jabiz-geoip-empty) push_gauge jabiz.geoip.ranges "" 0 ;;
    jabiz-keys-undeclared-sealed) push_gauge jabiz.keys.undeclared-sealed "" 1 ;;
    jabiz-schema-invalid-indexes) push_gauge jabiz.schema.invalid-indexes "" 1 ;;
    jabiz-disk-usage)
      push_gauge disk.free bytes 1 path=/alert-test
      push_gauge disk.total bytes 100 path=/alert-test ;;
    jabiz-metrics-absent) echo "  $1: cannot be faked; stop the application for 5 minutes instead" >&2; return 1 ;;
    *) fake_from_file "$1" ;;
  esac
}

# The application's made-up data for a rule (ALERT_FAKES); dies when the rule has none.
fake_from_file() {  # uid
  local uid=$1 found=false skipped=false line rule kind rest
  [[ -n "$FAKES" ]] || die "unknown rule $uid (an application's rules need ALERT_FAKES)"
  [[ -r "$FAKES" ]] || die "cannot read ALERT_FAKES $FAKES"
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "${line// }" || "$line" == \#* ]] && continue
    read -r rule kind rest <<<"$line"
    [[ "$rule" == "$uid" ]] || continue
    found=true
    # shellcheck disable=SC2086 # the fields of the line, as written
    case $kind in
      timer) push_timer $rest ;;
      gauge) set -- $rest; local name=$1 unit=$2; shift 2; [[ "$unit" == - ]] && unit=""; push_gauge "$name" "$unit" "$@" ;;
      log) push_log "$rest" ;;
      skip) echo "  $uid: cannot be faked: $rest" >&2; skipped=true ;;
      *) die "ALERT_FAKES: unknown kind '$kind' for $uid (timer, gauge, log or skip)" ;;
    esac
  done <"$FAKES"
  $found || die "unknown rule $uid (not in ALERT_FAKES $FAKES)"
  ! $skipped
}

# Waits until every given rule is pending or firing.
await_rules() {
  local deadline=$((SECONDS + WAIT_SECONDS)) waiting states uid
  while :; do
    states=$(rule_states)
    waiting=()
    for uid in "$@"; do
      awk -v u="$uid" '$1 == u && ($2 == "pending" || $2 == "firing") { found = 1 } END { exit !found }' <<<"$states" \
        || waiting+=("$uid")
    done
    if ((${#waiting[@]} == 0)); then
      echo "all ${#} rules are pending or firing"
      return 0
    fi
    if ((SECONDS >= deadline)); then
      die "not pending or firing after ${WAIT_SECONDS}s: ${waiting[*]}"
    fi
    sleep 10
  done
}

cmd_fire() {
  local uids=("$@") faked=() uid
  if ((${#uids[@]} == 0)); then
    mapfile -t uids < <(rule_states | awk '{ print $1 }')
  fi
  for uid in "${uids[@]}"; do
    if fake "$uid"; then
      echo "  faked $uid"
      faked+=("$uid")
    fi
  done
  ((${#faked[@]} > 0)) || die "nothing to fake"
  echo "waiting up to ${WAIT_SECONDS}s for Grafana to evaluate (every minute)"
  await_rules "${faked[@]}"
}

cmd_disk() {
  local dir=${1:-} total avail target fill
  [[ -d "$dir" ]] || die "usage: alert-fire-test.sh disk <directory on the file system to fill>"
  # The rule fires for any measured file system, so only a quiet rule shows that this one raised it.
  if rule_states | awk '$1 == "jabiz-disk-usage" && $2 != "inactive" { busy = 1 } END { exit !busy }'; then
    die "the disk alert is already pending or firing: clear that first (or wait for made-up data to expire)"
  fi
  read -r total avail < <(df -B1 --output=size,avail "$dir" | tail -1)
  # Leave the file system one percent above the threshold.
  target=$(( total * (100 - DISK_PERCENT - 1) / 100 ))
  fill=$(( avail - target ))
  ((fill > 0)) || die "already above ${DISK_PERCENT}%: the alert should be firing"
  FILL_FILE="$dir/.jabiz-alert-fire-test.$$"
  trap 'rm -f "$FILL_FILE"; echo "removed $FILL_FILE"' EXIT
  echo "filling $(( fill / 1024 / 1024 )) MiB in $FILL_FILE (to $((DISK_PERCENT + 1))%)"
  fallocate -l "$fill" "$FILL_FILE"
  await_rules jabiz-disk-usage
}

case ${1:-} in
  rules) cmd_rules ;;
  notify) cmd_notify ;;
  fire) shift; cmd_fire "$@" ;;
  disk) shift; cmd_disk "$@" ;;
  *) sed -n '2,17p' "$0"; exit 2 ;;
esac
