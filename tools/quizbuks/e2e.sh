#!/usr/bin/env bash
# The end-to-end tests on this machine, as CI runs them (deploy/quizbuks/README.md, "本机 e2e"). From the repository
# root:
#
#   tools/quizbuks/e2e.sh run [--build] [--fresh] [--keep] [-- <playwright arguments>]
#       build the jar if needed, start the database, two Mailpits, the Stripe and Claude stubs and the application
#       with the environment of quizbuks-web/e2e/app.env, run `pnpm e2e` (all tests, or the files and -g given after
#       --), then stop what it started. --build rebuilds the jar; --fresh drops the e2e database first; --keep leaves
#       everything running for the next run (then: tools/quizbuks/e2e.sh stop).
#   tools/quizbuks/e2e.sh stop | status | logs | env
#
# Needs JDK 21, pnpm, curl, openssl; Docker for the database and Mailpit unless something already listens on their
# ports (QB_E2E_DB_HOST/PORT/USER/PASSWORD, QB_E2E_SMTP_PORT/MAILPIT_PORT, QB_E2E_MERCHANT_SMTP_PORT/MAILPIT_PORT);
# E2E_CHROMIUM names a preinstalled Chromium instead of Playwright's own. LOCAL ONLY: administration without a second
# factor and throwaway keys; never expose this instance.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
E2E=$ROOT/quizbuks-web/e2e
STATE=${QB_E2E_STATE:-$E2E/.local}
JAR=$ROOT/backend/quizbuks/build/libs/quizbuks-0.0.1-SNAPSHOT.jar
COMPOSE=(docker compose -f "$ROOT/deploy/quizbuks/compose.dev.yaml")
MAILPIT_IMAGE=axllent/mailpit:v1.27.10
PORT=${QB_E2E_PORT:-8080}
STRIPE_PORT=${QB_E2E_STRIPE_PORT:-8093}
CLAUDE_PORT=${QB_E2E_CLAUDE_PORT:-8094}
SMTP_PORT=${QB_E2E_SMTP_PORT:-1025}
MAILPIT_PORT=${QB_E2E_MAILPIT_PORT:-8025}
MERCHANT_SMTP_PORT=${QB_E2E_MERCHANT_SMTP_PORT:-1026}
MERCHANT_MAILPIT_PORT=${QB_E2E_MERCHANT_MAILPIT_PORT:-8026}
DB_HOST=${QB_E2E_DB_HOST:-localhost}
DB_PORT=${QB_E2E_DB_PORT:-5440}
DB_NAME=${QB_E2E_DB_NAME:-quizbuks_e2e}
DB_USER=${QB_E2E_DB_USER:-quizbuks}
DB_PASSWORD=${QB_E2E_DB_PASSWORD:-quizbuks}
ADMIN_USER=admin
ADMIN_PASSWORD=e2e-admin-password

say() { printf '%s\n' "e2e: $*"; }
die() { say "$*" >&2; exit 1; }
listening() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
running() { [[ -f $STATE/$1.pid ]] && kill -0 "$(cat "$STATE/$1.pid")" 2>/dev/null; }
need() { command -v "$1" >/dev/null || die "$1 is needed ($2)"; }

# The application's environment: app.env (shared with CI), the keys made once per state directory (the Stripe stub
# signs with the same secrets), the database, the first administrator and this machine's ports.
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
SHOPIFY_API_SECRET=e2e_throwaway_shopify_$(openssl rand -hex 16)
EOF
  fi
  set -a
  # shellcheck disable=SC1091
  . "$E2E/app.env"
  # shellcheck disable=SC1091
  . "$STATE/env"
  JABIZ_BOOTSTRAP_ADMIN_USER=$ADMIN_USER
  JABIZ_BOOTSTRAP_ADMIN_PASSWORD=$ADMIN_PASSWORD
  JABIZ_FILES_LOCAL_ROOT=$STATE/files
  SPRING_R2DBC_URL=r2dbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME
  SPRING_R2DBC_USERNAME=$DB_USER
  SPRING_R2DBC_PASSWORD=$DB_PASSWORD
  SPRING_FLYWAY_URL=jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME
  SPRING_FLYWAY_USER=$DB_USER
  SPRING_FLYWAY_PASSWORD=$DB_PASSWORD
  SERVER_PORT=$PORT
  SERVER_ADDRESS=127.0.0.1
  # This machine's ports where they differ from app.env's (CI's).
  SPRING_MAIL_PORT=$SMTP_PORT
  JABIZ_MAIL_BASE_URL=http://localhost:$PORT
  QB_SPONSOR_LINK=http://localhost:$PORT/sponsor/
  QB_APP_LINK=http://localhost:$PORT/taker/
  QB_STRIPE_API_URL=http://localhost:$STRIPE_PORT
  QB_STRIPE_CHECKOUT_URL_PREFIX=http://localhost:$STRIPE_PORT/
  QB_STRIPE_CONNECT_URL_PREFIX=http://localhost:$STRIPE_PORT/
  QB_ANTHROPIC_API_URL=http://localhost:$CLAUDE_PORT
  # Japanese and Chinese in the PDFs (Q9d) when this machine has the fonts CI installs.
  fonts=""
  for font in /usr/share/fonts/opentype/ipafont-gothic/ipag.ttf /usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf; do
    [[ -f $font ]] && fonts="${fonts:+$fonts,}$font"
  done
  [[ -n $fonts ]] && JABIZ_REPORTS_PDF_FONTS=$fonts
  # What the tests read.
  E2E_ADMIN_USER=$ADMIN_USER
  E2E_ADMIN_PASSWORD=$ADMIN_PASSWORD
  E2E_BASE_URL=http://localhost:$PORT
  E2E_MAILPIT_URL=http://localhost:$MAILPIT_PORT
  E2E_MERCHANT_SMTP_PORT=$MERCHANT_SMTP_PORT
  E2E_MERCHANT_MAILPIT_URL=http://localhost:$MERCHANT_MAILPIT_PORT
  set +a
}

psql_e2e() {
  if command -v psql >/dev/null; then
    PGPASSWORD=$DB_PASSWORD psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 -tAq "$@"
  elif [[ -f $STATE/started-db ]] || [[ $DB_HOST == localhost ]]; then
    "${COMPOSE[@]}" exec -T db psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 -tAq "$@"
  else
    die "psql is needed to create the database $DB_NAME on $DB_HOST:$DB_PORT"
  fi
}

database() {
  local fresh=$1
  if ! listening "$DB_PORT"; then
    need docker "no PostgreSQL on port $DB_PORT; start one or set QB_E2E_DB_*"
    say "starting PostgreSQL (docker compose service db, port $DB_PORT)"
    QUIZBUKS_DB_PORT=$DB_PORT DB_PASSWORD=$DB_PASSWORD "${COMPOSE[@]}" up -d db
    touch "$STATE/started-db"
    for _ in $(seq 60); do listening "$DB_PORT" && sleep 2 && break; sleep 1; done
    listening "$DB_PORT" || die "PostgreSQL did not start"
  fi
  if $fresh; then
    say "dropping the database $DB_NAME"
    psql_e2e -c "DROP DATABASE IF EXISTS \"$DB_NAME\" WITH (FORCE)"
  fi
  if [[ $(psql_e2e -c "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'") != 1 ]]; then
    say "creating the database $DB_NAME"
    psql_e2e -c "CREATE DATABASE \"$DB_NAME\" OWNER \"$DB_USER\""
  fi
}

# The platform's Mailpit, and the sponsors' own mail server of the tests (Q29): a second Mailpit that offers STARTTLS
# with a throwaway certificate and takes any login, as CI starts it.
mailpits() {
  if ! listening "$SMTP_PORT" || ! listening "$MAILPIT_PORT"; then
    need docker "no Mailpit on ports $SMTP_PORT/$MAILPIT_PORT; start one or set QB_E2E_SMTP_PORT/QB_E2E_MAILPIT_PORT"
    say "starting Mailpit (mail at http://localhost:$MAILPIT_PORT)"
    docker rm -f quizbuks-e2e-mailpit >/dev/null 2>&1 || true
    docker run -d --name quizbuks-e2e-mailpit -p "127.0.0.1:$SMTP_PORT:1025" -p "127.0.0.1:$MAILPIT_PORT:8025" \
      "$MAILPIT_IMAGE" >/dev/null
    touch "$STATE/started-mailpit"
  fi
  if ! listening "$MERCHANT_SMTP_PORT" || ! listening "$MERCHANT_MAILPIT_PORT"; then
    need docker "no merchant Mailpit on ports $MERCHANT_SMTP_PORT/$MERCHANT_MAILPIT_PORT; set QB_E2E_MERCHANT_*"
    say "starting the sponsors' mail server (Mailpit with STARTTLS, mail at http://localhost:$MERCHANT_MAILPIT_PORT)"
    mkdir -p "$STATE/merchant-mail"
    if [[ ! -f $STATE/merchant-mail/cert.pem ]]; then
      openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj /CN=localhost \
        -keyout "$STATE/merchant-mail/key.pem" -out "$STATE/merchant-mail/cert.pem" 2>/dev/null
      chmod 644 "$STATE/merchant-mail/key.pem" "$STATE/merchant-mail/cert.pem"
    fi
    docker rm -f quizbuks-e2e-merchant-mail >/dev/null 2>&1 || true
    docker run -d --name quizbuks-e2e-merchant-mail -p "127.0.0.1:$MERCHANT_SMTP_PORT:1025" \
      -p "127.0.0.1:$MERCHANT_MAILPIT_PORT:8025" -v "$STATE/merchant-mail:/certs:ro" \
      -e MP_SMTP_TLS_CERT=/certs/cert.pem -e MP_SMTP_TLS_KEY=/certs/key.pem -e MP_SMTP_AUTH_ACCEPT_ANY=1 \
      "$MAILPIT_IMAGE" >/dev/null
    touch "$STATE/started-merchant-mail"
  fi
  for port in "$MAILPIT_PORT" "$MERCHANT_MAILPIT_PORT"; do
    for _ in $(seq 30); do curl -sf "localhost:$port/api/v1/info" >/dev/null && break; sleep 1; done
    curl -sf "localhost:$port/api/v1/info" >/dev/null || die "Mailpit on port $port did not answer"
  done
}

stubs() {
  if ! running stripe; then
    listening "$STRIPE_PORT" && die "port $STRIPE_PORT is in use (set QB_E2E_STRIPE_PORT)"
    nohup java "$ROOT/tools/quizbuks/stripe-stub/StripeStub.java" "$STRIPE_PORT" \
      "http://localhost:$PORT/api/inbound/stripe" "$STRIPE_WEBHOOK_SECRET" \
      "http://localhost:$PORT/api/inbound/stripe-connect" "$STRIPE_CONNECT_WEBHOOK_SECRET" \
      > "$STATE/stripe-stub.log" 2>&1 &
    echo $! > "$STATE/stripe.pid"
  fi
  if ! running claude; then
    listening "$CLAUDE_PORT" && die "port $CLAUDE_PORT is in use (set QB_E2E_CLAUDE_PORT)"
    nohup java "$ROOT/tools/quizbuks/claude-stub/ClaudeStub.java" "$CLAUDE_PORT" > "$STATE/claude-stub.log" 2>&1 &
    echo $! > "$STATE/claude.pid"
  fi
}

application() {
  local build=$1
  if $build || [[ ! -f $JAR ]]; then
    say "building the jar (first time: several minutes)"
    (cd "$ROOT/backend" && ./gradlew -q :quizbuks:bootJar)
  fi
  listening "$PORT" && die "port $PORT is in use (set QB_E2E_PORT)"
  say "starting QuizBuks (log: $STATE/app.log)"
  nohup java -Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true -jar "$JAR" > "$STATE/app.log" 2>&1 &
  echo $! > "$STATE/app.pid"
  for _ in $(seq 180); do
    curl -sf "http://localhost:$PORT/actuator/health" >/dev/null && break
    running app || die "QuizBuks stopped; see $STATE/app.log"
    sleep 1
  done
  curl -sf "http://localhost:$PORT/actuator/health" >/dev/null || die "QuizBuks did not become healthy; see $STATE/app.log"
  grep -q "Platform check failed" "$STATE/app.log" && die "the platform check failed; see $STATE/app.log"
  return 0
}

run() {
  local build=false fresh=false keep=false
  while [[ $# -gt 0 ]]; do
    case $1 in
      --build) build=true ;;
      --fresh) fresh=true ;;
      --keep) keep=true ;;
      --) shift; break ;;
      *) die "unknown option $1 (playwright arguments go after --)" ;;
    esac
    shift
  done
  need java "JDK 21"; need pnpm "the tests"; need curl "health checks"; need openssl "keys"
  environment
  if running app; then
    say "already running on http://localhost:$PORT (stop first to rebuild or to start fresh)"
  else
    database "$fresh"
    mailpits
    stubs
    application "$build"
  fi
  [[ -d $E2E/node_modules ]] || (cd "$E2E" && pnpm install --frozen-lockfile)
  if [[ -z ${E2E_CHROMIUM:-} ]]; then (cd "$E2E" && pnpm exec playwright install chromium >/dev/null); fi
  say "running the tests${*:+: $*}"
  local status=0
  (cd "$E2E" && pnpm e2e "$@") || status=$?
  if $keep; then
    say "kept running (http://localhost:$PORT, mail http://localhost:$MAILPIT_PORT); stop with: tools/quizbuks/e2e.sh stop"
  else
    stop
  fi
  return $status
}

stop() {
  environment
  for name in app stripe claude; do
    if running "$name"; then kill "$(cat "$STATE/$name.pid")"; say "stopped $name"; fi
    rm -f "$STATE/$name.pid"
  done
  for name in mailpit merchant-mail; do
    if [[ -f $STATE/started-$name ]]; then docker rm -f "quizbuks-e2e-$name" >/dev/null; rm "$STATE/started-$name"; say "stopped $name"; fi
  done
  if [[ -f $STATE/started-db ]]; then "${COMPOSE[@]}" stop db >/dev/null; rm "$STATE/started-db"; say "stopped PostgreSQL (data kept)"; fi
}

status() {
  environment
  for name in app stripe claude; do
    if running "$name"; then say "$name running (pid $(cat "$STATE/$name.pid"))"; else say "$name stopped"; fi
  done
  listening "$DB_PORT" && say "PostgreSQL listening on $DB_PORT" || say "no PostgreSQL on $DB_PORT"
  listening "$MAILPIT_PORT" && say "Mailpit on http://localhost:$MAILPIT_PORT" || say "no Mailpit on $MAILPIT_PORT"
  listening "$MERCHANT_MAILPIT_PORT" && say "merchant Mailpit on http://localhost:$MERCHANT_MAILPIT_PORT" || say "no merchant Mailpit on $MERCHANT_MAILPIT_PORT"
}

# The application's environment as the run sets it, secrets hidden (for a look, and for the tests of this script).
print_env() {
  environment
  env | grep -E '^(JABIZ_|QB_|SPRING_|STRIPE_|ANTHROPIC_|SHOPIFY_|SERVER_|E2E_)' | sort \
    | sed -E 's/^((JABIZ_(JWT_SECRET|INTEGRITY_KEY|MFA_KEY|SEAL_KEY|BOOTSTRAP_ADMIN_PASSWORD)|STRIPE_[A-Z_]*SECRET|SHOPIFY_API_SECRET|SPRING_[A-Z_]*PASSWORD|E2E_ADMIN_PASSWORD)=).*/\1***/'
}

case ${1:-} in
  run) shift; run "$@" ;;
  stop) stop ;;
  status) status ;;
  logs) environment; tail -n 100 -f "$STATE/app.log" ;;
  env) print_env ;;
  *) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
