#!/bin/sh
# check-env.sh <.env> <required-env>: whether the environment's .env gives every variable of the list (required-env in
# this directory), each non-empty, as long as the list asks and starting as it asks; a name marked `?` may be absent,
# but when given is checked the same; a name written `<profile>:NAME` is needed only when the .env's
# SPRING_PROFILES_ACTIVE names that Spring profile (the providers of "social", say). A fourth column names a form the
# value must have, as the application checks it at startup: `apple-id` (ten capital letters or digits), `url` (an https
# address with a host and nothing a URL cannot hold, such as a placeholder's `<host>`), `p8` (a .p8 key: PEM, line
# breaks written \n, or its Base64 body), `vapid-public` / `vapid-private` (the halves of a VAPID key pair). A value with a carriage return (a .env saved with Windows line endings) is
# refused whatever its form. Run by the deploy workflow on the server before the image is pulled. Prints the names of
# what is missing, too short or of the wrong form, never a value; exits 1 if anything is.
set -eu

env_file="$1"
required="$2"
[ -r "$env_file" ] || { echo "cannot read $env_file" >&2; exit 1; }

# value_of NAME: its value in the .env. The last definition counts, as in compose; a value in one pair of quotes is
# the text inside them.
value_of() {
  v=$(sed -n "s/^$1=//p" "$env_file" | tail -n 1)
  case "$v" in
    \'*\') v=${v#\'}; v=${v%\'} ;;
    \"*\") v=${v#\"}; v=${v%\"} ;;
  esac
  printf '%s' "$v"
}

# The active Spring profiles, one per line, without blanks.
profiles=$(value_of SPRING_PROFILES_ACTIVE | tr ',' '\n' | tr -d ' \t')

# has_form VALUE FORM: whether the value has the form.
has_form() {
  case "$2" in
    apple-id) printf '%s' "$1" | grep -Eqx '[A-Z0-9]{10}' ;;
    # A VAPID key pair (platform 17k): the public key an uncompressed P-256 point (65 bytes, first 0x04), the private
    # key 32 bytes, both Base64url without padding.
    vapid-public) printf '%s' "$1" | grep -Eqx 'B[A-Za-z0-9_-]{86}' ;;
    vapid-private) printf '%s' "$1" | grep -Eqx '[A-Za-z0-9_-]{43}' ;;
    url) printf '%s' "$1" | grep -Eqx "https://[A-Za-z0-9.-]+(:[0-9]+)?(/[A-Za-z0-9._~%/!\$&'()*+,;=:@-]*)?" ;;
    p8)
      # A PEM over several lines in one pair of quotes (compose reads it whole) cannot be checked line by line here.
      case "$1" in '"-----BEGIN '*) return 0 ;; esac
      body=$(printf '%s' "$1" | sed -e 's/\\n/ /g' -e 's/-----BEGIN [A-Z ]*PRIVATE KEY-----//g' -e 's/-----END [A-Z ]*PRIVATE KEY-----//g' | tr -d ' \t')
      # A P-256 PKCS #8 key is about 184 Base64 characters.
      printf '%s' "$body" | grep -Eqx '[A-Za-z0-9+/]{100,}={0,2}' ;;
    *) echo "required-env: unknown form $2" >&2; return 1 ;;
  esac
}

cr=$(printf '\r')
problems=
while read -r name min prefix form rest; do
  case "$name" in '' | '#'*) continue ;; esac
  optional=
  case "$name" in '?'*) optional=yes; name=${name#?} ;; esac
  case "$name" in
    *:*)
      profile=${name%%:*}
      name=${name#*:}
      printf '%s\n' "$profiles" | grep -qx -- "$profile" || continue
      ;;
  esac
  [ "$min" = - ] && min=
  [ "$prefix" = - ] && prefix=
  value=$(value_of "$name")
  if [ -z "$value" ]; then
    [ -n "$optional" ] || problems="$problems
  $name is not set"
  elif [ "${value%"$cr"*}" != "$value" ]; then
    problems="$problems
  $name has a carriage return (save the .env with Unix line endings)"
  elif [ -n "$prefix" ] && [ "${value#"$prefix"}" = "$value" ]; then
    problems="$problems
  $name must start with $prefix"
  elif [ -n "$min" ] && [ "${#value}" -lt "$min" ]; then
    problems="$problems
  $name is shorter than $min characters"
  elif [ -n "$form" ] && ! has_form "$value" "$form"; then
    problems="$problems
  $name is not of the form $form (a placeholder left in?)"
  fi
done <"$required"

if [ -n "$problems" ]; then
  echo "$env_file lacks what the application needs to start (deploy/quizbuks/DEPLOY.md):$problems" >&2
  exit 1
fi
