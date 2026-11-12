#!/usr/bin/env bash
# Tests of tools/check-migration-order.sh against throwaway repositories, offline: the platform's three sequences
# (db/jabiz, the demo application's migrations and its test migrations) checked separately, a migration behind the
# base, a rename, a version twice, the branch moving on after the pull request branched, fixtures outside every
# sequence, and an application branch's own sequence given as an argument (the case of 2026-11-08: base V81, V80);
# merged migrations (and their .sql.conf) neither edited, deleted nor renamed, while the pull request's own may be (17zz18).
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

# Renaming a merged migration: databases that ran V65 would find it missing.
branch "$d" rename
git -C "$d" mv "$jabiz/V65__key_rotation.sql" "$jabiz/V67__key_rotation.sql"
commit "$d" rename
expect "rename the merged V65 to V67" "$d" rename fail "renames (to $jabiz/V67__key_rotation.sql) a migration the base already has"

# Editing a merged migration, even a comment, changes its checksum (the V62 of 17zz18).
branch "$d" edit-merged
echo "-- a comment" >>"$d/$jabiz/V64__a.sql"
commit "$d" edit-merged
expect "edit the merged V64" "$d" edit-merged fail "$jabiz/V64__a.sql: the pull request modifies a migration the base already has"

branch "$d" delete-merged
git -C "$d" rm -q "$app/V19__warehouse.sql"
commit "$d" delete-merged
expect "delete the merged demo V19" "$d" delete-merged fail "deletes a migration the base already has"

branch "$d" conf-merged
echo "executeInTransaction=false" >"$d/$jabiz/V65__key_rotation.sql.conf"
commit "$d" conf-merged
expect "a .sql.conf added to a merged migration" "$d" conf-merged fail "adds settings ($jabiz/V65__key_rotation.sql.conf) to"

branch "$d" conf-base
echo "executeInTransaction=false" >"$d/$jabiz/V65__key_rotation.sql.conf"
commit "$d" conf-base
git -C "$d" checkout -q base
git -C "$d" merge -q --ff-only conf-base
branch "$d" conf-edit
echo "executeInTransaction=true" >"$d/$jabiz/V65__key_rotation.sql.conf"
commit "$d" conf-edit
expect "edit the .sql.conf of a merged migration" "$d" conf-edit fail "V65__key_rotation.sql.conf: the pull request modifies"
git -C "$d" checkout -q base
git -C "$d" reset -q --hard HEAD~1

# The exception: a merged migration listed in .jabiz-migration-exceptions may be renamed, content unchanged.
branch "$d" excepted
git -C "$d" mv "$jabiz/V65__key_rotation.sql" "$jabiz/V67__key_rotation.sql"
printf '# merged, never run in production\n%s 2026-11-12 renumbered behind V66, not deployed yet  # ok\n' \
  "$jabiz/V65__key_rotation.sql" >"$d/.jabiz-migration-exceptions"
commit "$d" excepted
expect "a listed merged migration renamed unchanged" "$d" excepted pass ""

branch "$d" excepted-app
git -C "$d" mv "$app/V19__warehouse.sql" "$app/V20__warehouse.sql"
echo "$app/V19__warehouse.sql 2026-11-12 the application's own list" >"$d/.jabiz-app-migration-exceptions"
commit "$d" excepted-app
expect "a merged migration listed in the application's file" "$d" excepted-app pass ""

branch "$d" unlisted
git -C "$d" mv "$jabiz/V65__key_rotation.sql" "$jabiz/V67__key_rotation.sql"
echo "$jabiz/V64__a.sql 2026-11-12 another one" >"$d/.jabiz-migration-exceptions"
commit "$d" unlisted
expect "an unlisted merged migration renamed" "$d" unlisted fail "renames (to $jabiz/V67__key_rotation.sql)"

branch "$d" excepted-edit
git -C "$d" mv "$jabiz/V65__key_rotation.sql" "$jabiz/V67__key_rotation.sql"
echo "-- changed" >>"$d/$jabiz/V67__key_rotation.sql"
echo "$jabiz/V65__key_rotation.sql 2026-11-12 renumbered" >"$d/.jabiz-migration-exceptions"
commit "$d" excepted-edit
expect "a listed merged migration renamed and changed" "$d" excepted-edit fail "allows renaming it with its content unchanged"

branch "$d" excepted-modify
echo "-- changed" >>"$d/$jabiz/V64__a.sql"
echo "$jabiz/V64__a.sql 2026-11-12 not a rename" >"$d/.jabiz-migration-exceptions"
commit "$d" excepted-modify
expect "a listed merged migration modified in place" "$d" excepted-modify fail "nothing else (status M)"

for bad in "$jabiz/V65__key_rotation.sql renumbered" "$jabiz/V65__key_rotation.sql 12.11.2026 renumbered" \
  "$jabiz/V65__key_rotation.sql 2026-11-12"; do
  branch "$d" malformed
  git -C "$d" mv "$jabiz/V65__key_rotation.sql" "$jabiz/V67__key_rotation.sql"
  echo "$bad" >"$d/.jabiz-migration-exceptions"
  commit "$d" malformed
  expect "a malformed exception line ($bad)" "$d" malformed fail ".jabiz-migration-exceptions line 1 is not '<path> <YYYY-MM-DD> <reason>'"
  git -C "$d" checkout -q base
  git -C "$d" branch -qD malformed
done

# The pull request's own migration may be renamed and edited across its commits: the base does not have it.
branch "$d" own
add "$d" "$jabiz/V66__own.sql"
commit "$d" own
echo "-- reworded" >>"$d/$jabiz/V66__own.sql"
commit "$d" own-edit
git -C "$d" mv "$jabiz/V66__own.sql" "$jabiz/V66__own_renamed.sql"
commit "$d" own-rename
expect "rename and edit the pull request's own V66" "$d" own pass ""

# Other files of a migration directory are not migrations.
branch "$d" readme
echo "notes" >"$d/$jabiz/README"
commit "$d" readme-add
git -C "$d" checkout -q base
git -C "$d" merge -q --ff-only readme
branch "$d" readme-edit
echo "more notes" >>"$d/$jabiz/README"
commit "$d" readme-edit
expect "a README next to the migrations may change" "$d" readme-edit pass ""
git -C "$d" checkout -q base
git -C "$d" reset -q --hard HEAD~1

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
