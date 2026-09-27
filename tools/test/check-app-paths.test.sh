#!/usr/bin/env bash
# Tests of tools/check-app-paths.sh (docs/design/17-apps-and-branches.md section 5): application branches are built
# in a throwaway repository and the script must pass the ones that only change their own paths and fail the others.
set -euo pipefail

script="$(cd "$(dirname "$0")/.." && pwd)/check-app-paths.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

git_() { git -C "$work/repo" -c user.name=test -c user.email=test@example.invalid "$@"; }
write() { mkdir -p "$(dirname "$work/repo/$1")"; printf '%s\n' "${2:-$1}" >"$work/repo/$1"; }
commit() { git_ add -A; git_ commit -q --allow-empty -m "$1"; }

# expect <exit code> <output fragment or ''> <name>: runs the script on the current branch against 'platform'.
expect() {
  local want="$1" fragment="$2" name="$3" out code=0
  out="$(cd "$work/repo" && "$script" platform 2>&1)" || code=$?
  if [[ "$code" != "$want" ]] || [[ -n "$fragment" && "$out" != *"$fragment"* ]]; then
    echo "FAIL $name: exit $code (want $want)${fragment:+, want output containing '$fragment'}"
    sed 's/^/    /' <<<"$out"
    failures=$((failures + 1))
  else
    echo "ok   $name"
  fi
}

# The platform branch.
git init -q -b platform "$work/repo"
write backend/core/Core.java
write backend/app/App.java
write docs/design/00-overview.md
write frontend/src/main.tsx
commit "platform"

# An application branch that owns backend/culture, site, docs/culture and its workflow file.
app_branch() {
  git_ checkout -q -B "$1" platform
  cat >"$work/repo/.jabiz-app-paths" <<'PATHS'
# culture's own paths
backend/culture/**
site/**

docs/culture/*.md   # one level only
.github/workflows/culture.yml
PATHS
  write backend/culture/build.gradle.kts
  write backend/culture/src/main/java/com/jabiz/culture/CultureApp.java
  write site/src/pages/Home.tsx
  write docs/culture/README.md
  write .github/workflows/culture.yml
  commit "$1: own paths"
}

git_ checkout -q platform
expect 0 "not an application branch" "the platform branch itself is not checked"

app_branch only-app
expect 0 "within .jabiz-app-paths" "an application branch that changes only its own paths passes"

app_branch unicode-names
write "docs/culture/文化.md"
write "site/src/pages/作品 一覧.tsx"
commit "non-ASCII names"
expect 0 "within .jabiz-app-paths" "non-ASCII and spaced file names in application paths pass"

app_branch unicode-platform
write "docs/design/設計.md"
commit "a non-ASCII platform file"
expect 1 "docs/design/設計.md" "a non-ASCII platform file is named as it is"

app_branch edits-core
write backend/core/Core.java "changed"
commit "touch the platform"
expect 1 "backend/core/Core.java" "changing a platform file fails and names it"

app_branch deletes-doc
git_ rm -q docs/design/00-overview.md
commit "delete a platform file"
expect 1 "docs/design/00-overview.md" "deleting a platform file fails"

app_branch moves-frontend
git_ mv frontend/src/main.tsx site/src/main.tsx
commit "move a platform file into the app"
expect 1 "frontend/src/main.tsx" "moving a platform file into an application path fails"

app_branch nested-doc
write docs/culture/deep/notes.md
commit "a nested doc"
expect 1 "docs/culture/deep/notes.md" "'*' does not cross directories"

app_branch merges-platform
git_ checkout -q platform
write backend/core/Core.java "platform moved on"
write backend/runtime/New.java
commit "platform: new work"
git_ checkout -q merges-platform
git_ merge -q --no-edit platform
write site/src/pages/About.tsx
commit "more app work"
expect 0 "within .jabiz-app-paths" "changes merged from the platform branch are not counted"

app_branch claims-platform
printf 'backend/**\n' >>"$work/repo/.jabiz-app-paths"
commit "claim the backend"
expect 1 "claims files of the platform branch" "a pattern that claims platform files fails"

git_ checkout -q only-app
out_code=0
(cd "$work/repo" && "$script" no-such-branch >/dev/null 2>&1) || out_code=$?
if [[ "$out_code" == 2 ]]; then echo "ok   a missing base cannot be checked (exit 2)"; else
  echo "FAIL a missing base: exit $out_code (want 2)"; failures=$((failures + 1)); fi

if ((failures > 0)); then
  echo "$failures test(s) failed"
  exit 1
fi
echo "all tests passed"
