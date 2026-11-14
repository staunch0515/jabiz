#!/usr/bin/env bash
# Tests of tools/quizbuks/ops/launch-day-check.sh with a fake psql and a fake curl (no database, no network): a healthy
# system passes; each fault is a FAIL, a query that fails is never read as "none"; missing inputs are SKIPPED; the
# script only reads (a read-only session running SELECTs, GET and HEAD only) and never asks for a personal column.
set -uo pipefail

root=$(cd "$(dirname "$0")/../../.." && pwd)
script="$root/tools/quizbuks/ops/launch-day-check.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failures=0
pass() { echo "ok   $1"; }
flunk() { echo "FAIL $1"; failures=$((failures + 1)); }

mkdir -p "$work/bin"
# The fake psql answers by what the statement asks; FAULT names one thing to break. It records every statement and
# the session's options, and refuses (exit 3) a statement that is not a SELECT or a WITH.
cat >"$work/bin/psql" <<'EOF'
#!/usr/bin/env bash
sql=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -c) sql=$2; shift 2 ;;
    -f) sql=$(cat "$2"); shift 2 ;;
    *) shift ;;
  esac
done
printf '%s\n---\n' "$sql" >>"$LOG_DIR/sql.log"
printf '%s\n' "${PGOPTIONS:-}" >>"$LOG_DIR/options.log"
first=$(sed -E 's/--[^\n]*//' <<<"$sql" | grep -vE '^[[:space:]]*$' | head -1 | awk '{print toupper($1)}')
[[ "$first" == SELECT || "$first" == WITH || "$first" == "(SELECT" ]] || { echo "not a read: $first" >&2; exit 3; }
fault=${FAULT:-}
answer() { printf '%s\n' "$@"; exit 0; }
[[ "$fault" == query-error && "$sql" == *ledger_account_version* ]] && { echo 'ERROR:  relation does not exist' >&2; exit 1; }
case "$sql" in
  *qb_distribution_price*) [[ "$fault" == price ]] && answer "op-1|0.5|1|20|500|||||"; exit 0 ;;
  *sys_control_change_version*) [[ "$fault" == pending ]] && answer "qb.ai.fee.enabled"; exit 0 ;;
  *"count(*) FROM (SELECT * FROM (SELECT DISTINCT ON (account_id)"*) answer 18 ;;
  *ledger_account_version*) [[ "$fault" == mapping ]] && answer 2190; exit 0 ;;
  *default_for_new*) [[ "$fault" == operator ]] && answer 0; answer 1 ;;
  *indisvalid\ FROM*) [[ "$fault" == index && "$sql" == *qb_ai_job_fee_charge_idx* ]] && answer f; answer t ;;
  *"NOT x.indisvalid"*) answer 0 ;;
  *pg_extension*) answer 1 ;;
  *jit_functions*) [[ "$fault" == jit ]] && answer 3; answer 0 ;;
  *"NOT EXISTS ("*sec_user_mfa_version*) [[ "$fault" == mfa ]] && answer 1; answer 0 ;;
  *require_mfa*) exit 0 ;;
  *"role_code = '"*) [[ "$fault" == super ]] && answer 1; answer 2 ;;
  *"count(DISTINCT user_id)"*) answer 3 ;;
  *sec_protected_grants_armed*) answer 1 ;;
  *unnest*)
    keys=$(grep -oE "ARRAY\[[^]]*\]" <<<"$sql" | grep -oE "'[^']*'" | tr -d "'")
    for k in $keys; do
      v=x
      case "$k" in
        qb.topup.targets|qb.topup.default-target) v=CASH ;;
        qb.creator-fee-rate|qb.transfer.fee-rate|qb.fee.monthly) v=0.0000 ;;
        qb.reward.default-methods) v= ;;
        *.enabled) v=$(grep -A0 "'$k', '[^']*', 'true'" "$TEMPLATE" >/dev/null && echo true || echo false) ;;
      esac
      [[ "$fault" == switch && "$k" == qb.kudos.buy.enabled ]] && v=true
      [[ "$fault" == issuer && "$k" == qb.issuer.name ]] && { echo "$k||f"; continue; }
      echo "$k|$v|t"
    done
    exit 0 ;;
  *"LIKE 'qb.exposure.%'"*) answer 5 ;;
  *qb_legal_version_version*) [[ "$fault" == legal ]] && answer "PRIVACY|placeholder" "TERMS_APP|real"
    answer "PRIVACY|real" "TERMS_APP|real" "TERMS_SPONSOR|real" ;;
  *qb_topic*) answer "12|0" ;;
  *sys_job_run*) [[ "$fault" == job && "$sql" == *qb.exposure-ends* ]] && answer FAILED; answer SUCCEEDED ;;
  *sys_task_version*) [[ "$fault" == health ]] && answer 1; answer 0 ;;
esac
echo "unexpected statement" >&2
exit 2
EOF
# The fake curl answers a healthy site; it records every call and refuses any method but GET and HEAD, and any body.
cat >"$work/bin/curl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$LOG_DIR/curl.log"
for arg in "$@"; do
  case "$arg" in -X|--request|-d|--data*|-F|--form|-T|--upload-file) echo "not a read: $arg" >&2; exit 3 ;; esac
done
url=${*: -1}
case "$*" in
  *" -I "*) printf 'HTTP/2 200\r\nstrict-transport-security: max-age=63072000\r\n' ;;
  *"%{http_code} %{redirect_url}"*) printf '301 https://quizbuks.example/' ;;
  *"%{http_code}"*) printf '404' ;;
  *entries/admin*) printf '{"name":"admin","environment":{"name":"%s","production":true}}' "${SITE_ENV:-production}" ;;
esac
EOF
chmod +x "$work/bin/psql" "$work/bin/curl"
printf 'Started QuizBuks\nAddress database /data/geo.csv: 12345 ranges\n' >"$work/clean.log"

# Runs the script; prints its output, sets $status.
run_check() {
  rm -f "$work"/*.log.tmp
  out=$(PATH="$work/bin:$PATH" LOG_DIR="$work" TEMPLATE="$root/backend/quizbuks/src/main/resources/queries/qb/admin/first-launch-switches.sql" \
    "$@" "$script" 2>&1)
  status=$?
}
full=(env DATABASE_URL=postgres://reader@db/quizbuks BASE_URL=https://quizbuks.example LOG_FILE="$work/clean.log")

# A healthy system: no error, the exit status says so.
: >"$work/sql.log"; : >"$work/curl.log"; : >"$work/options.log"
run_check "${full[@]}"
if [[ "$status" == 0 && "$out" == *"0 error(s), 0 warning(s)"* && "$out" != *"FAIL "* ]]; then pass "a healthy system passes"
else flunk "a healthy system passes"; echo "$out"; fi

# It only reads: every session is read-only, every statement a SELECT (the fake refuses others), GET and HEAD only.
if grep -qv 'default_transaction_read_only=on' "$work/options.log"; then flunk "every session is read-only"
else pass "every session is read-only"; fi
if [[ "$out" == *"not a read"* ]]; then flunk "only SELECTs and GETs"; else pass "only SELECTs and GETs"; fi
# No personal information is ever asked for.
# (a column name, not a word inside a parameter key such as qb.support.email)
if grep -qiE '(^|[^.a-z_])(email|display_name|user_name|contact_name|contact_email|registered_email|verified_email)\b' "$work/sql.log"
then flunk "no personal column is read"; else pass "no personal column is read"; fi

# Each fault is a FAIL on its item (and the exit status is 1).
expect_fail() {
  local fault=$1 item=$2
  FAULT=$fault run_check "${full[@]}" FAULT="$fault"
  if [[ "$status" == 1 ]] && grep -qE "^FAIL \| $item" <<<"$out"; then pass "fault $fault: FAIL on $item"
  else flunk "fault $fault: FAIL on $item"; echo "$out" | grep -E "FAIL|$item"; fi
}
expect_fail price '2.1 distribution price'
expect_fail mapping '2.2 account mapping'
expect_fail query-error '2.2 account mapping'
expect_fail operator '2.2 default operator'
expect_fail index '2.2 indexes'
expect_fail jit '2.2 JIT'
expect_fail mfa '2.3 second factor'
expect_fail super '2.3 super administrators'
expect_fail switch '2.4 first launch switches'
expect_fail issuer '2.4 issuer'
expect_fail legal '2.5 legal texts'
expect_fail job '2.6 job qb.exposure-ends'
expect_fail health "2.6 operators' structure"

# A proposal waiting is a warning, not an error.
FAULT=pending run_check "${full[@]}" FAULT=pending
[[ "$status" == 0 && "$out" == *"WARN | 2.2 controlled changes"* ]] && pass "a waiting proposal warns" \
  || flunk "a waiting proposal warns"

# The banner: the site runs as another system than expected.
run_check "${full[@]}" SITE_ENV=staging
[[ "$status" == 1 && "$out" == *"FAIL | 2.3 environment banner"* ]] && pass "the wrong environment fails" \
  || flunk "the wrong environment fails"
# On a test system the first launch's values are not proposed: skipped, not compared.
run_check "${full[@]}" SITE_ENV=dev EXPECT_ENVIRONMENT=dev
[[ "$out" == *"SKIP | 2.4 first launch switches"* ]] && pass "a test system skips the first launch's values" \
  || flunk "a test system skips the first launch's values"

# The startup log: an error line is a FAIL.
printf 'Platform check failed: 1 error(s)\n' >"$work/bad.log"
run_check env DATABASE_URL= BASE_URL= LOG_FILE="$work/bad.log"
[[ "$status" == 1 && "$out" == *"FAIL | 2.x startup log"*"Platform check failed"* ]] && pass "the startup log's error fails" \
  || flunk "the startup log's error fails"

# Nothing given: everything skipped, nothing failed.
run_check env -u DATABASE_URL -u BASE_URL -u ENV_FILE -u LOG_FILE -u LOG_CMD
[[ "$status" == 0 && $(grep -c '^SKIP' <<<"$out") == 4 ]] && pass "missing inputs are skipped" \
  || { flunk "missing inputs are skipped"; echo "$out"; }

if [[ "$failures" -gt 0 ]]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
