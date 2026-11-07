#!/usr/bin/env bash
# Tests of QuizBuks's rules for the platform's settings check (deploy/quizbuks/server/env-check.rules,
# docs/quizbuks/launch-checklist.md "安全"): every line is a rule the platform's tools/ops/env-check.sh knows; and,
# with that script (ENV_CHECK, default tools/ops/env-check.sh, the platform's 17zx), a production
# .env passes while one with a test switch, a copied local .env or no monitoring fails, naming the variable.
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
rules="$root/deploy/quizbuks/server/env-check.rules"
script="${ENV_CHECK:-$root/tools/ops/env-check.sh}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

fail() {
  echo "FAIL $1"
  failures=$((failures + 1))
}

while IFS= read -r line || [[ -n "$line" ]]; do
  line=${line%%#*}
  read -r rule name value rest <<<"$line" || true
  [[ -z "${rule:-}" ]] && continue
  case $rule in
    set | absent | warn-absent) [[ -n "$name" && -z "${value:-}" ]] || fail "rule '$line' takes a name only" ;;
    equals | not-equals) [[ -n "$name" && -n "${value:-}" && -z "${rest:-}" ]] || fail "rule '$line' takes a name and a value" ;;
    *) fail "unknown rule '$rule'" ;;
  esac
  [[ "$name" =~ ^[A-Z][A-Z0-9_]*$ ]] || fail "'$name' is not a variable name"
done <"$rules"
echo "ok   every line is a rule"

if [[ ! -f "$script" ]]; then
  # The platform's script is on this line since the sync of 17zx (#511): a missing one is a broken checkout.
  fail "the platform's check is not here ($script)"
else
  key() { openssl rand -base64 48 | tr -d '\n'; }
  cat >"$work/good" <<ENV
APP_NAME=quizbuks
JABIZ_WEB_ENVIRONMENT=production
JABIZ_JWT_SECRET=$(key)
JABIZ_INTEGRITY_KEY=$(key)
JABIZ_MFA_KEY=$(key)
JABIZ_SEAL_KEY=$(key)
JABIZ_TRUSTED_PROXIES=172.18.0.2
JABIZ_ALERT_EMAIL=oncall@quizbuks.test
GF_SMTP_ENABLED=true
QB_STRIPE_PLACEHOLDERS=false
COMPOSE_PROFILES=observability
MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=true
MANAGEMENT_OTLP_METRICS_EXPORT_URL=http://lgtm:4318/v1/metrics
MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT=http://lgtm:4318/v1/logs
JABIZ_FILES_LOCAL_ROOT=/var/lib/jabiz/files
ENV
  if out=$(bash "$script" "$work/good" "$rules" 2>&1); then
    echo "ok   a production .env passes"
  else
    fail "a production .env passes: $out"
  fi
  # bad <name> <line to add or replace> <variable the check must name>
  bad() {
    local name=$1 line=$2 variable=$3 out
    { grep -v "^${line%%=*}=" "$work/good"; [[ "$line" == *=* ]] && printf '%s\n' "$line"; } >"$work/bad"
    if out=$(bash "$script" "$work/bad" "$rules" 2>&1); then
      fail "$name: passed"
    elif ! grep -q "ERROR    $variable:" <<<"$out"; then
      fail "$name: does not name $variable: $out"
    else
      echo "ok   $name"
    fi
  }
  bad 'a test switch fails' QB_TRANSFER_RUN_CRON='0 * * * * *' QB_TRANSFER_RUN_CRON
  bad 'the development credit fails' QUIZBUKS_WALLET_DEVCREDIT_ENABLED=true QUIZBUKS_WALLET_DEVCREDIT_ENABLED
  bad "a local .env's Stripe placeholders fail" QB_STRIPE_PLACEHOLDERS=true QB_STRIPE_PLACEHOLDERS
  bad "QuizBuks's test seal key fails" \
    JABIZ_SEAL_KEY=dGVzdC1vbmx5LXNlYWwta2V5LW9mLXRoZS1xdWl6YnVrcy10ZXN0cy0wMDAwMDAw JABIZ_SEAL_KEY
  bad 'no monitoring fails' COMPOSE_PROFILES COMPOSE_PROFILES
  bad 'no metrics fail' MANAGEMENT_OTLP_METRICS_EXPORT_URL MANAGEMENT_OTLP_METRICS_EXPORT_URL
fi

if ((failures > 0)); then
  echo "$failures failed"
  exit 1
fi
echo "all passed"
