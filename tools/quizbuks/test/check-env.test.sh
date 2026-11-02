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

# The committed list: names (`?` when optional), each with an optional length (`-` for any), start (`-` for any) and
# form.
if grep -vE '^(#.*|\??([a-z][a-z0-9-]*:)?[A-Z][A-Z0-9_]*( ([0-9]+|-)( [^ ]+( (apple-id|url|p8))?)?)?|)$' \
  "$root/deploy/quizbuks/server/required-env"; then
  echo "FAIL required-env has a line that is not a name with an optional length, start and form"
  failures=$((failures + 1))
else
  echo "ok   required-env parses"
fi

[ "$failures" -eq 0 ] || { echo "$failures failure(s)"; exit 1; }
