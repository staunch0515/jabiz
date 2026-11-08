#!/usr/bin/env bash
# migration-order.sh <base> <head>: QuizBuks's Flyway migrations (db/migration of every backend/quizbuks* module; one
# sequence, one history table) that <head> adds or renames compared with <base> must come after every migration <base>
# already has, and no version may occur twice. Flyway refuses a resolved version older than the newest applied one
# ("Detected resolved migration not applied to database"), so a pull request whose migration was numbered before
# another one reached the line branch fails at its deployment, after the merge (2026-11-08: V80 behind V81).
#
# <base> is the line branch's latest commit (in CI: origin/<base_ref>), not the merge base: what counts is what the
# branch has now. <head> is what would be merged (in CI: the pull request's merge commit, HEAD). The migrations
# <head> adds are those of `git diff <base>...<head>` (added or renamed), so a pull request that has merged the line
# branch in is not charged with the branch's own migrations.
#
# Prints every problem with the version to use instead and exits 1; exits 0 when all is well.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <base> <head>" >&2
  exit 2
fi
base=$1
head=$2

# The QuizBuks modules' migration directories (backend/quizbuks, backend/quizbuks-shopify, …): one sequence.
is_quizbuks_migration() {
  [[ $1 =~ ^backend/quizbuks[^/]*/src/main/resources/db/migration/V([0-9]+)__[^/]*\.sql$ ]]
}

version_of() {
  [[ $1 =~ /V([0-9]+)__ ]] && echo $((10#${BASH_REMATCH[1]}))
}

max=0
while IFS= read -r path; do
  if is_quizbuks_migration "$path"; then
    v=$(version_of "$path")
    ((v > max)) && max=$v
  fi
done < <(git ls-tree -r --name-only "$base" -- backend)

added=()
while IFS=$'\t' read -r status first second; do
  path=$first
  [[ $status == R* ]] && path=$second
  if is_quizbuks_migration "$path"; then
    added+=("$path")
  fi
done < <(git diff --name-status --find-renames --diff-filter=AR "$base...$head" -- backend)

problems=0
next=$((max + 1))
for path in "${added[@]}"; do
  v=$(version_of "$path")
  if ((v <= max)); then
    echo "migration-order: $path: V$v is not after the line branch's migrations. 基线已到 V$max，请改为 V$next" \
      "(the base already has V$max; renumber it V$next)."
    problems=$((problems + 1))
    next=$((next + 1))
  fi
done

# No version twice among the QuizBuks modules in what would be merged.
declare -A seen=()
while IFS= read -r path; do
  if is_quizbuks_migration "$path"; then
    v=$(version_of "$path")
    if [[ -n ${seen[$v]:-} ]]; then
      echo "migration-order: V$v occurs twice: ${seen[$v]} and $path. 基线已到 V$max，请把这个 PR 的那一个改为 V$((max + 1)) 或之后的空号" \
        "(renumber this pull request's one after V$max)."
      problems=$((problems + 1))
    else
      seen[$v]=$path
    fi
  fi
done < <(git ls-tree -r --name-only "$head" -- backend)

if ((problems > 0)); then
  exit 1
fi
echo "migration-order: ok (the base has up to V$max; ${#added[@]} new or renamed migration(s) after it)"
