#!/usr/bin/env bash
# The finance performance probe (docs/finance/perf.md, FIN-NF-001/002): sets up the books of a running finance
# application through its API (finance setup, fiscal years, ACCOUNTS accounts), generates YEARS years of LINES ledger
# lines each straight into its database (generate.sql), then times the reports and a 50-line posting through the API.
#
#   BASE_URL=http://localhost:8080 ADMIN_USER=admin ADMIN_PASSWORD=… \
#     PGHOST=localhost PGUSER=… PGPASSWORD=… PGDATABASE=finance_perf \
#     YEARS=1 LINES=2500000 ACCOUNTS=600 RUNS=20 tools/finance/perf/probe.sh
#
# The database is libpq's (PG* variables, or ~/.pgpass) and must be one of its own, named with "perf": the generated
# rows bypass the processes and can never be removed (the tables only grow). The application must run with
# JABIZ_SECURITY_MFA_ADMINISTRATION=false, as for the end-to-end tests, for the administrator to set the books up.
# GENERATE=false only measures again. Secrets stay out of command lines: curl and jq read them from files and the
# environment.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
BASE_URL=${BASE_URL:-http://localhost:8080}
YEARS=${YEARS:-1}
LINES=${LINES:-2500000}
ACCOUNTS=${ACCOUNTS:-600}
RUNS=${RUNS:-20}
LAST_YEAR=${LAST_YEAR:-2026}
GENERATE=${GENERATE:-true}
: "${ADMIN_USER:?}" "${ADMIN_PASSWORD:?}" "${PGDATABASE:?set PGDATABASE (and the other PG* variables)}"
export ADMIN_USER ADMIN_PASSWORD
if [[ "$PGDATABASE" != *perf* ]]; then
  echo "probe: PGDATABASE '$PGDATABASE' is not a probe database (its name must contain 'perf')" >&2
  exit 2
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
jq -n '{userName: env.ADMIN_USER, password: env.ADMIN_PASSWORD}' > "$work/login.json"
curl -sf "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' --data @"$work/login.json" \
  | jq -r '"header = \"Authorization: Bearer " + .accessToken + "\""' > "$work/auth.conf"
rm "$work/login.json"

run() { # process, input [, accepted status] → output JSON on stdout
  local status
  printf '%s' "$2" > "$work/in.json"
  status=$(curl -s -o "$work/out.json" -w '%{http_code}' --config "$work/auth.conf" \
    "$BASE_URL/api/processes/$1/latest" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" --data @"$work/in.json")
  if [[ "$status" != 200 && "$status" != "${3:-200}" ]]; then echo "$1: $status $(cat "$work/out.json")" >&2; exit 1; fi
  cat "$work/out.json"
}

if [[ "$GENERATE" == true ]]; then
  run FIN_SETUP '{}' > /dev/null
  for ((y = LAST_YEAR - YEARS + 1; y <= LAST_YEAR; y++)); do
    run FIN_FISCAL_YEAR_CREATE "{\"fiscalYear\": $y, \"adjustmentPeriod\": true}" 422 > /dev/null
  done
  # ACCOUNTS accounts across five types: 1000… assets, 2000… liabilities, 3000… equity, 4000… revenue, 6000… expenses.
  types=(ASSET:DEBIT:1 LIABILITY:CREDIT:2 EQUITY:CREDIT:3 REVENUE:CREDIT:4 EXPENSE:DEBIT:6)
  for ((i = 0; i < ACCOUNTS; i++)); do
    IFS=: read -r type balance digit <<< "${types[$((i % 5))]}"
    code=$(printf '%s%03d' "$digit" $((i / 5)))
    run FIN_ACCOUNT_CREATE "{\"accountCode\": \"$code\", \"accountName\": \"Account $code\", \"financialType\": \"$type\",
      \"normalBalance\": \"$balance\", \"statementLine\": \"Line $digit\"}" 422 > /dev/null
  done
  for ((y = LAST_YEAR - YEARS + 1; y <= LAST_YEAR; y++)); do
    start=$(date +%s)
    psql -q -v year="$y" -v lines="$LINES" -v seed="0.$y" -f "$here/generate.sql"
    echo "generated $y in $(( $(date +%s) - start )) s"
  done
fi

# One call: "<seconds> <status>".
time_call() {
  printf '%s' "$2" > "$work/body.json"
  curl -s -o /dev/null -w '%{time_total} %{http_code}\n' --config "$work/auth.conf" "$BASE_URL$1" \
    -H 'Content-Type: application/json' --data @"$work/body.json"
}
summary() { # lines of "<seconds> <status>" → percentiles of the successful ones and the count of the others
  local input ok failed
  input=$(cat)
  ok=$(awk '$2 == 200 { print $1 }' <<< "$input" | sort -n | awk '{ v[NR] = $1 } END { if (NR > 0)
    printf "p50 %.3f s, p95 %.3f s, max %.3f s (%d runs)", v[int((NR + 1) / 2)], v[int(NR * 0.95 + 0.999)], v[NR], NR }')
  failed=$(awk '$2 != 200 { print "HTTP " $2 " after " $1 " s" }' <<< "$input" | sort | uniq -c | sed 's/^ *//' | paste -sd ';' -)
  echo "${ok:-no successful run}${failed:+; refused or timed out: $failed}"
}
measure() { # name, path, body
  time_call "$2" "$3" > /dev/null # warm-up
  echo "$1: $(for ((r = 0; r < RUNS; r++)); do time_call "$2" "$3"; done | summary)"
}

psql -At -c "SELECT 'ledger lines: ' || count(*) FROM ledger_entry_version" \
  -c "SELECT 'transactions: ' || count(*) FROM ledger_transaction_version"
busy=$(psql -At -c "SELECT a.account_code FROM ledger_entry_version e JOIN ledger_account_version a
  ON a.account_id = e.account_id GROUP BY 1 ORDER BY count(*) DESC LIMIT 1")
measure "trial balance through $LAST_YEAR-11-30" /api/queries/finance.gl.trial_balance \
  "{\"params\": {\"through\": \"$LAST_YEAR-11-30\"}, \"limit\": 500}"
measure "account inquiry $busy, November $LAST_YEAR" /api/queries/finance.gl.account_inquiry \
  "{\"params\": {\"account\": \"$busy\", \"from\": \"$LAST_YEAR-11-01\", \"to\": \"$LAST_YEAR-11-30\"}, \"limit\": 500}"

# A 50-line entry saved and submitted (posts at once: under the approval limit).
mapfile -t codes < <(psql -At -c "SELECT account_code FROM ledger_account_version WHERE account_code ~ '^6[0-9]{3}$'
  ORDER BY account_code LIMIT 49")
lines=$(for c in "${codes[@]}"; do echo "{\"accountCode\": \"$c\", \"debit\": \"10.00\"}"; done | jq -s \
  '. + [{"accountCode": "2000", "credit": "490.00"}]')
post_times=$(for ((r = 0; r < RUNS; r++)); do
  draft=$(run FIN_JOURNAL_SAVE "$(jq -n --argjson l "$lines" --arg d "$LAST_YEAR-11-15" \
    '{postingDate: $d, description: "Probe entry", lines: $l}')" | jq -r .output.journalId)
  time_call /api/processes/FIN_JOURNAL_SUBMIT/latest "{\"journalId\": \"$draft\"}"
done)
echo "submit and post a 50-line entry: $(summary <<< "$post_times")"
