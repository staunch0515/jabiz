#!/usr/bin/env bash
# Whether a pull request needs the backend's check (the jobs build and build-application of .github/workflows/ci.yml;
# docs/design/17-apps-and-branches.md section 2, ROADMAP 17zg). Reads the changed paths, one per line, on stdin and
# prints "true" or "false".
#
# The check is skipped only when every changed path matches a pattern that cannot change its outcome: the platform's
# own (docs/**) and those a branch lists in the optional file .jabiz-ci-no-backend at the repository's root (one pattern
# per line, written as in .jabiz-app-paths: '#' starts a comment, '**' crosses folders, '*' and '?' stay within one
# segment, so 'web/site/src/**' covers the whole folder). Anything else, or an empty list, means "true": when in doubt,
# the check runs.
set -euo pipefail

patterns=('docs/**')
if [[ -f .jabiz-ci-no-backend ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -n "$line" ]] && patterns+=("$line")
  done <.jabiz-ci-no-backend
fi

# A pattern as an anchored regular expression: '**' any characters, '*' and '?' none of them '/'.
regex() {
  local pattern="$1" out='' c i
  for ((i = 0; i < ${#pattern}; i++)); do
    c="${pattern:i:1}"
    if [[ "$c" == '*' && "${pattern:i+1:1}" == '*' ]]; then out+='.*'; i=$((i + 1))
    elif [[ "$c" == '*' ]]; then out+='[^/]*'
    elif [[ "$c" == '?' ]]; then out+='[^/]'
    elif [[ "$c" =~ [A-Za-z0-9_/-] ]]; then out+="$c"
    else out+="\\$c"
    fi
  done
  printf '^%s$' "$out"
}
regexes=()
for pattern in "${patterns[@]}"; do regexes+=("$(regex "$pattern")"); done

seen=0
while IFS= read -r path || [[ -n "$path" ]]; do
  [[ -z "$path" ]] && continue
  seen=1
  matched=0
  for re in "${regexes[@]}"; do
    if [[ "$path" =~ $re ]]; then matched=1; break; fi
  done
  if [[ "$matched" == 0 ]]; then
    echo true
    exit 0
  fi
done

if [[ "$seen" == 0 ]]; then echo true; else echo false; fi
