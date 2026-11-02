#!/usr/bin/env bash
# Tests of deploy/quizbuks/server/check-env.sh (deploy/quizbuks/DEPLOY.md): a .env against a list of required
# variables, as the deploy workflow checks it on the server; and that the committed list parses.
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
script="$root/deploy/quizbuks/server/check-env.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

cat >"$work/required" <<'LIST'
# a comment, and an empty line

PLAIN
LONG 8
?MAYBE - r2dbc:
LIST

# run <.env content>: the check; its output in $out, its status in $status.
run() {
  printf '%s\n' "$1" >"$work/env"
  status=0
  out="$(/bin/sh "$script" "$work/env" "$work/required" 2>&1)" || status=$?
}

expect() {
  local name="$1" want="$2" pattern="${3:-}"
  if [ "$status" != "$want" ] || { [ -n "$pattern" ] && ! grep -q -- "$pattern" <<<"$out"; }; then
    echo "FAIL $name: status $status, output: $out"
    failures=$((failures + 1))
  else
    echo "ok   $name"
  fi
}

run $'APP_NAME=x\nPLAIN=a\nLONG=12345678'
expect "everything given" 0

run $'PLAIN=a\nLONG=1234567'
expect "too short" 1 "LONG is shorter than 8 characters"

run $'LONG=12345678'
expect "missing" 1 "PLAIN is not set"

run $'PLAIN=\nLONG=12345678'
expect "empty" 1 "PLAIN is not set"

run $'PLAIN=a\nLONG=12345678\nPLAIN='
expect "the last definition counts" 1 "PLAIN is not set"

run $'PLAIN=\'\'\nLONG="12345678"'
expect "quotes are not the value" 1 "PLAIN is not set"

run $'# PLAIN=a\nXPLAIN=a\nLONG=12345678'
expect "only the variable's own line" 1 "PLAIN is not set"

run $'PLAIN=a\nLONG=zq7x'
if grep -q "zq7x" <<<"$out"; then
  echo "FAIL a value is printed: $out"
  failures=$((failures + 1))
else
  echo "ok   no value is printed"
fi

run $'PLAIN=a\nLONG=12345678\nMAYBE=r2dbc:postgresql://postgres:5432/db'
expect "an optional variable of the right form" 0

run $'PLAIN=a\nLONG=12345678\nMAYBE=jdbc:postgresql://postgres:5432/db'
expect "an optional variable of the wrong form" 1 "MAYBE must start with r2dbc:"
if grep -q "postgres:5432" <<<"$out"; then
  echo "FAIL a value is printed: $out"
  failures=$((failures + 1))
fi

run $'PLAIN=a\nLONG=12345678\nMAYBE='
expect "an optional variable left empty" 0

# Variables of a Spring profile: needed only when SPRING_PROFILES_ACTIVE names it.
cp "$work/required" "$work/plain"
cat >>"$work/required" <<'LIST'
social:SOCIAL_ID
social:SOCIAL_URL - https://
LIST
run $'PLAIN=a\nLONG=12345678\nSPRING_PROFILES_ACTIVE=staging'
expect "a profile not active asks for nothing" 0
run $'PLAIN=a\nLONG=12345678'
expect "no profiles ask for nothing" 0
run $'PLAIN=a\nLONG=12345678\nSPRING_PROFILES_ACTIVE="staging, social"'
expect "an active profile asks for its variables" 1 "SOCIAL_ID is not set"
expect "each of them" 1 "SOCIAL_URL is not set"
run $'PLAIN=a\nLONG=12345678\nSPRING_PROFILES_ACTIVE=staging,social\nSOCIAL_ID=x\nSOCIAL_URL=http://x'
expect "checked like the others" 1 "SOCIAL_URL must start with https://"
run $'PLAIN=a\nLONG=12345678\nSPRING_PROFILES_ACTIVE=staging,social\nSOCIAL_ID=x\nSOCIAL_URL=https://x'
expect "an active profile's variables given" 0
run $'PLAIN=a\nLONG=12345678\nSPRING_PROFILES_ACTIVE=socials'
expect "only the profile's own name" 0
cp "$work/plain" "$work/required"

# Forms, as the application checks the values at startup: placeholders left in stop the deployment here.
cat >>"$work/required" <<'LIST'
?TEAM 10 - apple-id
?FORM_POST - https:// url
?KEY - - p8
LIST
key="$(openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 2>/dev/null)"
key_one_line="$(printf '%s' "$key" | awk 'BEGIN { ORS = "\\n" } { print }')"
key_body="$(printf '%s\n' "$key" | grep -v -- '-----' | tr -d '\n')"
run $'PLAIN=a\nLONG=12345678\nTEAM=ABCDE12345\nFORM_POST=https://staging.example.com/api/auth/oidc/apple/form-post\nKEY='"$key_one_line"
expect "values of the right forms (a PEM on one line)" 0
run $'PLAIN=a\nLONG=12345678\nKEY='"$key_body"
expect "a key's Base64 body" 0
run $'PLAIN=a\nLONG=12345678\nKEY="'"$key_one_line"'"'
expect "a key in quotes" 0
run $'PLAIN=a\nLONG=12345678\nTEAM=YOUR_TEAM_ID'
expect "a placeholder id" 1 "TEAM is not of the form apple-id"
run $'PLAIN=a\nLONG=12345678\nTEAM=abcde12345'
expect "an id in small letters" 1 "TEAM is not of the form apple-id"
run $'PLAIN=a\nLONG=12345678\nTEAM=ABCDE123456'
expect "an id too long" 1 "TEAM is not of the form apple-id"
run $'PLAIN=a\nLONG=12345678\nFORM_POST=https://<host>/api/auth/oidc/apple/form-post'
expect "a placeholder host" 1 "FORM_POST is not of the form url"
run $'PLAIN=a\nLONG=12345678\nFORM_POST=https:///api'
expect "no host" 1 "FORM_POST is not of the form url"
run $'PLAIN=a\nLONG=12345678\nKEY=YOUR_PRIVATE_KEY'
expect "a placeholder key" 1 "KEY is not of the form p8"
run $'PLAIN=a\nLONG=12345678\nKEY=-----BEGIN PRIVATE KEY-----'
expect "only the first line of a PEM" 1 "KEY is not of the form p8"
if grep -q "YOUR_PRIVATE_KEY\|staging.example" <<<"$out"; then
  echo "FAIL a value is printed: $out"
  failures=$((failures + 1))
fi
run $'PLAIN=a\r\nLONG=12345678\r'
expect "Windows line endings" 1 "PLAIN has a carriage return"
cp "$work/plain" "$work/required"

status=0
out="$(/bin/sh "$script" "$work/none" "$work/required" 2>&1)" || status=$?
expect "no .env" 1 "cannot read"

# VAPID keys (platform 17k): the halves of a pair as the README makes them; a PEM or a padded value stops.
cat >>"$work/required" <<'LIST'
?VAPID_PUBLIC 87 - vapid-public
?VAPID_PRIVATE 43 - vapid-private
LIST
openssl ecparam -name prime256v1 -genkey -noout -out "$work/vapid.pem" 2>/dev/null
vpub="$(openssl ec -in "$work/vapid.pem" -pubout -outform DER 2>/dev/null | tail -c 65 | base64 | tr '/+' '_-' | tr -d '=\n')"
vpriv="$(openssl ec -in "$work/vapid.pem" -outform DER 2>/dev/null | tail -c +8 | head -c 32 | base64 | tr '/+' '_-' | tr -d '=\n')"
run $'PLAIN=a\nLONG=12345678\nVAPID_PUBLIC='"$vpub"$'\nVAPID_PRIVATE='"$vpriv"
expect "a VAPID key pair as the README makes it" 0
run $'PLAIN=a\nLONG=12345678\nVAPID_PUBLIC='"$vpub"$'==\nVAPID_PRIVATE='"$vpriv"'x'
expect "a padded public key" 1 "VAPID_PUBLIC is not of the form vapid-public"
expect "a private key too long" 1 "VAPID_PRIVATE is not of the form vapid-private"
run $'PLAIN=a\nLONG=12345678\nVAPID_PRIVATE=-----BEGIN EC PRIVATE KEY-----MHcCAQEEIAbcdefghijklmnopqrstuvwxyz0123456789ABCDEFGH'
expect "a PEM for the private key" 1 "VAPID_PRIVATE is not of the form vapid-private"

# The committed list against a staging .env: "social" asks nothing of Apple; "apple" asks Apple's values of their forms,
# so placeholders stop the deployment (as the application's startup check would, after migrating).
base="$(grep -E '^[A-Z]' "$root/deploy/quizbuks/server/required-env" | awk '{ print $1 "=" "0123456789abcdef0123456789abcdef0123456789" }')"
three=$'JABIZ_OIDC_GOOGLE_CLIENT_ID=g.apps.googleusercontent.com\nJABIZ_OIDC_GOOGLE_CLIENT_SECRET=gs
JABIZ_OIDC_FACEBOOK_CLIENT_ID=1234567890\nJABIZ_OIDC_FACEBOOK_CLIENT_SECRET=fs
JABIZ_OIDC_LINE_CLIENT_ID=2000000000\nJABIZ_OIDC_LINE_CLIENT_SECRET=0123456789abcdef0123456789abcdef
QB_OIDC_REDIRECT_APP=https://staging.example.com/taker/login/oidc
QB_OIDC_REDIRECT_SPONSOR=https://staging.example.com/sponsor/login/oidc'
cp "$work/required" "$work/plain"
cp "$root/deploy/quizbuks/server/required-env" "$work/required"
run "$base"$'\nSPRING_PROFILES_ACTIVE=staging,social\n'"$three"
expect "staging with social: three providers, nothing of Apple's" 0
run "$base"$'\nSPRING_PROFILES_ACTIVE=staging,apple\n'"$three"$'\nJABIZ_OIDC_APPLE_CLIENT_ID=com.example.web
JABIZ_OIDC_APPLE_TEAM_ID=YOUR_TEAM_ID\nJABIZ_OIDC_APPLE_KEY_ID=YOUR_KEY_ID\nJABIZ_OIDC_APPLE_PRIVATE_KEY=YOUR_PRIVATE_KEY
QB_OIDC_APPLE_FORM_POST=https://<host>/api/auth/oidc/apple/form-post'
expect "staging with apple and placeholders" 1 "JABIZ_OIDC_APPLE_TEAM_ID is not of the form apple-id"
expect "the key too" 1 "JABIZ_OIDC_APPLE_PRIVATE_KEY is not of the form p8"
expect "the return URL too" 1 "QB_OIDC_APPLE_FORM_POST is not of the form url"
run "$base"$'\nSPRING_PROFILES_ACTIVE=staging,apple\n'"$three"$'\nJABIZ_OIDC_APPLE_CLIENT_ID=com.example.web
JABIZ_OIDC_APPLE_TEAM_ID=ABCDE12345\nJABIZ_OIDC_APPLE_KEY_ID=KEY1234567\nJABIZ_OIDC_APPLE_PRIVATE_KEY='"$key_body"'
QB_OIDC_APPLE_FORM_POST=https://staging.example.com/api/auth/oidc/apple/form-post'
expect "staging with apple and real values" 0
cp "$work/plain" "$work/required"

# The committed list: names (`?` when optional), each with an optional length (`-` for any), start (`-` for any) and
# form.
if grep -vE '^(#.*|\??([a-z][a-z0-9-]*:)?[A-Z][A-Z0-9_]*( ([0-9]+|-)( [^ ]+( (apple-id|url|p8|vapid-public|vapid-private))?)?)?|)$' \
  "$root/deploy/quizbuks/server/required-env"; then
  echo "FAIL required-env has a line that is not a name with an optional length, start and form"
  failures=$((failures + 1))
else
  echo "ok   required-env parses"
fi

[ "$failures" -eq 0 ] || { echo "$failures failure(s)"; exit 1; }
