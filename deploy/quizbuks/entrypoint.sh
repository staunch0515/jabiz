#!/bin/sh
# QuizBuks's entrypoint: checks that the platform's secrets are given, fills Stripe's settings for a plain local
# `docker compose up` (deploy/quizbuks/README.md, "本机的 compose"), then hands over to the platform's entrypoint
# (docker/app-entrypoint.sh, unchanged).
#
# Secrets (Q10b, review M5). The platform's entrypoint generates a missing JABIZ_JWT_SECRET, JABIZ_INTEGRITY_KEY,
# JABIZ_MFA_KEY and first-administrator password (this script a missing JABIZ_SEAL_KEY, Q29) into the secrets volume, which is meant for a local demonstration
# only. So unless QB_LOCAL_SECRETS=true (the local compose and tools/quizbuks/local-demo.sh set it, nothing else), a
# missing key refuses the start, as does a missing JABIZ_BOOTSTRAP_ADMIN_PASSWORD unless QB_BOOTSTRAP_ADMIN_DONE=true
# says the first administrator exists (the platform creates it only in an empty database; the password is then given
# a random value nobody sees, so the platform's entrypoint does not make and print one). In live mode
# (QB_STRIPE_LIVEMODE or QUIZBUKS_STRIPE_LIVEMODE other than false, or a live Stripe key) nothing is ever generated,
# QB_LOCAL_SECRETS or not. A generated administrator password is never printed: it goes into a read-only file in the
# secrets volume, and its path is printed. Whenever a key or the password is generated (or read back from the volume
# where it was generated), QB_SECRETS_GENERATED=true is exported: the application's startup check refuses that next to
# live mode however live mode is set (review L5). compose.dev.yaml leaves QB_LOCAL_SECRETS false: a local
# demonstration sets it itself.
#
# Stripe placeholders only with QB_STRIPE_PLACEHOLDERS=true (compose.dev.yaml sets it by default; the image alone
# does not), only in test mode, and only for values that are not set:
#   - STRIPE_API_KEY: a test-mode key that is plainly fake; every call to Stripe is refused.
#   - STRIPE_WEBHOOK_SECRET / STRIPE_CONNECT_WEBHOOK_SECRET: generated once (whsec_ + 48 hex) into the secrets volume and
#     kept; random, so nobody can sign an event with it, and no endpoint in Stripe's dashboard has it.
# Each placeholder is announced on every start: Stripe is not configured, top-ups and payouts do not work. A value
# that is set always wins. Live mode never gets a placeholder, so its startup check still fails on a missing key or
# secret.
#
# QB_ENTRYPOINT_SECRETS and QB_ENTRYPOINT_NEXT exist for the tests (tools/quizbuks/test/entrypoint.test.sh).
set -eu

SECRETS=${QB_ENTRYPOINT_SECRETS:-/var/lib/jabiz/secrets}
NEXT=${QB_ENTRYPOINT_NEXT:-/app/platform-entrypoint.sh}
README='deploy/quizbuks/README.md, "本机的 compose"'
SECRETS_README='deploy/quizbuks/README.md, "密钥与部署"'

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

# 24 random characters (hex), never shown.
random_text() {
    od -An -tx1 -N12 /dev/urandom | tr -d ' \n'
}

live=false
case "${QB_STRIPE_LIVEMODE:-false}/${QUIZBUKS_STRIPE_LIVEMODE:-false}" in
    [Ff][Aa][Ll][Ss][Ee]/[Ff][Aa][Ll][Ss][Ee]) ;;
    *) live=true ;;
esac
case "${STRIPE_API_KEY:-}" in
    sk_live_* | rk_live_*) live=true ;;
esac

local_secrets=false
if [ "${QB_LOCAL_SECRETS:-false}" = true ]; then
    if $live; then
        echo "quizbuks: live mode: QB_LOCAL_SECRETS is ignored, no secret is generated" >&2
    else
        local_secrets=true
    fi
fi

generated=false
missing=""
if [ -z "${JABIZ_JWT_SECRET:-}" ]; then missing="$missing JABIZ_JWT_SECRET"; fi
if [ -z "${JABIZ_INTEGRITY_KEY:-}" ]; then missing="$missing JABIZ_INTEGRITY_KEY"; fi
if [ -z "${JABIZ_MFA_KEY:-}" ]; then missing="$missing JABIZ_MFA_KEY"; fi
if [ -z "${JABIZ_SEAL_KEY:-}" ]; then missing="$missing JABIZ_SEAL_KEY"; fi
if [ -z "${JABIZ_BOOTSTRAP_ADMIN_PASSWORD:-}" ]; then
    if $local_secrets; then
        if [ ! -s "$SECRETS/admin-password" ]; then
            (umask 077 && random_text > "$SECRETS/admin-password")
            chmod 400 "$SECRETS/admin-password"
            echo "quizbuks: first start: the first administrator's password is in $SECRETS/admin-password" \
                "(read it with: docker compose -f deploy/quizbuks/compose.dev.yaml exec app cat" \
                "$SECRETS/admin-password)"
        fi
        JABIZ_BOOTSTRAP_ADMIN_PASSWORD=$(cat "$SECRETS/admin-password")
        export JABIZ_BOOTSTRAP_ADMIN_PASSWORD
        generated=true
    elif [ "${QB_BOOTSTRAP_ADMIN_DONE:-false}" = true ]; then
        JABIZ_BOOTSTRAP_ADMIN_PASSWORD=$(random_text)
        export JABIZ_BOOTSTRAP_ADMIN_PASSWORD
    else
        missing="$missing JABIZ_BOOTSTRAP_ADMIN_PASSWORD"
    fi
fi
if [ -n "$missing" ] && ! $local_secrets; then
    echo "quizbuks: refusing to start: not set:$missing. Give every secret from the secret store" \
        "(QB_LOCAL_SECRETS=true generates them for a local demonstration only, never in live mode;" \
        "QB_BOOTSTRAP_ADMIN_DONE=true once the first administrator exists) ($SECRETS_README)" >&2
    exit 1
fi
# The platform's entrypoint makes the missing keys (local only, as checked above), except the key sealing the
# sponsors' mail server passwords (Q29, platform decision D56), which the platform's entrypoint does not know: it is
# generated here, once, into the secrets volume.
if [ -n "$missing" ]; then generated=true; fi
if $local_secrets && [ -z "${JABIZ_SEAL_KEY:-}" ]; then
    if [ ! -s "$SECRETS/seal-key" ]; then
        (umask 077 && head -c 48 /dev/urandom | base64 | tr -d '\n' > "$SECRETS/seal-key")
        chmod 400 "$SECRETS/seal-key"
        echo "quizbuks: generated the secret-sealing key in the jabiz-secrets volume (keep a copy: the sponsors'" \
            "mail server passwords need it)"
    fi
    JABIZ_SEAL_KEY=$(cat "$SECRETS/seal-key")
    export JABIZ_SEAL_KEY
fi
if $generated; then
    QB_SECRETS_GENERATED=true
    export QB_SECRETS_GENERATED
fi

placeholders=false
if [ "${QB_STRIPE_PLACEHOLDERS:-false}" = true ]; then
    if $live; then
        echo "quizbuks: live mode: no Stripe placeholders (STRIPE_API_KEY and the webhook secrets must be set)" >&2
    else
        placeholders=true
    fi
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
