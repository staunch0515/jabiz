#!/bin/sh
# Starts the application. For the local demonstration (docker compose), a missing access-token signing key, a missing
# integrity seal key, a missing second-factor key and a missing first-administrator password are generated once and
# kept in the jabiz-secrets volume, so the system starts with one command and no secret is ever written into the
# repository. Real deployments pass JABIZ_JWT_SECRET, JABIZ_INTEGRITY_KEY, JABIZ_MFA_KEY and the bootstrap
# administrator from their secret store (docs/design/10-security.md sections 2, 7 and 9, 21-audit-retention.md
# section 2.4).
set -eu

SECRETS=/var/lib/jabiz/secrets

# Writes Base64 of <bytes> random bytes to <file> unless it exists; <strip> removes characters (e.g. for passwords).
generate() { # file, bytes, strip
    if [ ! -s "$1" ]; then
        (umask 077 && head -c "$2" /dev/urandom | base64 | tr -d "\n$3" > "$1")
        return 0
    fi
    return 1
}

if [ -z "${JABIZ_JWT_SECRET:-}" ]; then
    if generate "$SECRETS/jwt-secret" 48 ""; then
        echo "jabiz: generated an access-token signing key in the jabiz-secrets volume"
    fi
    JABIZ_JWT_SECRET=$(cat "$SECRETS/jwt-secret")
    export JABIZ_JWT_SECRET
fi

if [ -z "${JABIZ_INTEGRITY_KEY:-}" ]; then
    if generate "$SECRETS/integrity-key" 48 ""; then
        echo "jabiz: generated an integrity seal key in the jabiz-secrets volume (keep a copy: seals need it)"
    fi
    JABIZ_INTEGRITY_KEY=$(cat "$SECRETS/integrity-key")
    export JABIZ_INTEGRITY_KEY
fi

if [ -z "${JABIZ_MFA_KEY:-}" ]; then
    if generate "$SECRETS/mfa-key" 48 ""; then
        echo "jabiz: generated a second-factor key in the jabiz-secrets volume (keep a copy: enrolled users need it)"
    fi
    JABIZ_MFA_KEY=$(cat "$SECRETS/mfa-key")
    export JABIZ_MFA_KEY
fi

export JABIZ_BOOTSTRAP_ADMIN_USER="${JABIZ_BOOTSTRAP_ADMIN_USER:-admin}"
if [ -z "${JABIZ_BOOTSTRAP_ADMIN_PASSWORD:-}" ]; then
    if generate "$SECRETS/admin-password" 18 "/+="; then
        echo "=================================================================================="
        echo " jabiz: first start. Sign in as '$JABIZ_BOOTSTRAP_ADMIN_USER' with password: $(cat "$SECRETS/admin-password")"
        echo " (shown once; later: docker compose exec app cat $SECRETS/admin-password)"
        echo "=================================================================================="
    fi
    JABIZ_BOOTSTRAP_ADMIN_PASSWORD=$(cat "$SECRETS/admin-password")
    export JABIZ_BOOTSTRAP_ADMIN_PASSWORD
fi

exec java ${JAVA_OPTS:-} -Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true -jar /app/app.jar "$@"
