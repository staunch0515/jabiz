#!/usr/bin/env bash
# Read-only check of a production settings file before going live (docs/guide/platform-operations.md section 1):
# the platform's rules, plus the application's own in an optional rules file. Prints the names of the variables that
# are missing or wrong, never a value. Exit 0 when nothing is wrong (warnings allowed), 1 otherwise.
#
#   env-check.sh <settings file> [<application rules file>]
#
# An application rules file has one rule per line ('#' starts a comment):
#   set NAME              NAME must be set and not empty
#   absent NAME           NAME must not be set (test switches)
#   equals NAME VALUE     NAME must be exactly VALUE
#   not-equals NAME VALUE NAME must not be VALUE
#   warn-absent NAME      a warning when NAME is set
set -euo pipefail

file=${1:-}
rules=${2:-}
[[ -f "$file" ]] || { echo "usage: env-check.sh <settings file> [<application rules file>]" >&2; exit 2; }
[[ -z "$rules" || -f "$rules" ]] || { echo "env-check: no rules file $rules" >&2; exit 2; }

errors=0
warnings=0
declare -A env=()
while IFS= read -r line || [[ -n "$line" ]]; do
  line=${line%$'\r'}
  [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
  line=${line#export }
  [[ "$line" == *=* ]] || continue
  name=${line%%=*}
  value=${line#*=}
  value=${value%\"}; value=${value#\"}; value=${value%\'}; value=${value#\'}
  env[$name]=$value
done <"$file"

error() { echo "ERROR    $1: $2"; errors=$((errors + 1)); }
warn() { echo "WARNING  $1: $2"; warnings=$((warnings + 1)); }
has() { [[ -n "${env[$1]:-}" ]]; }

# A Base64 key of at least 32 bytes.
key() {
  local name=$1 bytes
  if ! has "$name"; then
    error "$name" "not set"
    return
  fi
  if ! bytes=$(printf '%s' "${env[$name]}" | base64 -d 2>/dev/null | wc -c); then
    error "$name" "not valid Base64"
    return
  fi
  ((bytes >= 32)) || error "$name" "shorter than 32 bytes"
}

# Keys of the platform (docs/guide/key-rotation.md): set, long enough, all different, none a known test value.
keys=(JABIZ_JWT_SECRET JABIZ_INTEGRITY_KEY JABIZ_MFA_KEY)
for name in "${keys[@]}"; do key "$name"; done
has JABIZ_SEAL_KEY && key JABIZ_SEAL_KEY
known_test_values=(
  # backend/app/src/test/resources/config/application.properties
  dGVzdC1vbmx5LWtleS1vZi10aGUtamFiaXotaW50ZWdyYXRpb24tdGVzdHMtMDAwMDA=
  dGVzdC1vbmx5LWludGVncml0eS1rZXktb2YtdGhlLWphYml6LXRlc3RzLTAwMDAwMA==
  dGVzdC1vbmx5LW1mYS1rZXktb2YtdGhlLWphYml6LWludGVncmF0aW9uLXRlc3RzLTA=
  dGVzdC1vbmx5LXNlYWwta2V5LW9mLXRoZS1qYWJpei1pbnRlZ3JhdGlvbi10ZXN0cy0wMDA=
)
seen=()
for name in JABIZ_JWT_SECRET JABIZ_INTEGRITY_KEY JABIZ_MFA_KEY JABIZ_SEAL_KEY; do
  has "$name" || continue
  for test in "${known_test_values[@]}"; do
    [[ "${env[$name]}" == "$test" ]] && error "$name" "is a test key from the repository"
  done
  for other in "${seen[@]}"; do
    [[ "${env[$name]}" == "${env[$other]}" ]] && error "$name" "is the same as $other: every key must be its own"
  done
  seen+=("$name")
done
for name in JABIZ_JWT_SECRET_PREVIOUS JABIZ_MFA_KEY_PREVIOUS JABIZ_SEAL_KEY_PREVIOUS; do
  has "$name" && warn "$name" "a key rotation is in progress: remove it when it is over (key-rotation.md)"
done

# Profiles, first administrator, switches only tests use.
[[ "${env[SPRING_PROFILES_ACTIVE]:-}" =~ (^|,)[[:space:]]*dev[[:space:]]*(,|$) ]] \
  && error SPRING_PROFILES_ACTIVE "contains dev (development headers and keys)"
for name in JABIZ_BOOTSTRAP_ADMIN_USER JABIZ_BOOTSTRAP_ADMIN_PASSWORD; do
  has "$name" && error "$name" "remove it once the first administrator exists"
done
[[ "${env[JABIZ_SECURITY_MFA_ADMINISTRATION]:-}" == "false" ]] \
  && error JABIZ_SECURITY_MFA_ADMINISTRATION "false turns off the second factor for administration"
has JABIZ_SECURITY_MFA_ADMINISTRATION && [[ "${env[JABIZ_SECURITY_MFA_ADMINISTRATION]}" != "false" ]] \
  && warn JABIZ_SECURITY_MFA_ADMINISTRATION "need not be set (on by default)"
for name in JABIZ_SECURITY_SIGN_IN_RATE_LIMIT_PER_USER_ADDRESS JABIZ_SECURITY_SIGN_IN_RATE_LIMIT_USER_BURST; do
  has "$name" && warn "$name" "raises the sign-in limits (end-to-end tests set it): check it is meant"
done
has JABIZ_STORAGE_WRITE_BATCHING_ENABLED && warn JABIZ_STORAGE_WRITE_BATCHING_ENABLED "an emergency switch: not set normally"

# Running behind a proxy, as production.
has JABIZ_TRUSTED_PROXIES || error JABIZ_TRUSTED_PROXIES "not set: every client address would be the proxy's"
[[ "${env[JABIZ_WEB_ENVIRONMENT]:-}" == "production" ]] \
  || error JABIZ_WEB_ENVIRONMENT "must be production (otherwise the pages show the test system banner)"
has JABIZ_FILES_LOCAL_ROOT || warn JABIZ_FILES_LOCAL_ROOT "not set here (the image sets it): check the volume"

# Alerts (13 section 7).
if ! has JABIZ_ALERT_EMAIL || [[ "${env[JABIZ_ALERT_EMAIL]}" == *@example.com* ]]; then
  error JABIZ_ALERT_EMAIL "set the on-call address"
fi
[[ "${env[GF_SMTP_ENABLED]:-}" == "true" ]] || error GF_SMTP_ENABLED "alerts are mailed: Grafana needs its SMTP settings"

# The application's rules.
if [[ -n "$rules" ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    line=${line%%#*}
    read -r rule name value <<<"$line" || true
    [[ -z "${rule:-}" ]] && continue
    case $rule in
      set) has "$name" || error "$name" "not set" ;;
      absent) has "$name" && error "$name" "must not be set in production" ;;
      equals) [[ "${env[$name]:-}" == "$value" ]] || error "$name" "must be $value" ;;
      not-equals) [[ "${env[$name]:-}" != "$value" ]] || error "$name" "must not be $value" ;;
      warn-absent) has "$name" && warn "$name" "is set: check it is meant" ;;
      *) echo "env-check: unknown rule '$rule' in $rules" >&2; exit 2 ;;
    esac
  done <"$rules"
fi

echo "$errors error(s), $warnings warning(s)"
((errors == 0))
