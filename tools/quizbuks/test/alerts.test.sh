#!/usr/bin/env bash
# Tests of QuizBuks's alert rules (deploy/quizbuks/alerting/quizbuks-alerts.yaml, docs/quizbuks/operations.md section 5):
# rendered next to the platform's file by the platform's render.sh as the server's LGTM container does, every rule in
# the platform's form, nothing that would replace the platform's contact point or route, every setting given a default
# by the server's compose, and the files the container mounts uploaded by the deploy workflow. Offline: whether the
# rules load and fire is tools/ops/alert-fire-test.sh against a running stack.
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
rules="$root/deploy/quizbuks/alerting/quizbuks-alerts.yaml"
platform="$root/docker/grafana/alerting"
compose="$root/deploy/quizbuks/server/compose.yaml"
workflow="$root/.github/workflows/quizbuks-deploy.yml"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

check() {  # name condition...
  local name="$1"
  shift
  if "$@"; then
    echo "ok   $name"
  else
    echo "FAIL $name"
    failures=$((failures + 1))
  fi
}

# The server's directory: the platform's files and QuizBuks's side by side (DEPLOY.md, "告警").
mkdir -p "$work/src" "$work/out"
cp "$platform"/*.yaml "$platform/render.sh" "$rules" "$work/src/"
settings=(JABIZ_ALERT_EMAIL=oncall@quizbuks.test JABIZ_ALERT_SERVICE=quizbuks JABIZ_ALERT_DISK_PERCENT=80
  JABIZ_ALERT_REFRESH_GRANTED_MIN=10 JABIZ_ALERT_REFRESH_GRANTED_RATIO=0.02 JABIZ_ALERT_REFRESH_REVOKED_MAX=20
  JABIZ_ALERT_QB_AI_HELD_MAX=1)
status=0
env -i PATH="$PATH" JABIZ_ALERT_RULES_SOURCE="$work/src" JABIZ_ALERT_RULES_TARGET="$work/out" \
  JABIZ_ALERT_RENDER_ONLY=true "${settings[@]}" bash "$work/src/render.sh" 2>"$work/err" || status=$?
out="$work/out/quizbuks-alerts.yaml"
check 'both files render with the platform script' test "$status" -eq 0 -a -s "$out" -a -s "$work/out/jabiz-alerts.yaml"
check 'no placeholder is left' bash -c "! grep -q '\\\$JABIZ_ALERT_' '$out'"
check 'the service name is filled in' grep -q 'job="quizbuks"' "$out"

# The rules' form, without a YAML library (the runner's Python may have none).
check 'every rule is in the platform form' python3 - "$out" "$work/out/jabiz-alerts.yaml" <<'PY'
import re, sys
text = open(sys.argv[1]).read()
platform = open(sys.argv[2]).read()
problems = []
if re.search(r'^(contactPoints|policies|deleteRules|templates):', text, re.M):
    problems.append("only groups: the contact point and the route are the platform's")
for line in ("apiVersion: 1", "    name: quizbuks", "    folder: quizbuks", "    interval: 1m"):
    if line not in text.split("\n"):
        problems.append("missing: " + line.strip())
blocks = text.split("\n      - uid: ")[1:]
if not blocks:
    problems.append("no rules")
uids = [b.split("\n", 1)[0].strip() for b in blocks]
taken = set(re.findall(r'^\s+- uid: (\S+)', platform, re.M))
for uid, block in zip(uids, blocks):
    where = uid + ": "
    if not uid.startswith("qb-"):
        problems.append(where + "the uid does not start with qb-")
    if uid in taken:
        problems.append(where + "the uid is the platform's")
    for required in ("        title: ", "        condition: C\n", "            datasourceUid: prometheus\n",
                     "              expr: '", "              type: threshold\n", "              expression: A\n",
                     "                - evaluator: { type: gt, params: [0] }\n", "        for: ",
                     "        noDataState: OK\n", "          summary: '", "          runbook: "):
        if required not in block:
            problems.append(where + "missing " + required.strip())
    if not re.search(r"^        labels: \{ severity: (page|warning), area: [a-z-]+ \}$", block, re.M):
        problems.append(where + "labels must be severity page or warning and an area")
    expr = re.search(r"^              expr: '(.*)'$", block, re.M)
    if expr and 'job="quizbuks"' not in expr.group(1):
        problems.append(where + "the query is not limited to the service ($JABIZ_ALERT_SERVICE)")
    # A comparison with == 0 keeps the value 0, which the threshold (> 0) never takes: count() it.
    if expr and "== 0" in expr.group(1) and not expr.group(1).startswith("count("):
        problems.append(where + "== 0 outside count(): the alert never fires")
if len(set(uids)) != len(uids):
    problems.append("a uid is used twice")
for p in problems:
    print("  " + p)
sys.exit(1 if problems else 0)
PY

# Every setting of the file has a default in the server's compose (an unset one stops the container).
for name in $(grep -o '\$JABIZ_ALERT_[A-Z][A-Z_]*' "$rules" "$platform/jabiz-alerts.yaml" | cut -d'$' -f2 | sort -u); do
  check "the server's compose gives $name a default" grep -q "^      $name: \${$name:-[^}]" "$compose"
done
check 'the compose mounts the rules where render.sh reads them' grep -q '\./alerting:/otel-lgtm/jabiz/alerting:ro' "$compose"
check 'the compose starts the container with render.sh' grep -q 'command: \["bash", "/otel-lgtm/jabiz/alerting/render.sh"\]' "$compose"
for file in docker/grafana/alerting/jabiz-alerts.yaml docker/grafana/alerting/render.sh \
  deploy/quizbuks/alerting/quizbuks-alerts.yaml; do
  check "the deploy workflow uploads $file" grep -q "$file" "$workflow"
done

# The made-up data for the platform's test-fire script (platform 17zz9): a line for every rule and none for a rule
# that does not exist, each of a kind the script knows.
fakes="$root/deploy/quizbuks/alerting/quizbuks-alerts.fakes"
rule_uids=$(grep -o '^      - uid: [a-z0-9-]*' "$rules" | awk '{ print $3 }' | sort -u)
fake_uids=$(grep -v '^\s*\(#\|$\)' "$fakes" | awk '{ print $1 }' | sort -u)
check 'every rule has made-up data, and only the rules' test "$rule_uids" = "$fake_uids"
check 'every line of the made-up data is of a known kind' bash -c \
  "! grep -v '^\\s*\\(#\\|\$\\)' '$fakes' | awk '\$2 !~ /^(timer|gauge|log|skip)\$/' | grep -q ."
check 'the deploy workflow uploads the made-up data' grep -q 'deploy/quizbuks/alerting/quizbuks-alerts.fakes' "$workflow"

if ((failures > 0)); then
  echo "$failures failed"
  exit 1
fi
echo "all passed"
