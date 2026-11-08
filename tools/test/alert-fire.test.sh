#!/usr/bin/env bash
# Tests of tools/ops/alert-fire-test.sh against a fake `curl` (offline; the real Grafana and LGTM were checked by hand,
# 17zu): `rules` covers the folders of ALERT_FOLDERS; `fire` without arguments has made-up data for every platform rule
# that can be faked; an application's rules take theirs from ALERT_FAKES (timer, gauge, log, skip), and a rule without
# any, an unknown kind or an unreadable file stop it.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
script="$root/tools/ops/alert-fire-test.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

# The fake curl: Grafana's rule list from $work/rules.json; OTLP pushes appended to $work/pushed (one payload a line).
mkdir -p "$work/bin"
cat >"$work/bin/curl" <<'FAKE'
#!/usr/bin/env bash
data="" url=""
while (($#)); do
  case $1 in
    -d) data=$2; shift 2 ;;
    -u | -H | -X) shift 2 ;;
    -*) shift ;;
    *) url=$1; shift ;;
  esac
done
case $url in
  */api/prometheus/grafana/api/v1/rules) cat "$FAKE_DIR/rules.json" ;;
  */v1/metrics | */v1/logs) tr -d '\n ' <<<"$data" >>"$FAKE_DIR/pushed"; echo >>"$FAKE_DIR/pushed" ;;
  *) echo "fake curl: unexpected $url" >&2; exit 22 ;;
esac
FAKE
chmod +x "$work/bin/curl"
export PATH="$work/bin:$PATH" FAKE_DIR="$work" GRAFANA_USER=test GRAFANA_PASSWORD=test WAIT_SECONDS=5

# rules_json <state> <folder>:<uid>...: Grafana's answer, every rule in that state and healthy.
rules_json() {
  local state=$1
  shift
  python3 -c '
import json, sys
state, specs = sys.argv[1], sys.argv[2:]
groups = {}
for spec in specs:
    folder, uid = spec.split(":", 1)
    groups.setdefault(folder, []).append({"uid": uid, "state": state, "health": "ok"})
print(json.dumps({"data": {"groups": [{"file": f, "rules": r} for f, r in groups.items()]}}))
' "$state" "$@" >"$work/rules.json"
}

check() {
  if [ "$2" = yes ]; then echo "ok   $1"; else echo "FAIL $1: $out"; failures=$((failures + 1)); fi
}

run() {
  : >"$work/pushed"
  status=0
  out=$("$script" "$@" 2>&1) || status=$?
}

mapfile -t platform < <(grep -o 'uid: jabiz-[a-z-]*' "$root/docker/grafana/alerting/jabiz-alerts.yaml" | awk '{ print $2 }' |
  grep -vx jabiz-oncall-email)
specs=()
for uid in "${platform[@]}"; do specs+=("jabiz:$uid"); done
cat >"$work/fakes" <<'LIST'
# QuizBuks-like rules
qb-diff gauge qb.reconcile.differences - 1 check=CASH
qb-busy timer jabiz.process 20 process=QB_ATTEMPT_SUBMIT status=503 outcome=busy
qb-error log Reconciliation failed (alert test)
qb-missing skip needs a day without a reconciliation
qb-two timer jabiz.external.job 1 job_type=qb.stripe.transfer outcome=FAILED

qb-two gauge jabiz.external.job.held - 5 job_type=qb.ai.questions
LIST

rules_json pending "${specs[@]}" quizbuks:qb-diff quizbuks:qb-busy quizbuks:qb-error quizbuks:qb-missing quizbuks:qb-two

run rules
[ "$status" -eq 0 ] && grep -q "jabiz-disk-usage" <<<"$out" && ! grep -q "qb-diff" <<<"$out" && r=yes || r=no
check "rules: the platform's folder by default" $r

ALERT_FOLDERS="jabiz quizbuks" run rules
[ "$status" -eq 0 ] && grep -q "qb-diff" <<<"$out" && grep -q "jabiz-disk-usage" <<<"$out" && r=yes || r=no
check "rules: the folders of ALERT_FOLDERS" $r

run fire
[ "$status" -eq 0 ] && grep -q "all $((${#platform[@]} - 1)) rules are pending or firing" <<<"$out" \
  && grep -q "jabiz-metrics-absent: cannot be faked" <<<"$out" && r=yes || r=no
check "fire: made-up data for every platform rule but the absent metrics (${#platform[@]} rules)" $r
grep -q '"name":"jabiz.keys.undeclared-sealed"' "$work/pushed" && r=yes || r=no
check "fire: the undeclared sealed values' gauge (17zzz)" $r

ALERT_FOLDERS="jabiz quizbuks" ALERT_FAKES="$work/fakes" run fire qb-diff qb-busy qb-error qb-two
[ "$status" -eq 0 ] && grep -q "all 4 rules are pending or firing" <<<"$out" && r=yes || r=no
check "fire: an application's rules from ALERT_FAKES" $r
grep -q '"name":"qb.reconcile.differences","unit":"","gauge"' "$work/pushed" \
  && grep -q '"key":"check","value":{"stringValue":"CASH"}' "$work/pushed" && r=yes || r=no
check "fire: a gauge without a unit, with its attribute" $r
[ "$(grep -c '"name":"jabiz.process"' "$work/pushed")" -eq 2 ] && grep -q '"count":"20"' "$work/pushed" \
  && grep -q '"key":"service.name","value":{"stringValue":"jabiz"}' "$work/pushed" && r=yes || r=no
check "fire: a timer pushed at 0 and at its count, under the service name" $r
grep -q 'Reconciliationfailed(alerttest)' "$work/pushed" && r=yes || r=no
check "fire: a log line" $r
grep -q '"name":"jabiz.external.job.held"' "$work/pushed" && grep -q '"name":"jabiz.external.job"' "$work/pushed" \
  && r=yes || r=no
check "fire: every line of a rule with two" $r

ALERT_FOLDERS="jabiz quizbuks" ALERT_FAKES="$work/fakes" run fire qb-missing
[ "$status" -ne 0 ] && grep -q "qb-missing: cannot be faked: needs a day without a reconciliation" <<<"$out" \
  && grep -q "nothing to fake" <<<"$out" && r=yes || r=no
check "fire: a skipped rule is named with its reason, and alone is nothing to fake" $r

ALERT_FOLDERS="jabiz quizbuks" run fire qb-diff
[ "$status" -ne 0 ] && grep -q "need ALERT_FAKES" <<<"$out" && r=yes || r=no
check "fire: an application's rule without ALERT_FAKES" $r

ALERT_FOLDERS="jabiz quizbuks" ALERT_FAKES="$work/fakes" run fire qb-unknown
[ "$status" -ne 0 ] && grep -q "not in ALERT_FAKES" <<<"$out" && r=yes || r=no
check "fire: a rule the file does not know" $r

echo "qb-diff counter x 1" >"$work/bad"
ALERT_FAKES="$work/bad" run fire qb-diff
[ "$status" -ne 0 ] && grep -q "unknown kind 'counter'" <<<"$out" && r=yes || r=no
check "fire: an unknown kind" $r

ALERT_FAKES="$work/missing" run fire qb-diff
[ "$status" -ne 0 ] && grep -q "cannot read ALERT_FAKES" <<<"$out" && r=yes || r=no
check "fire: an unreadable file" $r

# Rules that stay inactive: fire waits, then names them.
rules_json inactive "jabiz:jabiz-geoip-stale"
run fire jabiz-geoip-stale
[ "$status" -ne 0 ] && grep -q "not pending or firing after 5s: jabiz-geoip-stale" <<<"$out" && r=yes || r=no
check "fire: a rule that does not react is named" $r

[ "$failures" -eq 0 ] || { echo "$failures failure(s)"; exit 1; }
echo "all alert-fire tests passed"
