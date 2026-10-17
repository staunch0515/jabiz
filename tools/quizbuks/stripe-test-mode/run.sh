#!/usr/bin/env bash
# QuizBuks's money flow against Stripe's TEST mode (runbook: docs/quizbuks/stripe-test-mode.md). From the repository
# root:
#
#   tools/quizbuks/stripe-test-mode/run.sh serve [--stub] [--build]   start QuizBuks on this machine for a run
#   tools/quizbuks/stripe-test-mode/run.sh run [options]              drive the flow, verify each step, summarise
#   tools/quizbuks/stripe-test-mode/run.sh guard [options]            only the safety checks (no network)
#   tools/quizbuks/stripe-test-mode/run.sh payout-check PO_ID         was Stripe's payout recorded (payout.paid)?
#   tools/quizbuks/stripe-test-mode/run.sh stop | status | logs       what serve started
#
# serve runs the packaged jar against PostgreSQL with the real test key (STRIPE_TEST_SECRET_KEY, sk_test_...) and the
# Stripe CLI forwarding the events (stripe listen), Mailpit for the sign-up mail, the transfer run every minute (test
# mode only) and no second factor for the administration (this machine only). With --stub it uses the local Stripe
# stub instead: no key, nothing reaches Stripe (the dry run; then `run --stub`).
#
# run drives a running installation (default http://localhost:8080; a remote one needs --allow-remote and https, and
# never a host in production-hosts beside this script). It refuses unless the key is sk_test_, the application shows
# test mode and the target is not production. Secrets are never printed. Options: stm.py --help.
#
# Settings for serve: STRIPE_TM_DB_HOST/PORT/NAME/USER/PASSWORD (default localhost:5440, database
# quizbuks_stripe_test, quizbuks/quizbuks: the compose database), STRIPE_TM_PORT (8080), STRIPE_TM_STUB_PORT (8093),
# STRIPE_TM_SMTP_PORT / STRIPE_TM_MAILPIT_PORT (1025 / 8025). State, secrets and logs: deploy/quizbuks/.stripe-test-mode.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
STATE=${STRIPE_TM_STATE:-$ROOT/deploy/quizbuks/.stripe-test-mode}
export STRIPE_TM_STATE=$STATE
JAR=$ROOT/backend/quizbuks/build/libs/quizbuks-0.0.1-SNAPSHOT.jar
MAILPIT_IMAGE=axllent/mailpit:v1.27.10
PORT=${STRIPE_TM_PORT:-8080}
STUB_PORT=${STRIPE_TM_STUB_PORT:-8093}
SMTP_PORT=${STRIPE_TM_SMTP_PORT:-1025}
MAILPIT_PORT=${STRIPE_TM_MAILPIT_PORT:-8025}
DB_HOST=${STRIPE_TM_DB_HOST:-localhost}
DB_PORT=${STRIPE_TM_DB_PORT:-5440}
DB_NAME=${STRIPE_TM_DB_NAME:-quizbuks_stripe_test}
DB_USER=${STRIPE_TM_DB_USER:-quizbuks}
DB_PASSWORD=${STRIPE_TM_DB_PASSWORD:-quizbuks}
# The events QuizBuks subscribes to (application.yml, jabiz.inbound-webhooks): both endpoints' lists. stripe listen
# sends the connected accounts' events to --forward-connect-to; each endpoint drops the types it does not consume.
EVENTS=checkout.session.completed,checkout.session.expired,charge.refunded,charge.dispute.created,charge.dispute.closed,transfer.reversed,payout.paid,payout.failed,account.updated,account.application.deauthorized

say() { printf '%s\n' "stripe-test-mode: $*"; }
die() { say "$*" >&2; exit 1; }
listening() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
running() { [[ -f $STATE/$1.pid ]] && kill -0 "$(cat "$STATE/$1.pid")" 2>/dev/null; }
need() { command -v "$1" >/dev/null || die "$1 is needed ($2)"; }
stm() { python3 "$HERE/stm.py" "$@"; }

# Settings that run reads when it drives what serve started (only for that address, never over set values).
load_run_env() {
  [[ -f $STATE/run.env ]] || return 0
  local base
  base=$(sed -n 's/^STRIPE_TM_BASE_URL=//p' "$STATE/run.env")
  [[ -z ${STRIPE_TM_BASE_URL:-} || ${STRIPE_TM_BASE_URL:-} == "$base" ]] || return 0
  for arg in "$@"; do [[ $arg == --base-url* ]] && return 0; done
  while IFS='=' read -r name value; do
    [[ $name =~ ^STRIPE_TM_[A-Z_]+$ ]] || continue
    [[ -n ${!name:-} ]] || export "$name=$value"
  done < "$STATE/run.env"
}

# Secrets made once and kept: the integrity and MFA keys belong to the database they were used with.
environment() {
  local stub=$1
  mkdir -p "$STATE"
  chmod 700 "$STATE"
  if [[ ! -f $STATE/env ]]; then
    (umask 077 && cat > "$STATE/env" <<EOF
JABIZ_JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
JABIZ_INTEGRITY_KEY=$(openssl rand -base64 48 | tr -d '\n')
JABIZ_MFA_KEY=$(openssl rand -base64 48 | tr -d '\n')
JABIZ_BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -hex 16)
STUB_WEBHOOK_SECRET=whsec_$(openssl rand -hex 24)
STUB_CONNECT_WEBHOOK_SECRET=whsec_$(openssl rand -hex 24)
PUBLISHER_PASSWORD=$(openssl rand -hex 16)
EOF
    )
  fi
  set -a
  # shellcheck disable=SC1091
  . "$STATE/env"
  JABIZ_BOOTSTRAP_ADMIN_USER=admin
  JABIZ_FILES_LOCAL_ROOT=$STATE/files
  SPRING_R2DBC_URL=r2dbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME
  SPRING_R2DBC_USERNAME=$DB_USER
  SPRING_R2DBC_PASSWORD=$DB_PASSWORD
  SPRING_FLYWAY_URL=jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME
  SPRING_FLYWAY_USER=$DB_USER
  SPRING_FLYWAY_PASSWORD=$DB_PASSWORD
  SERVER_PORT=$PORT
  # This machine only: the administration without a second factor, and a second administrator (control.publish
  # only) that run creates to publish the controlled changes (four eyes).
  JABIZ_SECURITY_MFA_ADMINISTRATION=false
  QB_ADMIN_ROLES=ADMIN,QB_ADMIN_SUPER,QB_ADMIN_CONTENT,QB_ADMIN_FINANCE,QB_STM_PUBLISHER
  JABIZ_MAIL_ENABLED=true
  JABIZ_MAIL_FROM=noreply@quizbuks.test
  JABIZ_MAIL_BASE_URL=http://localhost:$PORT
  SPRING_MAIL_HOST=127.0.0.1
  SPRING_MAIL_PORT=$SMTP_PORT
  QB_SPONSOR_LINK=http://localhost:$PORT/sponsor/
  QB_APP_LINK=http://localhost:$PORT/
  # Test mode only (TransferScheduleCheck refuses them next to live mode or a live key): the daily transfers and the
  # daily reconciliation every minute, so that a run sees both.
  QB_STRIPE_LIVEMODE=false
  QB_TRANSFER_RUN_CRON='0 * * * * *'
  QB_RECONCILE_RUN_CRON='30 * * * * *'
  set +a
  if [[ $stub == true ]]; then
    export STRIPE_API_KEY=sk_test_stripe_test_mode_stub STRIPE_WEBHOOK_SECRET=$STUB_WEBHOOK_SECRET \
      STRIPE_CONNECT_WEBHOOK_SECRET=$STUB_CONNECT_WEBHOOK_SECRET QB_STRIPE_API_URL=http://localhost:$STUB_PORT \
      QB_STRIPE_API_HOSTS=localhost QB_STRIPE_CHECKOUT_URL_PREFIX=http://localhost:$STUB_PORT/ \
      QB_STRIPE_CONNECT_URL_PREFIX=http://localhost:$STUB_PORT/
  else
    export STRIPE_API_KEY=${STRIPE_TEST_SECRET_KEY:-}
  fi
}

database() {
  [[ $DB_NAME =~ ^[a-z0-9_]+$ ]] || die "STRIPE_TM_DB_NAME must be lower-case letters, digits and _"
  listening "$DB_PORT" || die "no PostgreSQL on port $DB_PORT (docker compose -f deploy/quizbuks/docker-compose.yml up -d db, or set STRIPE_TM_DB_*)"
  need psql "to create the database $DB_NAME"
  if [[ -z $(PGPASSWORD=$DB_PASSWORD psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -tAc \
      "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'") ]]; then
    PGPASSWORD=$DB_PASSWORD psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -qc "CREATE DATABASE $DB_NAME"
    say "created database $DB_NAME"
  fi
}

mailpit() {
  listening "$SMTP_PORT" && listening "$MAILPIT_PORT" && return
  need docker "no Mailpit on ports $SMTP_PORT/$MAILPIT_PORT; start one or set STRIPE_TM_SMTP_PORT/STRIPE_TM_MAILPIT_PORT"
  say "starting Mailpit (mail at http://localhost:$MAILPIT_PORT)"
  docker rm -f quizbuks-stm-mailpit >/dev/null 2>&1 || true
  docker run -d --name quizbuks-stm-mailpit -p "127.0.0.1:$SMTP_PORT:1025" -p "127.0.0.1:$MAILPIT_PORT:8025" \
    "$MAILPIT_IMAGE" >/dev/null
  touch "$STATE/started-mailpit"
  for _ in $(seq 30); do listening "$MAILPIT_PORT" && return; sleep 1; done
  die "Mailpit did not start"
}

# Stripe's CLI forwards the account's events to both endpoints, signed with one secret it prints for this purpose.
listen() {
  need stripe "the Stripe CLI (https://docs.stripe.com/stripe-cli) forwards Stripe's events to this machine"
  local secret
  secret=$(stripe listen --print-secret 2>/dev/null | tr -d '[:space:]')
  [[ $secret == whsec_* ]] || die "stripe listen --print-secret gave no signing secret (is STRIPE_TEST_SECRET_KEY valid?)"
  export STRIPE_WEBHOOK_SECRET=$secret STRIPE_CONNECT_WEBHOOK_SECRET=$secret
  # Its log holds the signing secret: it stays in the private state directory.
  (umask 077 && nohup stripe listen --events "$EVENTS" \
    --forward-to "http://localhost:$PORT/api/inbound/stripe" \
    --forward-connect-to "http://localhost:$PORT/api/inbound/stripe-connect" > "$STATE/listen.log" 2>&1 &
    echo $! > "$STATE/listen.pid")
  for _ in $(seq 30); do grep -q 'Ready' "$STATE/listen.log" 2>/dev/null && return; sleep 1; done
  die "stripe listen did not become ready (see $STATE/listen.log)"
}

serve() {
  local stub=false build=false
  for arg in "$@"; do
    case $arg in --stub) stub=true ;; --build) build=true ;; *) die "unknown option $arg" ;; esac
  done
  need java "JDK 21"; need python3 "the run"; need curl "health checks"; need openssl "secrets"
  environment "$stub"
  export STRIPE_TM_ADMIN_PASSWORD=$JABIZ_BOOTSTRAP_ADMIN_PASSWORD
  local guard_args=()
  [[ $stub == true ]] && guard_args+=(--stub --stub-url "http://localhost:$STUB_PORT")
  STRIPE_TM_BASE_URL=http://localhost:$PORT stm guard "${guard_args[@]}" || exit $?
  if running app; then die "already running (stop it first: run.sh stop)"; fi
  listening "$PORT" && die "port $PORT is in use (set STRIPE_TM_PORT)"
  database
  mailpit
  if $build || [[ ! -f $JAR ]]; then
    say "building the jar (first time: several minutes)"
    (cd "$ROOT/backend" && ./gradlew -q :quizbuks:bootJar)
  fi
  if [[ $stub == true ]]; then
    # Another process on the port would sign with other secrets, and every event would be refused (runbook).
    listening "$STUB_PORT" && die "port $STUB_PORT is in use: a stale Stripe stub? (stop it or set STRIPE_TM_STUB_PORT)"
    nohup java "$ROOT/tools/quizbuks/stripe-stub/StripeStub.java" "$STUB_PORT" \
      "http://localhost:$PORT/api/inbound/stripe" "$STRIPE_WEBHOOK_SECRET" \
      "http://localhost:$PORT/api/inbound/stripe-connect" "$STRIPE_CONNECT_WEBHOOK_SECRET" > "$STATE/stub.log" 2>&1 &
    echo $! > "$STATE/stub.pid"
  else
    listen
  fi
  say "starting QuizBuks (log: $STATE/app.log)"
  nohup java -Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true -jar "$JAR" > "$STATE/app.log" 2>&1 &
  echo $! > "$STATE/app.pid"
  for _ in $(seq 180); do
    curl -sf "http://localhost:$PORT/actuator/health" >/dev/null && break
    running app || die "QuizBuks stopped; see $STATE/app.log"
    sleep 1
  done
  curl -sf "http://localhost:$PORT/actuator/health" >/dev/null || die "QuizBuks did not become healthy"
  (umask 077 && cat > "$STATE/run.env" <<EOF
STRIPE_TM_BASE_URL=http://localhost:$PORT
STRIPE_TM_ADMIN_USER=admin
STRIPE_TM_ADMIN_PASSWORD=$JABIZ_BOOTSTRAP_ADMIN_PASSWORD
STRIPE_TM_PUBLISHER_PASSWORD=$PUBLISHER_PASSWORD
STRIPE_TM_MAILPIT_URL=http://localhost:$MAILPIT_PORT
STRIPE_TM_STUB_URL=http://localhost:$STUB_PORT
EOF
  )
  echo "$stub" > "$STATE/mode"
  say "QuizBuks on http://localhost:$PORT ($([[ $stub == true ]] && echo "Stripe stub" || echo "Stripe test mode, events via stripe listen")); next: run.sh run$([[ $stub == true ]] && echo " --stub")"
}

stop() {
  for name in app listen stub; do
    if running "$name"; then kill "$(cat "$STATE/$name.pid")"; say "stopped $name"; fi
    rm -f "$STATE/$name.pid"
  done
  if [[ -f $STATE/started-mailpit ]]; then docker rm -f quizbuks-stm-mailpit >/dev/null; rm "$STATE/started-mailpit"; say "stopped Mailpit"; fi
  rm -f "$STATE/run.env"
}

status() {
  for name in app listen stub; do
    if running "$name"; then say "$name running (pid $(cat "$STATE/$name.pid"))"; else say "$name stopped"; fi
  done
}

case ${1:-} in
  serve) shift; serve "$@" ;;
  run|guard|payout-check) need python3 "the run"; load_run_env "$@"; stm "$@" ;;
  stop) stop ;;
  status) status ;;
  logs) tail -n 100 -f "$STATE/app.log" ;;
  *) sed -n '2,23p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
