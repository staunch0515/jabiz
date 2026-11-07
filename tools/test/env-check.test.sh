#!/usr/bin/env bash
# Tests of tools/ops/env-check.sh (ROADMAP 17zx): the read-only check of production settings.
set -euo pipefail

script="$(cd "$(dirname "$0")/.." && pwd)/ops/env-check.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

k() { printf 'key-%s-of-the-env-check-tests-000000000' "$1" | base64 -w0; }
good() {
  cat <<END
JABIZ_JWT_SECRET=$(k jwt)
JABIZ_INTEGRITY_KEY=$(k integrity)
JABIZ_MFA_KEY=$(k mfa)
JABIZ_SEAL_KEY="$(k seal)"
JABIZ_TRUSTED_PROXIES=10.0.0.0/8
JABIZ_WEB_ENVIRONMENT=production
JABIZ_FILES_LOCAL_ROOT=/var/lib/jabiz/files
JABIZ_ALERT_EMAIL=oncall@shop.test
GF_SMTP_ENABLED=true
SPRING_PROFILES_ACTIVE=prod
END
}

# expect <exit> <name> <grep pattern or -> <settings...>: runs the check on the good settings plus these lines.
expect() {
  local want=$1 name=$2 pattern=$3 out status
  shift 3
  { good; printf '%s\n' "$@"; } >"$work/env"
  set +e
  out=$("$script" "$work/env" ${RULES:+"$RULES"} 2>&1)
  status=$?
  set -e
  if [[ "$status" != "$want" ]] || { [[ "$pattern" != "-" ]] && ! grep -q -- "$pattern" <<<"$out"; }; then
    echo "FAIL $name (exit $status, want $want)"; echo "$out" | sed 's/^/     /'
    failures=$((failures + 1))
  elif grep -q "key-.*-of-the-env-check" <<<"$out" || grep -q "$(k jwt)" <<<"$out"; then
    echo "FAIL $name: a value was printed"; failures=$((failures + 1))
  else
    echo "ok   $name"
  fi
}

expect 0 'good settings pass' '0 error(s)'
expect 1 'the same key twice' 'JABIZ_MFA_KEY: is the same as JABIZ_JWT_SECRET' "JABIZ_MFA_KEY=$(k jwt)"
expect 1 'a test key of the repository' 'is a test key' \
  'JABIZ_INTEGRITY_KEY=dGVzdC1vbmx5LWludGVncml0eS1rZXktb2YtdGhlLWphYml6LXRlc3RzLTAwMDAwMA=='
expect 1 'a short key' 'shorter than 32' "JABIZ_JWT_SECRET=$(printf short | base64)"
expect 1 'the dev profile' 'contains dev' 'SPRING_PROFILES_ACTIVE=prod,dev'
expect 1 'the first administrator left in' 'JABIZ_BOOTSTRAP_ADMIN_PASSWORD' 'JABIZ_BOOTSTRAP_ADMIN_PASSWORD=x'
expect 1 'the second factor turned off' 'JABIZ_SECURITY_MFA_ADMINISTRATION' 'JABIZ_SECURITY_MFA_ADMINISTRATION=false'
expect 1 'the test system banner' 'JABIZ_WEB_ENVIRONMENT' 'JABIZ_WEB_ENVIRONMENT=staging'
expect 1 'the placeholder on-call address' 'JABIZ_ALERT_EMAIL' 'JABIZ_ALERT_EMAIL=oncall@example.com'
expect 0 'a rotation in progress is a warning' 'JABIZ_MFA_KEY_PREVIOUS' "JABIZ_MFA_KEY_PREVIOUS=$(k old)"

cat >"$work/rules" <<'END'
# QuizBuks-like rules
absent QB_WALLET_DEV_CREDIT
equals QB_STRIPE_PLACEHOLDERS false
set STRIPE_API_KEY
END
RULES="$work/rules" expect 1 "the application's rules" 'QB_STRIPE_PLACEHOLDERS: must be false' 'QB_WALLET_DEV_CREDIT=true'
RULES="$work/rules" expect 0 "the application's rules met" '0 error(s)' 'QB_STRIPE_PLACEHOLDERS=false' \
  'STRIPE_API_KEY=sk_live_x'

# --app-only (ROADMAP 17zz3): a settings file that is not the application's, with the new rule kinds.
cat >"$work/backup-rules" <<'END'
set BUCKET
min-length PASS_A 32
min-length PASS_B 32
different PASS_A PASS_B
warn-unset PING_URL
END
pass() { printf 'passphrase-%s-of-the-env-check-tests-00000' "$1"; }
# app_only <exit> <name> <pattern> <settings...>: only the rules, on these lines alone.
app_only() {
  local want=$1 name=$2 pattern=$3 out status
  shift 3
  printf '%s\n' "$@" >"$work/backup.env"
  set +e
  out=$("$script" --app-only "$work/backup.env" "$work/backup-rules" 2>&1)
  status=$?
  set -e
  if [[ "$status" != "$want" ]] || ! grep -q -- "$pattern" <<<"$out"; then
    echo "FAIL $name (exit $status, want $want)"; echo "$out" | sed 's/^/     /'; failures=$((failures + 1))
  elif grep -q "passphrase-" <<<"$out"; then
    echo "FAIL $name: a value was printed"; failures=$((failures + 1))
  else
    echo "ok   $name"
  fi
}
app_only 0 'app-only: no platform rules, a recommendation is a warning' '0 error(s), 1 warning' \
  BUCKET=b "PASS_A=$(pass a)" "PASS_B=$(pass b)"
app_only 0 'app-only: all met' '0 error(s), 0 warning' BUCKET=b "PASS_A=$(pass a)" "PASS_B=$(pass b)" PING_URL=x
app_only 1 'app-only: a short passphrase' 'PASS_A: shorter than 32' BUCKET=b PASS_A=short "PASS_B=$(pass b)"
app_only 1 'app-only: the same passphrase twice' 'PASS_A: is the same as PASS_B' BUCKET=b "PASS_A=$(pass a)" \
  "PASS_B=$(pass a)"
app_only 1 'app-only: a missing passphrase' 'PASS_B: not set' BUCKET=b "PASS_A=$(pass a)"
set +e
"$script" --app-only "$work/backup.env" >/dev/null 2>&1
status=$?
set -e
if [[ "$status" == 2 ]]; then echo "ok   app-only needs a rules file"; else echo "FAIL app-only without rules ($status)"; failures=$((failures + 1)); fi

if ((failures > 0)); then
  echo "$failures failed"
  exit 1
fi
