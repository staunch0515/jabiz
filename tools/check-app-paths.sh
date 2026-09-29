#!/usr/bin/env bash
# Checks that an application branch changes only its own paths (docs/design/17-apps-and-branches.md section 2,
# decisions D19 and D21). The paths it owns are globs listed in .jabiz-app-paths at the repository root, one per line
# ('#' starts a comment; '**' matches across directories, '*' and '?' within one path segment).
#
# Every file changed since the common ancestor with the platform branch must match one of them; changes that came
# in by merging the platform branch are not counted. A pattern may not claim a file of the platform branch, and
# nothing under the platform's own directories (see 'reserved') counts as the application's.
#
# The platform branch is the one of the line in .jabiz-platform-line (D21): line 1.1 -> origin/1.1/platform. The file
# comes from the platform, so an application is on the line whose platform it last merged. A branch named after a
# line ('1.1/...') must be on that line, which catches an application branch opened for a line without merging its
# platform, and a platform line whose file was not moved on. This check runs on every branch.
#
# Usage: tools/check-app-paths.sh [base]
#   base: the platform branch; default $APP_PATHS_BASE, else origin/<line>/platform, else origin/platform (no line
#   file: a branch from before version lines). $APP_PATHS_BRANCH names the branch being checked where HEAD is
#   detached (CI); default the checked-out branch.
# Exit:  0 passes (or not an application branch), 1 violations, 2 cannot check.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
list="$root/.jabiz-app-paths"
line_file="$root/.jabiz-platform-line"

line=""
if [[ -f "$line_file" ]]; then
  line="$(tr -d '[:space:]' <"$line_file")"
  if [[ ! "$line" =~ ^[0-9]+\.[0-9]+$ ]]; then
    echo "check-app-paths: .jabiz-platform-line must hold a line such as 1.0, not '$line'" >&2
    exit 2
  fi
fi

branch="${APP_PATHS_BRANCH:-$(git -C "$root" symbolic-ref --quiet --short HEAD || true)}"
if [[ "$branch" =~ ^([0-9]+\.[0-9]+)/ ]]; then
  named="${BASH_REMATCH[1]}"
  if [[ "$named" != "$line" ]]; then
    echo "check-app-paths: branch '$branch' belongs to line $named but .jabiz-platform-line says '${line:-none}'" \
      "(merge $named/platform into it, or on a new platform line write $named into the file)" >&2
    exit 1
  fi
fi

if [[ -n "$line" ]]; then default_base="origin/$line/platform"; else default_base="origin/platform"; fi
base="${1:-${APP_PATHS_BASE:-$default_base}}"

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

# Directories that belong to the platform as a whole (17 section 1): nothing in them, new files included, can be an
# application's, whatever .jabiz-app-paths says.
reserved=(backend/core/ backend/runtime/ backend/ext-geo/ backend/app/ backend/build-logic/ frontend/ spec/
  docs/design/ docs/guide/)
reserved_dir() {
  local path="$1" dir
  for dir in "${reserved[@]}"; do
    [[ "$path" == "$dir"* ]] && return 0
  done
  return 1
}

# The application may not claim what the platform has.
# NUL-separated: git would otherwise quote and escape non-ASCII names ("\346\226\207.md").
platform_files="$(git -C "$root" ls-tree -r -z --name-only "$merge_base" | tr '\0' '\n')"
for i in ${patterns[@]+"${!patterns[@]}"}; do
  claimed="$(grep -E -m 3 -- "${regexes[i]}" <<<"$platform_files" || true)"
  if [[ -n "$claimed" ]]; then
    echo "check-app-paths: pattern '${patterns[i]}' claims files of the platform branch, e.g.:" >&2
    sed 's/^/  /' <<<"$claimed" >&2
    status=1
  fi
done

violations=()
while IFS= read -r -d '' path; do
  if reserved_dir "$path" || ! owned "$path"; then violations+=("$path"); fi
done < <(git -C "$root" diff -z --name-only --no-renames "$merge_base" HEAD)

if ((${#violations[@]} > 0)); then
  echo "check-app-paths: ${#violations[@]} changed path(s) outside .jabiz-app-paths (make platform changes on the" \
    "platform branch of this line first, then merge it into this branch):" >&2
  printf '  %s\n' "${violations[@]}" >&2
  status=1
fi

if ((status == 0)); then
  echo "check-app-paths: every change since '$base' is within .jabiz-app-paths"
fi
exit "$status"
