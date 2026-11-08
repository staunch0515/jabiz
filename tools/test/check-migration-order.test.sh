#!/usr/bin/env bash
# Tests of tools/check-migration-order.sh against throwaway repositories, offline: the platform's three sequences
# (db/jabiz, the demo application's migrations and its test migrations) checked separately, a migration behind the
# base, a rename, a version twice, the branch moving on after the pull request branched, fixtures outside every
# sequence, and an application branch's own sequence given as an argument (the case of 2026-11-08: base V81, V80).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
script="$root/tools/check-migration-order.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

fail() {
  echo "FAIL $1"
  failures=$((failures + 1))
}

jabiz=backend/runtime/src/main/resources/db/jabiz
app=backend/app/src/main/resources/db/migration
testapp=backend/app/src/test/resources/db/testmigration
qb=backend/quizbuks/src/main/resources/db/migration
shop=backend/quizbuks-shopify/src/main/resources/db/migration
qb_sequence='backend/quizbuks[^/]*/src/main/resources/db/migration'

new_repo() {
  local dir="$work/$1"
  shift
  rm -rf "$dir"
  mkdir -p "$dir"
  git -C "$dir" init -q -b base
  git -C "$dir" config user.email test@example.org
  git -C "$dir" config user.name test
  for path in "$@"; do add "$dir" "$path"; done
  echo readme >"$dir/README"
  commit "$dir" base
  echo "$dir"
}

add() {
  mkdir -p "$1/$(dirname "$2")"
  echo "select 1;" >"$1/$2"
}

commit() {
  git -C "$1" add -A
  git -C "$1" commit -qm "$2"
}

branch() {
  git -C "$1" checkout -q base
  git -C "$1" checkout -qb "$2"
}

# expect <name> <dir> <head> pass|fail [text] [-- sequences...]
expect() {
  local name=$1 dir=$2 head=$3 outcome=$4 text=${5:-}
  shift 5 || shift $#
  [[ ${1:-} == -- ]] && shift
  local out status=0
  out=$(cd "$dir" && "$script" base "$head" "$@" 2>&1) || status=$?
  if [[ $outcome == pass && $status -ne 0 ]]; then
    fail "$name: expected to pass, got: $out"
  elif [[ $outcome == fail && $status -ne 1 ]]; then
    fail "$name: expected to fail (1), got $status: $out"
  elif [[ -n $text && $out != *"$text"* ]]; then
    fail "$name: output lacks '$text': $out"
  else
    echo "ok   $name"
  fi
}

d=$(new_repo platform "$jabiz/V64__a.sql" "$jabiz/V65__key_rotation.sql" "$app/V19__warehouse.sql" \
  "$testapp/V1017__it_sealed_pair.sql" "backend/app/src/test/resources/it/sealed-history/kept/V1__x.sql")

branch "$d" behind
add "$d" "$jabiz/V64__mine.sql"
commit "$d" behind
expect "platform: V64 behind V65 (and V64 twice)" "$d" behind fail "基线已到 V65，请改为 V66"

branch "$d" next
add "$d" "$jabiz/V66__mine.sql"
commit "$d" next
expect "platform: V66 after V65" "$d" next pass ""

# The demo application's V20 is not behind its test migrations' V1017, nor the platform's V65: three sequences.
branch "$d" demo
add "$d" "$app/V20__demo.sql"
commit "$d" demo
expect "demo application V20: its own sequence" "$d" demo pass ""

branch "$d" demo-behind
add "$d" "$app/V18__old.sql"
commit "$d" demo-behind
expect "demo application V18 behind its V19" "$d" demo-behind fail "基线已到 V19，请改为 V20"

branch "$d" test-behind
add "$d" "$testapp/V1016__old.sql"
commit "$d" test-behind
expect "test migration V1016 behind V1017" "$d" test-behind fail "请改为 V1018"

branch "$d" rename
git -C "$d" mv "$jabiz/V65__key_rotation.sql" "$jabiz/V67__key_rotation.sql"
commit "$d" rename
expect "rename V65 to V67" "$d" rename pass ""

branch "$d" twice
add "$d" "$jabiz/V66__one.sql"
add "$d" "$jabiz/V66__two.sql"
commit "$d" twice
expect "V66 twice in one pull request" "$d" twice fail "V66 occurs twice"

branch "$d" fixtures
add "$d" "backend/app/src/test/resources/it/sealed-history/kept/V2__y.sql"
add "$d" "backend/runtime/src/test/resources/db/legacyapp/V1__z.sql"
commit "$d" fixtures
expect "fixtures outside every sequence" "$d" fixtures pass ""

# The branch moves on: its latest commit counts, then merging it in and renumbering passes.
branch "$d" late
add "$d" "$jabiz/V66__late.sql"
commit "$d" late
git -C "$d" checkout -q base
add "$d" "$jabiz/V66__first.sql"
commit "$d" "V66 on the branch"
expect "branch moved on to V66, pull request still V66" "$d" late fail "基线已到 V66，请改为 V67"
git -C "$d" checkout -q late
git -C "$d" merge -q --no-edit base
git -C "$d" mv "$jabiz/V66__late.sql" "$jabiz/V67__late.sql"
commit "$d" renumber
expect "branch merged in, renumbered V67" "$d" late pass ""

# An application branch's own sequence, given as an argument: the QuizBuks case of 2026-11-08.
q=$(new_repo app-branch "$qb/V79__a.sql" "$qb/V81__b.sql" "$shop/V35__c.sql" "$jabiz/V65__k.sql")
branch "$q" v80
add "$q" "$qb/V80__foreign_videos.sql"
commit "$q" v80
expect "application sequence: base V81, V80" "$q" v80 fail "基线已到 V81，请改为 V82" -- "$qb_sequence"
branch "$q" v82
add "$q" "$qb/V82__ok.sql"
commit "$q" v82
expect "application sequence: V82 after V81" "$q" v82 pass "" -- "$qb_sequence"
branch "$q" shop
add "$q" "$shop/V36__shop.sql"
add "$q" "$qb/V36__also.sql"
commit "$q" shop
expect "application sequence: two modules, V36 twice" "$q" shop fail "V36 occurs twice" -- "$qb_sequence"

status=0
"$script" only-one >/dev/null 2>&1 || status=$?
if [[ $status -eq 2 ]]; then echo "ok   usage on one argument"; else fail "one argument: status $status, not 2"; fi

if ((failures > 0)); then
  echo "$failures failure(s)"
  exit 1
fi
echo "all check-migration-order tests passed"
