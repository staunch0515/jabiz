#!/usr/bin/env bash
# check-migration-order.sh <base> <head> [<sequence>...]: the Flyway migrations (V<n>__*.sql) that <head> adds or
# renames compared with <base> must come after every migration <base> already has in the same sequence, and no version
# may occur twice in a sequence. Flyway refuses a resolved version older than the newest applied one ("Detected
# resolved migration not applied to database"), so a pull request whose migration was numbered before another one
# reached the branch fails at deployment, after the merge (2026-11-08 on 1.3/quizbuks: V80 behind V81; ROADMAP 17zz8).
#
# A sequence is an extended regular expression for directories (anchored at both ends): the migrations of all the
# directories it matches are one sequence, one history table. Without arguments the platform's sequences:
#   backend/runtime/src/main/resources/db/jabiz       the platform's (PlatformSchemaMigration)
#   backend/app/src/main/resources/db/migration       the demo application's
#   backend/app/src/test/resources/db/testmigration   the demo application's test migrations (V1000 and up)
# An application branch gives its own, e.g. 'backend/quizbuks[^/]*/src/main/resources/db/migration'.
#
# <base> is the branch's latest commit (in CI: origin/<base_ref>), not the merge base: what counts is what the branch
# has now. <head> is what would be merged (in CI: the pull request's merge commit, HEAD). A migration counts as the
# pull request's when `git diff <base>...<head>` adds or renames it, so a pull request that has merged the branch in is
# not charged with the branch's own migrations. The branch can still move on after the check ran: look at its highest
# version again before merging (docs/guide/version-lines.md).
#
# Prints every problem with the version to use instead and exits 1; exits 0 when all is well; 2 on bad arguments.
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "usage: $0 <base> <head> [<sequence directory regex>...]" >&2
  exit 2
fi
base=$1
head=$2
shift 2
sequences=("$@")
if [[ ${#sequences[@]} -eq 0 ]]; then
  sequences=(
    'backend/runtime/src/main/resources/db/jabiz'
    'backend/app/src/main/resources/db/migration'
    'backend/app/src/test/resources/db/testmigration'
  )
fi

# The sequence (its index) a path's migration belongs to, or nothing.
sequence_of() {
  local path=$1 i
  [[ $path =~ ^(.*)/V[0-9]+__[^/]*\.sql$ ]] || return 0
  local dir=${BASH_REMATCH[1]}
  for i in "${!sequences[@]}"; do
    if [[ $dir =~ ^(${sequences[$i]})$ ]]; then
      echo "$i"
      return 0
    fi
  done
}

version_of() {
  [[ $1 =~ /V([0-9]+)__[^/]*$ ]] && echo $((10#${BASH_REMATCH[1]}))
}

declare -A max=()
while IFS= read -r path; do
  s=$(sequence_of "$path")
  [[ -z $s ]] && continue
  v=$(version_of "$path")
  ((v > ${max[$s]:-0})) && max[$s]=$v
done < <(git ls-tree -r --name-only "$base")

problems=0
added=0
declare -A next=()
while IFS=$'\t' read -r status first second; do
  path=$first
  [[ $status == R* ]] && path=$second
  s=$(sequence_of "$path")
  [[ -z $s ]] && continue
  added=$((added + 1))
  v=$(version_of "$path")
  m=${max[$s]:-0}
  if ((v <= m)); then
    n=${next[$s]:-$((m + 1))}
    echo "migration-order: $path: V$v is not after the branch's migrations of this sequence. 基线已到 V$m，请改为 V$n" \
      "(the base already has V$m; renumber it V$n)."
    next[$s]=$((n + 1))
    problems=$((problems + 1))
  fi
done < <(git diff --name-status --find-renames --diff-filter=AR "$base...$head")

# No version twice within a sequence in what would be merged.
declare -A seen=()
while IFS= read -r path; do
  s=$(sequence_of "$path")
  [[ -z $s ]] && continue
  v=$(version_of "$path")
  key="$s:$v"
  if [[ -n ${seen[$key]:-} ]]; then
    m=${max[$s]:-0}
    echo "migration-order: V$v occurs twice: ${seen[$key]} and $path. 基线已到 V$m，请把这个 PR 的那一个改为 V$((m + 1)) 或之后的空号" \
      "(renumber this pull request's one after V$m)."
    problems=$((problems + 1))
  else
    seen[$key]=$path
  fi
done < <(git ls-tree -r --name-only "$head")

if ((problems > 0)); then
  exit 1
fi
summary=""
for i in "${!sequences[@]}"; do
  summary+=" ${sequences[$i]}=V${max[$i]:-0}"
done
echo "migration-order: ok ($added new or renamed migration(s); the base has:$summary)"
