#!/usr/bin/env bash
# Read-only dry run of the launch checklist's deployment day (docs/quizbuks/launch-checklist.md, part 2): what can be
# confirmed by reading the database, the site, the settings file and the startup log is confirmed here, item by item;
# the rest is listed at the end for a person. Run it on staging before the day, and on the real system on the day.
#
#   DATABASE_URL=postgres://reader@host/quizbuks BASE_URL=https://quizbuks.com ENV_FILE=/opt/apps/quizbuks/.env \
#   LOG_FILE=startup.log EXPECT_ENVIRONMENT=production tools/quizbuks/ops/launch-day-check.sh
#
# Every input is optional: what it is missing is SKIPPED, never guessed.
#   DATABASE_URL        the application's database (a read-only login is best)
#   BASE_URL            the site, as visitors reach it
#   ENV_FILE            the settings file: tools/ops/env-check.sh with deploy/quizbuks/server/env-check.rules
#   LOG_FILE / LOG_CMD  the application's startup log, as a file or a command printing it (docker compose logs app)
#   EXPECT_ENVIRONMENT  production (no banner) or the name of a test system (a banner); default production
#
# It writes nothing and turns nothing on: the database session is read-only (default_transaction_read_only) and runs
# SELECTs only, the site is asked with GET and HEAD only, no process is started. It prints no secret and no personal
# information: administrators are counted, never named. The expected values come from the code and the data (the
# first launch's switches from the template qb.admin.first-launch-switches, the accounts from the ledger's chart, the
# indexes built concurrently from the migrations), never from numbers written here.
#
# One line an item: OK | WARN | FAIL | SKIP, the checklist's item, what was found. Exit 1 when anything FAILed.
set -uo pipefail

root=$(cd "$(dirname "$0")/../../.." && pwd)
expect_env=${EXPECT_ENVIRONMENT:-production}
fails=0
warns=0

line() { printf '%-4s | %-28s | %s\n' "$1" "$2" "$3"; }
ok() { line OK "$1" "$2"; }
warn() { line WARN "$1" "$2"; warns=$((warns + 1)); }
fail() { line FAIL "$1" "$2"; fails=$((fails + 1)); }
skip() { line SKIP "$1" "$2"; }

# ---- The database --------------------------------------------------------------------------------------------------

# A read-only session that runs one statement and prints its rows unaligned, fields split by '|'. On an error it prints
# nothing, keeps the database's first error line in $query_error and fails, so that no check reads an error as "none".
query_error=""
q() {
  local out errfile status
  errfile=$(mktemp)
  out=$(PGOPTIONS="-c default_transaction_read_only=on -c statement_timeout=30s" \
    psql "$DATABASE_URL" -X -A -t -q -v ON_ERROR_STOP=1 -c "$1" 2>"$errfile")
  status=$?
  query_error=$(grep -m1 -E 'ERROR|FATAL|could not' "$errfile")
  rm -f "$errfile"
  [[ "$status" == 0 ]] || return 1
  printf '%s' "$out"
}

# The current version of the rows of a temporal table, as the platform reads them ($1 table, $2 its key column).
current() {
  echo "(SELECT * FROM (SELECT DISTINCT ON ($2) * FROM $1 WHERE effect_start_time <= now()
    ORDER BY $2, effect_start_time DESC, version_no DESC) c WHERE NOT c.is_deleted)"
}
params="$(current sys_param_version param_id)"

# Whether a stored value is the expected one: numbers by value (a rate is stored as 0.0000), the rest as text.
same() {
  local number='^-?[0-9]+(\.[0-9]+)?$'
  if [[ "$1" =~ $number && "$2" =~ $number ]]; then awk -v a="$1" -v b="$2" 'BEGIN { exit !(a + 0 == b + 0) }'
  else [[ "$1" == "$2" ]]; fi
}

# The value in force of each parameter named on stdin (one a line), as "key|value|present" lines.
param_values() {
  local keys
  keys=$(sed "s/.*/'&'/" | paste -sd,)
  q "SELECT k, coalesce(p.param_value, ''), (p.param_key IS NOT NULL)
    FROM unnest(ARRAY[$keys]::text[]) k LEFT JOIN $params p ON p.param_key = k"
}

database_checks() {
  local n rows total item

  # 2.1: operators whose distribution price is outside the headquarters' range (the script the checklist names).
  item="2.1 distribution price range"
  if rows=$(q "$(cat "$root/tools/quizbuks/sql/distribution-price-range.sql")"); then
    n=$(grep -c . <<<"$rows")
    if [[ "$n" == 0 ]]; then ok "$item" "every operator's price is within the range"
    else fail "$item" "$n operator(s) outside the range (operations.md §7.48)"; fi
  else fail "$item" "the query failed: $query_error"; fi

  # 2.2: proposals still waiting for the second administrator (the third part's switches wait on purpose).
  item="2.2 controlled changes"
  if rows=$(q "SELECT coalesce(target_key, target_entity) FROM $(current sys_control_change_version change_id) c
      WHERE status = 'PROPOSED' ORDER BY 1"); then
    if [[ -z "$rows" ]]; then ok "$item" "nothing waits for publishing"
    else warn "$item" "$(grep -c . <<<"$rows") proposal(s) wait: $(paste -sd, <<<"$rows") (part 3's switches wait on purpose)"; fi
  else fail "$item" "the query failed: $query_error"; fi

  # 2.2: every account of the ledger's chart has its mapping for the journal export.
  item="2.2 account mapping"
  if rows=$(q "SELECT a.account_code FROM $(current ledger_account_version account_id) a
      WHERE NOT EXISTS (SELECT 1 FROM $params p WHERE p.param_key = 'qb.journal.account.' || a.account_code
        AND coalesce(p.param_value, '') <> '') ORDER BY 1") \
     && total=$(q "SELECT count(*) FROM $(current ledger_account_version account_id) a"); then
    if [[ "$total" == 0 ]]; then fail "$item" "the ledger has no chart of accounts"
    elif [[ -z "$rows" ]]; then ok "$item" "all $total accounts of the chart are mapped"
    else fail "$item" "no mapping for $(paste -sd, <<<"$rows") (QB_JOURNAL_ACCOUNT_UNMAPPED)"; fi
  else fail "$item" "the query failed: $query_error"; fi

  # 2.2: the default operator, without which nobody can sign up as a sponsor.
  item="2.2 default operator"
  if n=$(q "SELECT count(*) FROM qb_operator WHERE default_for_new AND status = 'ACTIVE'"); then
    if [[ "$n" -ge 1 ]]; then ok "$item" "an active default operator exists"
    else fail "$item" "no active default operator (QB_OPERATOR_DEFAULT_MISSING)"; fi
  else fail "$item" "the query failed: $query_error"; fi

  # 2.2: the indexes the migrations build outside a transaction (V66, V127, V129, V130 ...) exist and are valid, and
  # no index of the schema is invalid.
  item="2.2 indexes"
  local built name valid problems=""
  built=$(grep -lE '^executeInTransaction=false' "$root"/backend/*/src/main/resources/db/*/*.sql.conf 2>/dev/null \
    | sed 's/\.conf$//' | xargs -r cat | grep -vE '^[[:space:]]*--' \
    | grep -oiE 'CREATE +(UNIQUE +)?INDEX +(CONCURRENTLY +|\$\{concurrently\} +)?(IF NOT EXISTS +)?[a-z0-9_]+' \
    | awk '{print $NF}' | sort -u)
  for name in $built; do
    if valid=$(q "SELECT x.indisvalid FROM pg_index x JOIN pg_class c ON c.oid = x.indexrelid
        WHERE c.relname = '$name' AND c.relnamespace = current_schema()::regnamespace"); then
      [[ "$valid" == t ]] || problems+="$name(${valid:-missing}) "
    else problems+="$name(query failed) "; fi
  done
  if [[ -z "$built" ]]; then fail "$item" "no index built outside a transaction found in the migrations"
  elif ! n=$(q "SELECT count(*) FROM pg_index x JOIN pg_class c ON c.oid = x.indexrelid
      WHERE NOT x.indisvalid AND c.relnamespace = current_schema()::regnamespace"); then
    fail "$item" "the query failed: $query_error"
  elif [[ -z "$problems" && "$n" == 0 ]]; then
    ok "$item" "the $(wc -w <<<"$built") index(es) built concurrently are valid; none invalid"
  else fail "$item" "${problems:+not valid: $problems; }$n invalid index(es) in the schema (see the migration's comment)"; fi

  # 2.2: the application's statements are not compiled by JIT (needs pg_stat_statements; operations.md §7.60).
  item="2.2 JIT"
  if [[ "$(q "SELECT count(*) FROM pg_extension WHERE extname = 'pg_stat_statements'")" == 1 ]]; then
    if n=$(q "SELECT count(*) FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid
        WHERE d.datname = current_database() AND s.jit_functions > 0"); then
      if [[ "$n" == 0 ]]; then ok "$item" "no statement of this database was compiled by JIT"
      else fail "$item" "$n statement(s) compiled by JIT: the connection's jit=off is overridden"; fi
    else fail "$item" "the query failed: $query_error"; fi
  else skip "$item" "pg_stat_statements is not installed (the settings file is checked below)"; fi

  # 2.3: the administrators (users of the administration's roles) have a second factor; two super administrators.
  local admin_roles super_role roles_sql users_sql
  admin_roles=$(grep -oE 'QB_ADMIN_ROLES:[A-Z_,]+' "$root/backend/quizbuks/src/main/resources/application.yml" \
    | head -1 | cut -d: -f2)
  super_role=$(grep -oE 'ADMIN_SUPER *= *"[A-Z_]+"' "$root/backend/quizbuks/src/main/java/com/jabiz/quizbuks/setup/QbRoles.java" \
    | head -1 | grep -oE '"[A-Z_]+"' | tr -d '"')
  if [[ -z "$admin_roles" || -z "$super_role" ]]; then
    fail "2.3 administrators" "the administration's roles could not be read from the code"
  else
    roles_sql="'$(sed "s/,/','/g" <<<"$admin_roles")'"
    users_sql="SELECT DISTINCT ur.user_id, r.role_code FROM $(current sec_user_role_version user_role_id) ur
      JOIN $(current sec_role_version role_id) r ON r.role_id = ur.role_id
      JOIN $(current sec_user_version user_id) u ON u.user_id = ur.user_id
      WHERE u.enabled AND u.erased_at IS NULL AND r.role_code IN ($roles_sql)"
    item="2.3 second factor"
    if n=$(q "SELECT count(DISTINCT a.user_id) FROM ($users_sql) a WHERE NOT EXISTS (
          SELECT 1 FROM $(current sec_user_mfa_version user_mfa_id) m WHERE m.user_id = a.user_id AND m.confirmed)") \
       && total=$(q "SELECT count(DISTINCT user_id) FROM ($users_sql) a"); then
      if [[ "$n" == 0 ]]; then ok "$item" "all $total administrator(s) have a second factor"
      else fail "$item" "$n of $total administrator(s) have no second factor"; fi
    else fail "$item" "the query failed: $query_error"; fi
    item="2.3 roles require MFA"
    if rows=$(q "SELECT role_code FROM $(current sec_role_version role_id) r WHERE role_code IN ($roles_sql)
        AND NOT coalesce(require_mfa, false) ORDER BY 1"); then
      if [[ -z "$rows" ]]; then ok "$item" "every administration role requires a second factor"
      else warn "$item" "roles without 'require a second factor': $(paste -sd, <<<"$rows")"; fi
    else fail "$item" "the query failed: $query_error"; fi
    item="2.3 super administrators"
    if n=$(q "SELECT count(DISTINCT user_id) FROM ($users_sql) a WHERE role_code = '$super_role'"); then
      if [[ "$n" -ge 2 ]]; then ok "$item" "$n users hold $super_role"
      else fail "$item" "$n user(s) hold $super_role (at least two, D51)"; fi
    else fail "$item" "the query failed: $query_error"; fi
  fi
  item="2.3 protected grants"
  if n=$(q "SELECT count(*) FROM sec_protected_grants_armed"); then
    if [[ "$n" == 1 ]]; then ok "$item" "the setting-up period is over (armed)"
    else fail "$item" "not armed: the setting-up period has not ended (operations.md §3 steps 4-5)"; fi
  else fail "$item" "the query failed: $query_error"; fi

  # 2.4: the first launch's switches against the template the finance page's card reads (its VALUES are the registry
  # setup.FirstLaunchSwitches; FirstLaunchSwitchesTemplateTest keeps them equal). An amount (NULL) is only required.
  # QB_SETUP proposes these values only on the environments the registry names (production and staging).
  item="2.4 first launch switches"
  local template="$root/backend/quizbuks/src/main/resources/queries/qb/admin/first-launch-switches.sql"
  local environments wanted values key expected value present absent="" mismatched=""
  environments=$(grep -oE 'ENVIRONMENTS *= *Set\.of\([^)]*\)' \
    "$root/backend/quizbuks/src/main/java/com/jabiz/quizbuks/setup/FirstLaunchSwitches.java" | grep -oE '"[a-z]+"' | tr -d '"')
  wanted=$(sed -nE "s/^ *\('([^']*)', '[^']*', (CAST\(NULL AS text\)|'[^']*'), [0-9]+\)[,)]*$/\1|\2/p" "$template")
  if ! grep -qx "$expect_env" <<<"$environments"; then
    skip "$item" "QB_SETUP proposes the first launch's values only on: $(paste -sd, <<<"$environments")"
  elif [[ -z "$wanted" ]]; then fail "$item" "the template's expected values could not be read"
  elif ! values=$(cut -d'|' -f1 <<<"$wanted" | param_values); then fail "$item" "the query failed: $query_error"
  else
    while IFS='|' read -r key expected; do
      IFS='|' read -r _ value present <<<"$(grep -m1 "^$key|" <<<"$values")"
      if [[ "$present" != t ]]; then absent+="$key "
      elif [[ "$expected" != "CAST(NULL AS text)" ]] && ! same "$value" "${expected//\'/}"; then mismatched+="$key "; fi
    done <<<"$wanted"
    if [[ -n "$mismatched" ]]; then fail "$item" "not the first launch's value: $mismatched"
    else ok "$item" "every switch in force has the first launch's value"; fi
    [[ -z "$absent" ]] || warn "$item" "not in force yet (to publish when their part 3 is done): $absent"
  fi

  # 2.4, 2.5: parameters that must be set (their values are not printed).
  item="2.4 issuer, thresholds, mailbox"
  if values=$(printf '%s\n' qb.issuer.name qb.issuer.address qb.prepayment.release.overdue-days \
      qb.audience.refund.missing-after-hours qb.support.email | param_values); then
    rows=$(awk -F'|' '$2 == "" {print $1}' <<<"$values")
    if [[ -z "$rows" ]]; then ok "$item" "the issuer, the two reconciliation thresholds and the support mailbox are set"
    else fail "$item" "not set: $(paste -sd' ' <<<"$rows")"; fi
  else fail "$item" "the query failed: $query_error"; fi

  # 2.5: the legal texts in effect are the real ones.
  item="2.5 legal texts"
  local legal="$(current qb_legal_version_version legal_version_id)"
  if rows=$(q "SELECT k.kind, coalesce((SELECT CASE WHEN position('[TEST PLACEHOLDER]' IN v.body::text) > 0
        THEN 'placeholder' ELSE 'real' END FROM $legal v
        WHERE v.kind = k.kind AND v.published_at IS NOT NULL AND v.effective_at <= now()
        ORDER BY v.effective_at DESC, v.legal_version_no DESC LIMIT 1), 'none')
      FROM (SELECT DISTINCT kind FROM $legal) k ORDER BY 1"); then
    [[ -n "$rows" ]] || fail "$item" "no legal text at all"
    while IFS='|' read -r kind state; do
      [[ -z "$kind" ]] && continue
      case "$state" in
        real) ok "$item" "$kind: the version in effect is a real text" ;;
        placeholder) fail "$item" "$kind: the version in effect is the test placeholder" ;;
        *) fail "$item" "$kind: no version in effect" ;;
      esac
    done <<<"$rows"
  else fail "$item" "the query failed: $query_error"; fi

  # 2.6: the features' settings that QB_SETUP creates.
  item="2.6 feature settings"
  if values=$(printf '%s\n' qb.feed.shorts.positions qb.feed.shorts.per-shelf qb.topic.max-per-quiz \
        qb.topic.custom-max-length qb.block.max-per-user qb.block.count-min-group | param_values) \
     && n=$(q "SELECT count(*) FROM $params p WHERE param_key LIKE 'qb.exposure.%'"); then
    rows=$(awk -F'|' '$3 != "t" {print $1}' <<<"$values")
    if [[ -z "$rows" && "$n" -ge 1 ]]; then ok "$item" "shorts, topics, blocks and $n exposure setting(s) exist"
    else fail "$item" "missing: $(paste -sd' ' <<<"$rows"); $n exposure setting(s)"; fi
  else fail "$item" "the query failed: $query_error"; fi
  item="2.6 topics"
  if rows=$(q "SELECT count(*), count(*) FILTER (WHERE coalesce(name ->> 'zh', '') = ''
        OR coalesce(name ->> 'ja', '') = '' OR coalesce(name ->> 'en', '') = '')
      FROM qb_topic WHERE active AND merged_into IS NULL"); then
    IFS='|' read -r n total <<<"$rows"
    if [[ "$n" -ge 1 && "$total" == 0 ]]; then ok "$item" "$n topic(s), every one named in three languages"
    else fail "$item" "$n topic(s); $total without a name in each of the three languages"; fi
  else fail "$item" "the query failed: $query_error"; fi

  # 2.6: the jobs the checklist names have run and succeeded lately.
  local job age
  for job in qb.exposure-ends:1 qb.exposure-check:2 qb.operator-check:2; do
    age=${job#*:}; job=${job%%:*}; item="2.6 job $job"
    if rows=$(q "SELECT outcome FROM sys_job_run WHERE job_name = '$job'
        AND finished_time > now() - interval '$age day' ORDER BY finished_time DESC LIMIT 1"); then
      case "$rows" in
        SUCCEEDED|REPLAYED) ok "$item" "its latest run succeeded" ;;
        '') warn "$item" "no run in the last $age day(s) (a new system: look again after its first run)" ;;
        *) fail "$item" "its latest run: $rows" ;;
      esac
    else fail "$item" "the query failed: $query_error"; fi
  done
  item="2.6 operators' structure"
  if n=$(q "SELECT count(*) FROM $(current sys_task_version task_id) t
      WHERE task_type = 'qb.operator-health' AND status = 'OPEN'"); then
    if [[ "$n" == 0 ]]; then ok "$item" "no open task of the operators' daily check"
    else fail "$item" "the operators' daily check found problems (headquarters' task is open; §7.13)"; fi
  else fail "$item" "the query failed: $query_error"; fi
}

# ---- The site ------------------------------------------------------------------------------------------------------

site_checks() {
  local headers code env
  if [[ "$BASE_URL" == https://* ]]; then
    headers=$(curl -sS -I -m 20 "$BASE_URL/" 2>/dev/null || true)
    grep -qi '^strict-transport-security:' <<<"$headers" && ok "2.3 HTTPS" "HSTS is sent" \
      || fail "2.3 HTTPS" "no Strict-Transport-Security header"
    code=$(curl -s -o /dev/null -m 20 -w '%{http_code} %{redirect_url}' "http://${BASE_URL#https://}/" || true)
    [[ "$code" =~ ^30[1278]\ https:// ]] && ok "2.3 HTTPS" "plain HTTP is sent to HTTPS" \
      || fail "2.3 HTTPS" "plain HTTP answers '$code' instead of a redirect to HTTPS"
  else
    skip "2.3 HTTPS" "BASE_URL is not https (a local system)"
  fi
  for path in /actuator/prometheus /actuator/metrics /actuator/env; do
    code=$(curl -s -o /dev/null -m 20 -w '%{http_code}' "$BASE_URL$path" || true)
    [[ "$code" == 200 ]] && fail "2.3 metrics not public" "$path answers 200" \
      || ok "2.3 metrics not public" "$path answers $code"
  done
  env=$(curl -s -m 20 "$BASE_URL/api/auth/entries/admin" | grep -oE '"environment" *: *(\{[^}]*\}|"[^"]*")' | head -1)
  local named
  named=$(grep -oE '"name" *: *"[^"]*"' <<<"$env" | cut -d'"' -f4)
  [[ -z "$named" ]] && named=$(grep -oE ': *"[^"]*"' <<<"$env" | cut -d'"' -f2)
  if [[ -z "$named" ]]; then fail "2.3 environment banner" "the entry does not say which system it is"
  elif [[ "$named" == "$expect_env" ]]; then
    ok "2.3 environment banner" "the pages run as '$named' ($([[ "$named" == production ]] && echo no banner || echo banner shown))"
  else fail "2.3 environment banner" "the pages run as '$named', expected '$expect_env' (JABIZ_WEB_ENVIRONMENT)"; fi
}

# ---- The settings file ---------------------------------------------------------------------------------------------

settings_checks() {
  local out status
  out=$("$root/tools/ops/env-check.sh" "$ENV_FILE" "$root/deploy/quizbuks/server/env-check.rules" 2>&1)
  status=$?
  sed 's/^/         /' <<<"$out"
  if [[ "$status" == 0 ]]; then ok "2.3 settings check" "env-check: no error (read the warnings above)"
  else fail "2.3 settings check" "env-check reported errors (above)"; fi
  local options
  options=$(grep -E '^(export )?SPRING_R2DBC_PROPERTIES_OPTIONS=' "$ENV_FILE" | tail -1)
  if [[ -z "$options" || "$options" == *jit=off* ]]; then ok "2.2 JIT" "the settings do not override jit=off"
  else fail "2.2 JIT" "SPRING_R2DBC_PROPERTIES_OPTIONS overrides application.yml's jit=off"; fi
}

# ---- The startup log -----------------------------------------------------------------------------------------------

log_checks() {
  local log pattern label
  log=$( (if [[ -n "${LOG_FILE:-}" ]]; then cat "$LOG_FILE"; else bash -c "$LOG_CMD"; fi) 2>/dev/null | tr -d '\000')
  for pattern in 'Platform check failed' 'QUIZBUKS \| parameter' 'JournalMappingCheck' 'FirstLaunchSwitchesCheck' \
      'StripeModeCheck' 'legal:(TERMS_APP|TERMS_SPONSOR|PRIVACY)'; do
    label=${pattern//\\/}
    if grep -qE "$pattern" <<<"$log"; then fail "2.x startup log" "it reports '$label' ($(grep -cE "$pattern" <<<"$log") line(s))"
    else ok "2.x startup log" "nothing about '$label'"; fi
  done
  if grep -qE 'Address database .*: [0-9]+ ranges' <<<"$log"; then ok "2.5 address database" "loaded at startup"
  else warn "2.5 address database" "the log does not say the address database was loaded (JABIZ_GEOIP_DATABASE)"; fi
}

echo "QuizBuks launch day, dry run (read only): $(date -u +%Y-%m-%dT%H:%M:%SZ)"
if [[ -n "${DATABASE_URL:-}" ]]; then database_checks; else skip "database" "DATABASE_URL is not set"; fi
if [[ -n "${BASE_URL:-}" ]]; then BASE_URL=${BASE_URL%/}; site_checks; else skip "site" "BASE_URL is not set"; fi
if [[ -n "${ENV_FILE:-}" ]]; then settings_checks; else skip "settings" "ENV_FILE is not set"; fi
if [[ -n "${LOG_FILE:-}${LOG_CMD:-}" ]]; then log_checks; else skip "startup log" "neither LOG_FILE nor LOG_CMD is set"; fi

cat <<'EOF'

For a person (launch-checklist.md part 2; not something a script can confirm):
  2.1  the secrets are new for production, the offline copies of the three keys are kept
  2.2  the backups run and a restore drill passed with DRILL_ROTATE=true (tools/ops/restore-drill.sh)
  2.3  no shared accounts; the bootstrap administrator's variables are gone (env-check above); only internal ports
  2.4  Stripe live mode and the small real top-up and refund; the next day's reconciliation is OK
  2.5  the legal texts are the lawyer's; the help pages read well in three languages; a test mail arrives
  2.6  the devices (install, Facebook and LINE, AdSense), mainland China's network, the launch content
EOF
echo
echo "$fails error(s), $warns warning(s)"
[[ "$fails" == 0 ]]
