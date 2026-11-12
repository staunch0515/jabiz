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
# A migration the base already has (merged) is never edited, deleted or renamed by <head>, nor is a .sql.conf of it
# added, changed or deleted: Flyway
# compares the checksum of every applied script with the file at each start, and a database that ran the old content
# stops at validation ("Migration checksum mismatch"); the same for a migration that disappears. Explain or correct in a
# new migration instead (17zz18: a comment edited in the merged V62). Migrations the pull request adds may be renamed
# and edited freely: the base does not have them.
#
# The one remedy: a merged migration that no production database has run yet (it would fail, say, Flyway's order) may
# be RENAMED, its content unchanged, when <head> lists its path (the base's, before the rename) in an exceptions file
# at the repository root, one per line: '<path> <YYYY-MM-DD> <reason...>' (the date the exception was granted; the
# reason is free text and required; '#' starts a comment). The platform branch keeps its own in
# .jabiz-migration-exceptions, an application branch its own in .jabiz-app-migration-exceptions (listed in its
# .jabiz-app-paths), so that neither edits the other's file. Modifying or deleting a listed migration still fails, and
# so does a malformed line. Better than an exception: leave the merged migration as it is and add a new, idempotent
# one (docs/guide/version-lines.md).
#
# Prints every problem (with the version to use instead, for the order) and exits 1; exits 0 when all is well; 2 on bad
# arguments.
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

# The sequence of a migration's own file: the script itself or its .sql.conf (Flyway's per-script settings).
sequence_of_file() {
  sequence_of "${1%.conf}"
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

# The merged migrations <head> may rename (see above), from both exception files.
declare -A excepted=()
for file in .jabiz-migration-exceptions .jabiz-app-migration-exceptions; do
  git cat-file -e "$head:$file" 2>/dev/null || continue
  n=0
  while IFS= read -r line || [[ -n $line ]]; do
    n=$((n + 1))
    line=${line%%#*}
    [[ -z ${line//[[:space:]]/} ]] && continue
    if [[ $line =~ ^[[:space:]]*([^[:space:]]+)[[:space:]]+([0-9]{4}-[0-9]{2}-[0-9]{2})[[:space:]]+([^[:space:]].*)$ ]]; then
      excepted[${BASH_REMATCH[1]}]="$file:$n"
    else
      echo "migration-order: $file line $n is not '<path> <YYYY-MM-DD> <reason>': $line"
      problems=$((problems + 1))
    fi
  done < <(git show "$head:$file")
done

# Merged migrations stay as they are: whatever <head> modifies, deletes or renames away that the base has.
while IFS=$'\t' read -r status first second; do
  [[ -z $(sequence_of_file "$first") ]] && continue
  # A .sql.conf belongs to its script: adding, changing or deleting it changes how a merged script runs.
  git cat-file -e "$base:${first%.conf}" 2>/dev/null || continue
  if [[ -n ${excepted[${first%.conf}]:-} ]]; then
    # Listed (a .sql.conf with its script): renamed with the content unchanged (git's similarity 100) is the one
    # change allowed.
    [[ $status == R100 ]] && continue
    echo "migration-order: $first: listed in ${excepted[${first%.conf}]}, which allows renaming it with its content unchanged," \
      "nothing else (status $status). 例外只允许原样改名。"
    problems=$((problems + 1))
    continue
  fi
  case $status in
    A*) what="adds settings ($first) to" ;;
    D*) what="deletes" ;;
    R*) what="renames (to $second)" ;;
    *) what="modifies" ;;
  esac
  echo "migration-order: $first: the pull request $what a migration the base already has." \
    "已合并的迁移不能修改、删除或改名（数据库按校验和核对已执行的脚本），请恢复原样，说明或更正写进新的迁移" \
    "(restore it; put the change in a new migration)."
  problems=$((problems + 1))
done < <(git diff --name-status --find-renames --diff-filter=AMDRT "$base...$head")

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
echo "migration-order: ok ($added new or renamed migration(s), no merged one changed; the base has:$summary)"
