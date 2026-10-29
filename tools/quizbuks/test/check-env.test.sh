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

status=0
out="$(/bin/sh "$script" "$work/none" "$work/required" 2>&1)" || status=$?
expect "no .env" 1 "cannot read"

# The committed list: names (`?` when optional), each with an optional length (`-` for any) and start.
if grep -vE '^(#.*|\??[A-Z][A-Z0-9_]*( ([0-9]+|-)( [^ ]+)?)?|)$' "$root/deploy/quizbuks/server/required-env"; then
  echo "FAIL required-env has a line that is not a name with an optional length and start"
  failures=$((failures + 1))
else
  echo "ok   required-env parses"
fi

[ "$failures" -eq 0 ] || { echo "$failures failure(s)"; exit 1; }
