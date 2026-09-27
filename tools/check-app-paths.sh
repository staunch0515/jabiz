#!/usr/bin/env bash
# Checks that an application branch changes only its own paths (docs/design/17-apps-and-branches.md section 2,
# decision D19). The paths it owns are globs listed in .jabiz-app-paths at the repository root, one per line
# ('#' starts a comment; '**' matches across directories, '*' and '?' within one path segment).
#
# Every file changed since the common ancestor with the platform branch must match one of them; changes that came
# in by merging the platform branch are not counted. A pattern may not claim a file of the platform branch.
#
# Usage: tools/check-app-paths.sh [base]    base: the platform branch, default $APP_PATHS_BASE or origin/platform
# Exit:  0 passes (or not an application branch), 1 violations, 2 cannot check.
set -euo pipefail

base="${1:-${APP_PATHS_BASE:-origin/platform}}"
root="$(git rev-parse --show-toplevel)"
list="$root/.jabiz-app-paths"

if [[ ! -f "$list" ]]; then
  echo "check-app-paths: no .jabiz-app-paths, not an application branch; nothing to check"
  exit 0
fi
if ! git -C "$root" rev-parse --verify --quiet "$base^{commit}" >/dev/null; then
  echo "check-app-paths: base '$base' not found (fetch the platform branch with its history)" >&2
  exit 2
fi
if ! merge_base="$(git -C "$root" merge-base "$base" HEAD)"; then
  echo "check-app-paths: HEAD has no common ancestor with '$base'" >&2
  exit 2
fi

# A glob as an anchored extended regular expression.
glob_to_regex() {
  local glob="$1" regex="" i c
  for ((i = 0; i < ${#glob}; i++)); do
    c="${glob:i:1}"
    if [[ "$c" == "*" && "${glob:i+1:1}" == "*" ]]; then
      if [[ "${glob:i+2:1}" == "/" ]]; then regex+="(.*/)?"; i=$((i + 2)); else regex+=".*"; i=$((i + 1)); fi
    elif [[ "$c" == "*" ]]; then regex+="[^/]*"
    elif [[ "$c" == "?" ]]; then regex+="[^/]"
    elif [[ "$c" =~ [].[\\^\$+\(\){}\|] ]]; then regex+="\\$c"
    else regex+="$c"
    fi
  done
  printf '^%s$' "$regex"
}

patterns=()
regexes=()
while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%%#*}"
  line="${line#"${line%%[![:space:]]*}"}"
  line="${line%"${line##*[![:space:]]}"}"
  [[ -z "$line" ]] && continue
  line="${line#/}"
  patterns+=("$line")
  regexes+=("$(glob_to_regex "$line")")
done <"$list"

owned() {
  local path="$1" regex
  [[ "$path" == ".jabiz-app-paths" ]] && return 0
  for regex in ${regexes[@]+"${regexes[@]}"}; do
    [[ "$path" =~ $regex ]] && return 0
  done
  return 1
}

status=0

# The application may not claim what the platform has.
platform_files="$(git -C "$root" ls-tree -r --name-only "$merge_base")"
for i in ${patterns[@]+"${!patterns[@]}"}; do
  claimed="$(grep -E -m 3 -- "${regexes[i]}" <<<"$platform_files" || true)"
  if [[ -n "$claimed" ]]; then
    echo "check-app-paths: pattern '${patterns[i]}' claims files of the platform branch, e.g.:" >&2
    sed 's/^/  /' <<<"$claimed" >&2
    status=1
  fi
done

violations=()
while IFS= read -r path; do
  [[ -z "$path" ]] && continue
  owned "$path" || violations+=("$path")
done < <(git -C "$root" diff --name-only --no-renames "$merge_base" HEAD)

if ((${#violations[@]} > 0)); then
  echo "check-app-paths: ${#violations[@]} changed path(s) outside .jabiz-app-paths (make platform changes on the" \
    "platform branch first, then merge it into this branch):" >&2
  printf '  %s\n' "${violations[@]}" >&2
  status=1
fi

if ((status == 0)); then
  echo "check-app-paths: every change since '$base' is within .jabiz-app-paths"
fi
exit "$status"
