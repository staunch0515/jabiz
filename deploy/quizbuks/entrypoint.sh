#!/bin/sh
# QuizBuks's entrypoint: fills Stripe's settings for a plain local `docker compose up` (deploy/quizbuks/README.md,
# "本机的 compose"), then hands over to the platform's entrypoint (docker/app-entrypoint.sh: the platform's keys and
# the first administrator).
#
# Only with QB_STRIPE_PLACEHOLDERS=true (docker-compose.yml sets it by default; the image alone does not), only in
# test mode, and only for values that are not set:
#   - STRIPE_API_KEY: a test-mode key that is plainly fake; every call to Stripe is refused.
#   - STRIPE_WEBHOOK_SECRET / STRIPE_CONNECT_WEBHOOK_SECRET: generated once (whsec_ + 48 hex) into the secrets volume and
#     kept; random, so nobody can sign an event with it, and no endpoint in Stripe's dashboard has it.
# Each placeholder is announced on every start: Stripe is not configured, top-ups and payouts do not work. A value
# that is set always wins. Live mode (QB_STRIPE_LIVEMODE, or QUIZBUKS_STRIPE_LIVEMODE, anything but false) never gets
# a placeholder, so its startup check still fails on a missing key or secret.
#
# QB_ENTRYPOINT_SECRETS and QB_ENTRYPOINT_NEXT exist for the tests (tools/quizbuks/test/entrypoint.test.sh).
set -eu

SECRETS=${QB_ENTRYPOINT_SECRETS:-/var/lib/jabiz/secrets}
NEXT=${QB_ENTRYPOINT_NEXT:-/app/platform-entrypoint.sh}
README='deploy/quizbuks/README.md, "本机的 compose"'

warn() { # what
    echo "quizbuks: WARNING: $1; Stripe is not configured: top-ups and payouts will not work until real values" \
        "are set ($README)" >&2
}

# Writes whsec_ and 48 hex digits to <file> unless it exists.
generate_webhook_secret() { # file
    if [ ! -s "$1" ]; then
        (umask 077 && printf 'whsec_%s' "$(od -An -tx1 -N24 /dev/urandom | tr -d ' \n')" > "$1")
        return 0
    fi
    return 1
}

placeholders=false
if [ "${QB_STRIPE_PLACEHOLDERS:-false}" = true ]; then
    case "${QB_STRIPE_LIVEMODE:-false}/${QUIZBUKS_STRIPE_LIVEMODE:-false}" in
        [Ff][Aa][Ll][Ss][Ee]/[Ff][Aa][Ll][Ss][Ee]) placeholders=true ;;
        *) echo "quizbuks: live mode: no Stripe placeholders (STRIPE_API_KEY and the webhook secrets must be set)" >&2 ;;
    esac
fi

if $placeholders; then
    if [ -z "${STRIPE_API_KEY:-}" ]; then
        STRIPE_API_KEY=sk_test_quizbuks_local_placeholder_not_a_stripe_key
        export STRIPE_API_KEY
        warn "STRIPE_API_KEY is not set, using a fake test-mode placeholder"
    fi
    if [ -z "${STRIPE_WEBHOOK_SECRET:-}" ]; then
        if generate_webhook_secret "$SECRETS/stripe-webhook-secret"; then
            echo "quizbuks: generated a placeholder Stripe webhook secret in the jabiz-secrets volume"
        fi
        STRIPE_WEBHOOK_SECRET=$(cat "$SECRETS/stripe-webhook-secret")
        export STRIPE_WEBHOOK_SECRET
        warn "STRIPE_WEBHOOK_SECRET is not set, using a placeholder"
    fi
    if [ -z "${STRIPE_CONNECT_WEBHOOK_SECRET:-}" ]; then
        if generate_webhook_secret "$SECRETS/stripe-connect-webhook-secret"; then
            echo "quizbuks: generated a placeholder Stripe Connect webhook secret in the jabiz-secrets volume"
        fi
        STRIPE_CONNECT_WEBHOOK_SECRET=$(cat "$SECRETS/stripe-connect-webhook-secret")
        export STRIPE_CONNECT_WEBHOOK_SECRET
        warn "STRIPE_CONNECT_WEBHOOK_SECRET is not set, using a placeholder"
    fi
fi

exec "$NEXT" "$@"
