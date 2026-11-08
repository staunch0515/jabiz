#!/usr/bin/env bash
# Tests of tools/quizbuks/migration-order.sh against throwaway repositories, offline: the cases of 2026-11-08 (base
# V81 and a pull request with V80; base V83 and V82; V84), a rename, two modules sharing the sequence, a version twice,
# the line branch moving on after the pull request branched (merged in or not), and other directories left alone.
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
script="$root/tools/quizbuks/migration-order.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

fail() {
  echo "FAIL $1"
  failures=$((failures + 1))
}

qb=backend/quizbuks/src/main/resources/db/migration
shop=backend/quizbuks-shopify/src/main/resources/db/migration
app=backend/app/src/main/resources/db/migration

# A repository whose line branch "base" has the given migrations (paths), one commit.
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
  git -C "$dir" add -A
  git -C "$dir" commit -qm base
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

# expect <name> <dir> <base> <head> pass|fail [text the output must contain]
expect() {
  local name=$1 dir=$2 base=$3 head=$4 outcome=$5 text=${6:-}
  local out status=0
  out=$(cd "$dir" && "$script" "$base" "$head" 2>&1) || status=$?
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

# 1. Base V81, the pull request adds V80 (the F-S11 merge of 2026-11-08).
d=$(new_repo one "$qb/V79__usage.sql" "$qb/V81__digital_code_fulfiller.sql")
git -C "$d" checkout -qb pr
add "$d" "$qb/V80__foreign_videos.sql"
commit "$d" "V80"
expect "base V81, pull request V80" "$d" base pr fail "基线已到 V81，请改为 V82"

# 2. Base V83, the pull request adds V82 (the sync's neighbour, #517).
d=$(new_repo two "$qb/V81__a.sql" "$qb/V83__foreign_videos.sql")
git -C "$d" checkout -qb pr
add "$d" "$qb/V82__x.sql"
commit "$d" "V82"
expect "base V83, pull request V82" "$d" base pr fail "基线已到 V83，请改为 V84"

# 3. Base V83, the pull request adds V84.
git -C "$d" checkout -q base
git -C "$d" checkout -qb pr84
add "$d" "$qb/V84__x.sql"
commit "$d" "V84"
expect "base V83, pull request V84" "$d" base pr84 pass "ok"

# 4. The fix of 2026-11-08: base V81 (and V80 merged too early), the pull request renames V80 to V83.
d=$(new_repo four "$qb/V80__foreign_videos.sql" "$qb/V81__a.sql")
git -C "$d" checkout -qb pr
git -C "$d" mv "$qb/V80__foreign_videos.sql" "$qb/V83__foreign_videos.sql"
commit "$d" "rename"
expect "rename V80 to V83 after V81" "$d" base pr pass

# 5. The Shopify module shares the sequence: base has V35 there, V34 in QuizBuks is behind it.
d=$(new_repo five "$qb/V33__a.sql" "$shop/V35__billing.sql")
git -C "$d" checkout -qb pr
add "$d" "$qb/V34__b.sql"
commit "$d" "V34"
expect "behind the other module's V35" "$d" base pr fail "基线已到 V35，请改为 V36"

# 6. The same new version in both modules.
git -C "$d" checkout -q base
git -C "$d" checkout -qb twice
add "$d" "$qb/V36__a.sql"
add "$d" "$shop/V36__b.sql"
commit "$d" "V36 twice"
expect "V36 twice in one pull request" "$d" base twice fail "V36 occurs twice"

# 7. Two new migrations in order: the messages number them one after another when both are behind.
git -C "$d" checkout -q base
git -C "$d" checkout -qb two-new
add "$d" "$qb/V36__a.sql"
add "$d" "$qb/V37__b.sql"
commit "$d" "V36 V37"
expect "two new migrations after the base" "$d" base two-new pass
git -C "$d" checkout -q base
git -C "$d" checkout -qb two-old
add "$d" "$qb/V30__a.sql"
add "$d" "$qb/V31__b.sql"
commit "$d" "V30 V31"
expect "two behind: first suggestion V36" "$d" base two-old fail "请改为 V36"
expect "two behind: second suggestion V37" "$d" base two-old fail "请改为 V37"

# 8. The line branch moves on after the pull request branched: the base's latest commit counts, not the merge base.
d=$(new_repo eight "$qb/V81__a.sql")
git -C "$d" checkout -qb pr
add "$d" "$qb/V82__mine.sql"
commit "$d" "V82"
git -C "$d" checkout -q base
add "$d" "$qb/V83__theirs.sql"
commit "$d" "V83 on the line branch"
expect "line branch moved on to V83, pull request still V82" "$d" base pr fail "基线已到 V83，请改为 V84"
# Merged in and renumbered: the line branch's own V83 is not the pull request's.
git -C "$d" checkout -q pr
git -C "$d" merge -q --no-edit base
git -C "$d" mv "$qb/V82__mine.sql" "$qb/V84__mine.sql"
commit "$d" "renumber"
expect "line branch merged in, renumbered V84" "$d" base pr pass

# 9. Other directories are another sequence: the demo application's and the platform's migrations are left alone.
d=$(new_repo nine "$qb/V81__a.sql" "$app/V19__x.sql")
git -C "$d" checkout -qb pr
add "$d" "$app/V20__y.sql"
add "$d" "backend/runtime/src/main/resources/db/jabiz/V66__z.sql"
commit "$d" "other sequences"
expect "the demo application's and the platform's migrations" "$d" base pr pass

# 10. Nothing about migrations: passes.
git -C "$d" checkout -q base
git -C "$d" checkout -qb docs
echo changed >"$d/README"
commit "$d" "docs"
expect "no migrations" "$d" base docs pass

if ((failures > 0)); then
  echo "$failures failure(s)"
  exit 1
fi
echo "all migration-order tests passed"
