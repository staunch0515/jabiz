#!/usr/bin/env bash
# QuizBuks on this machine, for trying it out (docs: deploy/quizbuks/README.md, "本机演示"). From the repository root:
#
#   tools/quizbuks/local-demo.sh start [--seed] [--build]   build if needed, start, set up (and add demo data)
#   tools/quizbuks/local-demo.sh seed                       add the demo data to a running demo
#   tools/quizbuks/local-demo.sh stop                       stop what start started
#   tools/quizbuks/local-demo.sh status | logs
#
# Runs the packaged jar (the three front ends in one) with the Stripe stub and Mailpit, so nothing reaches Stripe and
# all mail stays on this machine. PostgreSQL: QB_DEMO_DB_HOST/PORT/NAME/USER/PASSWORD (default: the compose database,
# localhost:5440, quizbuks/quizbuks, started with Docker when nothing listens there). Needs JDK 21, python3, curl,
# openssl; Docker only for the database and Mailpit when they are not already running.
#
# LOCAL ONLY: administration without a second factor, a second administrator without one (QB_DEMO_PUBLISHER) who
# publishes the controlled changes, fixed demo passwords. Never expose this instance.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
STATE=${QB_DEMO_STATE:-$ROOT/deploy/quizbuks/.local-demo}
JAR=$ROOT/backend/quizbuks/build/libs/quizbuks-0.0.1-SNAPSHOT.jar
COMPOSE=(docker compose -f "$ROOT/deploy/quizbuks/compose.dev.yaml")
MAILPIT_IMAGE=axllent/mailpit:v1.27.10
PORT=${QB_DEMO_PORT:-8080}
STUB_PORT=${QB_DEMO_STUB_PORT:-8093}
SMTP_PORT=${QB_DEMO_SMTP_PORT:-1025}
MAILPIT_PORT=${QB_DEMO_MAILPIT_PORT:-8025}
DB_HOST=${QB_DEMO_DB_HOST:-localhost}
DB_PORT=${QB_DEMO_DB_PORT:-5440}
DB_NAME=${QB_DEMO_DB_NAME:-quizbuks}
DB_USER=${QB_DEMO_DB_USER:-quizbuks}
DB_PASSWORD=${QB_DEMO_DB_PASSWORD:-quizbuks}

say() { printf '%s\n' "local-demo: $*"; }
die() { say "$*" >&2; exit 1; }
listening() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
running() { [[ -f $STATE/$1.pid ]] && kill -0 "$(cat "$STATE/$1.pid")" 2>/dev/null; }
need() { command -v "$1" >/dev/null || die "$1 is needed ($2)"; }

# Secrets made once and kept: the integrity and MFA keys must stay the same for the database they were used with.
environment() {
  mkdir -p "$STATE"
  if [[ ! -f $STATE/env ]]; then
    umask 077
    cat > "$STATE/env" <<EOF
JABIZ_JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
JABIZ_INTEGRITY_KEY=$(openssl rand -base64 48 | tr -d '\n')
JABIZ_MFA_KEY=$(openssl rand -base64 48 | tr -d '\n')
JABIZ_SEAL_KEY=$(openssl rand -base64 48 | tr -d '\n')
STRIPE_WEBHOOK_SECRET=whsec_$(openssl rand -hex 24)
STRIPE_CONNECT_WEBHOOK_SECRET=whsec_$(openssl rand -hex 24)
JABIZ_BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -hex 12)
EOF
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
  # Administration without a second factor (below): reachable from this machine only (Q10b, review L4).
  SERVER_ADDRESS=127.0.0.1
  # The secrets above are this demonstration's own (Q10b, M5: only a local demonstration may have them made).
  QB_LOCAL_SECRETS=true
  JABIZ_SECURITY_MFA_ADMINISTRATION=false
  QB_ADMIN_ROLES=ADMIN,QB_ADMIN_SUPER,QB_ADMIN_CONTENT,QB_ADMIN_FINANCE,QB_DEMO_PUBLISHER
  JABIZ_MAIL_ENABLED=true
  JABIZ_MAIL_FROM=noreply@quizbuks.local
  JABIZ_MAIL_BASE_URL=http://localhost:$PORT
  SPRING_MAIL_HOST=127.0.0.1
  SPRING_MAIL_PORT=$SMTP_PORT
  STRIPE_API_KEY=sk_test_local_demo
  # Claude (Q8a): a placeholder key and an address nothing answers; AI stays off (qb.ai.enabled), nothing is sent.
  ANTHROPIC_API_KEY=local-demo-placeholder-not-a-claude-key
  QB_ANTHROPIC_API_URL=http://localhost:9
  QB_ANTHROPIC_API_HOSTS=localhost
  QB_STRIPE_API_URL=http://localhost:$STUB_PORT
  QB_STRIPE_API_HOSTS=localhost
  QB_STRIPE_CHECKOUT_URL_PREFIX=http://localhost:$STUB_PORT/
  QB_STRIPE_CONNECT_URL_PREFIX=http://localhost:$STUB_PORT/
  QB_SPONSOR_LINK=http://localhost:$PORT/sponsor/
  QB_APP_LINK=http://localhost:$PORT/taker/
  QB_DEMO_BASE_URL=http://localhost:$PORT
  QB_DEMO_MAILPIT_URL=http://localhost:$MAILPIT_PORT
  set +a
}

database() {
  listening "$DB_PORT" && return
  need docker "no PostgreSQL on port $DB_PORT; start one or set QB_DEMO_DB_*"
  say "starting PostgreSQL (docker compose service db, port $DB_PORT)"
  QUIZBUKS_DB_PORT=$DB_PORT DB_PASSWORD=$DB_PASSWORD "${COMPOSE[@]}" up -d db
  touch "$STATE/started-db"
  for _ in $(seq 60); do listening "$DB_PORT" && sleep 2 && return; sleep 1; done
  die "PostgreSQL did not start"
}

mailpit() {
  listening "$SMTP_PORT" && listening "$MAILPIT_PORT" && return
  need docker "no Mailpit on ports $SMTP_PORT/$MAILPIT_PORT; start one or set QB_DEMO_SMTP_PORT/QB_DEMO_MAILPIT_PORT"
  say "starting Mailpit (mail at http://localhost:$MAILPIT_PORT)"
  docker rm -f quizbuks-demo-mailpit >/dev/null 2>&1 || true
  docker run -d --name quizbuks-demo-mailpit -p "127.0.0.1:$SMTP_PORT:1025" -p "127.0.0.1:$MAILPIT_PORT:8025" \
    "$MAILPIT_IMAGE" >/dev/null
  touch "$STATE/started-mailpit"
  for _ in $(seq 30); do listening "$MAILPIT_PORT" && return; sleep 1; done
  die "Mailpit did not start"
}

start() {
  local seed=false build=false
  for arg in "$@"; do
    case $arg in --seed) seed=true ;; --build) build=true ;; *) die "unknown option $arg" ;; esac
  done
  need java "JDK 21"; need python3 "the setup"; need curl "health checks"; need openssl "secrets"
  environment
  if running app; then say "already running on http://localhost:$PORT"; else
    database
    mailpit
    if $build || [[ ! -f $JAR ]]; then
      say "building the jar (first time: several minutes)"
      (cd "$ROOT/backend" && ./gradlew -q :quizbuks:bootJar)
    fi
    listening "$PORT" && die "port $PORT is in use (set QB_DEMO_PORT)"
    # Another stub would sign its events with other secrets, and the application would refuse every payment.
    if ! running stub && listening "$STUB_PORT"; then die "port $STUB_PORT is in use (set QB_DEMO_STUB_PORT)"; fi
    if ! running stub; then
      nohup java "$ROOT/tools/quizbuks/stripe-stub/StripeStub.java" "$STUB_PORT" \
        "http://localhost:$PORT/api/inbound/stripe" "$STRIPE_WEBHOOK_SECRET" \
        "http://localhost:$PORT/api/inbound/stripe-connect" "$STRIPE_CONNECT_WEBHOOK_SECRET" \
        > "$STATE/stub.log" 2>&1 &
      echo $! > "$STATE/stub.pid"
    fi
    say "starting QuizBuks (log: $STATE/app.log)"
    nohup java -Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true -jar "$JAR" \
      > "$STATE/app.log" 2>&1 &
    echo $! > "$STATE/app.pid"
    for _ in $(seq 120); do
      curl -sf "http://localhost:$PORT/actuator/health" >/dev/null && break
      running app || die "QuizBuks stopped; see $STATE/app.log"
      sleep 1
    done
    curl -sf "http://localhost:$PORT/actuator/health" >/dev/null || die "QuizBuks did not become healthy"
  fi
  say "first-time setup"
  python3 "$ROOT/tools/quizbuks/local-demo/demo.py" setup
  if $seed; then seed; fi
  summary "$seed"
}

seed() {
  environment
  running app || die "not running; start it first"
  say "demo data"
  python3 "$ROOT/tools/quizbuks/local-demo/demo.py" seed
}

summary() {
  cat <<EOF

QuizBuks is running (local demo; never expose it):
  App (takers)        http://localhost:$PORT/taker/
  Business site       http://localhost:$PORT/biz/  (the bare address opens it)
  Sponsor console     http://localhost:$PORT/sponsor/
  Administration      http://localhost:$PORT/admin/     admin / $JABIZ_BOOTSTRAP_ADMIN_PASSWORD
  Mail (Mailpit)      http://localhost:$MAILPIT_PORT/
  Stripe stub         http://localhost:$STUB_PORT/       (checkout and payout onboarding pages)
EOF
  if [[ ${1:-false} == true ]] || [[ -f $STATE/seeded ]]; then
    touch "$STATE/seeded"
    cat <<EOF
Demo accounts (password ${QB_DEMO_PASSWORD:-quizbuks-demo-1}):
  sponsor@quizbuks.local   sponsor console: approved, 10,000 Kudos, quiz "World capitals" live
  taker@quizbuks.local     app: play "World capitals" from the home page
EOF
  fi
  echo "Stop with: tools/quizbuks/local-demo.sh stop"
}

stop() {
  environment
  for name in app stub; do
    if running "$name"; then kill "$(cat "$STATE/$name.pid")"; say "stopped $name"; fi
    rm -f "$STATE/$name.pid"
  done
  if [[ -f $STATE/started-mailpit ]]; then docker rm -f quizbuks-demo-mailpit >/dev/null; rm "$STATE/started-mailpit"; say "stopped Mailpit"; fi
  if [[ -f $STATE/started-db ]]; then "${COMPOSE[@]}" stop db >/dev/null; rm "$STATE/started-db"; say "stopped PostgreSQL (data kept)"; fi
}

status() {
  environment
  for name in app stub; do
    if running "$name"; then say "$name running (pid $(cat "$STATE/$name.pid"))"; else say "$name stopped"; fi
  done
  listening "$DB_PORT" && say "PostgreSQL listening on $DB_PORT" || say "no PostgreSQL on $DB_PORT"
  listening "$MAILPIT_PORT" && say "Mailpit on http://localhost:$MAILPIT_PORT" || say "no Mailpit on $MAILPIT_PORT"
}

case ${1:-} in
  start) shift; start "$@" ;;
  seed) seed ;;
  stop) stop ;;
  status) status ;;
  logs) environment; tail -n 100 -f "$STATE/app.log" ;;
  *) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
