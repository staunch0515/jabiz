#!/usr/bin/env bash
# Tests of tools/quizbuks/e2e.sh, offline: the environment it gives the application is app.env's (the file CI reads
# too), with this machine's ports and the secrets hidden; the workflow reads the same file; the script parses.
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
script=$root/tools/quizbuks/e2e.sh
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

check() {
  if eval "$2"; then echo "ok: $1"; else echo "FAIL: $1"; failures=$((failures + 1)); fi
}

bash -n "$script" && echo "ok: the script parses"

# The environment, as the run sets it, with ports of this test's own.
out=$(QB_E2E_STATE="$work/state" QB_E2E_PORT=18080 QB_E2E_SMTP_PORT=11025 QB_E2E_DB_PORT=15440 "$script" env)

# Every key of app.env is set (the values CI uses), except those the machine's ports replace.
while IFS='=' read -r key _; do
  [[ -z $key || $key == \#* ]] && continue
  check "app.env's $key is given to the application" "grep -q '^$key=' <<< \"\$out\""
done < "$root/quizbuks-web/e2e/app.env"
check "the fixed values are app.env's" "grep -qx 'QB_ADMIN_ROLES=ADMIN,QB_ADMIN_SUPER,QB_ADMIN_CONTENT,QB_ADMIN_FINANCE,QB_E2E_PUBLISHER,QB_E2E_FINANCE,QB_E2E_CONTENT' <<< \"\$out\""
check "the analytics job runs every minute, with the e2e switch" "grep -qx 'QB_ANALYTICS_RUN_CRON=15 \* \* \* \* \*' <<< \"\$out\" && grep -qx 'QB_E2E_ENABLED=true' <<< \"\$out\""

# This machine's ports replace CI's where they differ.
check "the application's port" "grep -qx 'SERVER_PORT=18080' <<< \"\$out\""
check "the links follow the port" "grep -qx 'QB_SPONSOR_LINK=http://localhost:18080/sponsor/' <<< \"\$out\" && grep -qx 'JABIZ_MAIL_BASE_URL=http://localhost:18080' <<< \"\$out\""
check "the mail port" "grep -qx 'SPRING_MAIL_PORT=11025' <<< \"\$out\""
check "the database" "grep -qx 'SPRING_R2DBC_URL=r2dbc:postgresql://localhost:15440/quizbuks_e2e' <<< \"\$out\""
check "the tests' addresses" "grep -qx 'E2E_BASE_URL=http://localhost:18080' <<< \"\$out\" && grep -qx 'E2E_MERCHANT_SMTP_PORT=1026' <<< \"\$out\""

# The keys are made once, kept for the next run, and never printed.
check "the keys are kept in the state directory" "test -s '$work/state/env' && grep -q '^JABIZ_INTEGRITY_KEY=' '$work/state/env'"
check "the keys are hidden" "grep -qx 'JABIZ_INTEGRITY_KEY=\*\*\*' <<< \"\$out\" && grep -qx 'STRIPE_WEBHOOK_SECRET=\*\*\*' <<< \"\$out\" && ! grep -q 'whsec_[0-9a-f]' <<< \"\$out\""
again=$(QB_E2E_STATE="$work/state" "$script" env)
check "a second run reads the same keys" "diff <(grep -c '' '$work/state/env') <(echo 7) >/dev/null && [[ \"\$again\" == *'JABIZ_INTEGRITY_KEY=***'* ]]"

# CI reads the same file: the workflow sources app.env instead of listing the values itself.
workflow=$root/.github/workflows/quizbuks.yml
check "the workflow reads app.env" "grep -q 'quizbuks-web/e2e/app.env' '$workflow'"
check "the workflow does not repeat app.env's values" "! grep -q 'QB_ADMIN_ROLES=ADMIN' '$workflow' && ! grep -q 'QB_ANALYTICS_RUN_CRON' '$workflow'"

# Unknown options are refused; playwright's arguments go after --.
check "an unknown option is refused" "! QB_E2E_STATE='$work/state' '$script' run --nope >/dev/null 2>&1"

if [[ $failures -gt 0 ]]; then echo "$failures failure(s)"; exit 1; fi
echo "all passed"
