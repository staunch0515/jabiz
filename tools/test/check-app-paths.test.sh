#!/usr/bin/env bash
# Tests of tools/check-app-paths.sh (docs/design/17-apps-and-branches.md section 5, decision D21): application branches are built
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
expect() { check "$1" "$2" "$3" platform; }

# expect_line <exit code> <output fragment or ''> <name>: runs the script with its default base (version lines).
expect_line() { check "$1" "$2" "$3"; }

check() {
  local want="$1" fragment="$2" name="$3" out code=0
  shift 3
  out="$(cd "$work/repo" && "$script" "$@" 2>&1)" || code=$?
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

app_branch claims-new-platform-file
printf 'backend/runtime/src/Extra.java\n' >>"$work/repo/.jabiz-app-paths"
write backend/runtime/src/Extra.java
commit "a new file in a platform module"
expect 1 "backend/runtime/src/Extra.java" "a new file in a platform directory fails even when listed"

git_ checkout -q only-app
out_code=0
(cd "$work/repo" && "$script" no-such-branch >/dev/null 2>&1) || out_code=$?
if [[ "$out_code" == 2 ]]; then echo "ok   a missing base cannot be checked (exit 2)"; else
  echo "FAIL a missing base: exit $out_code (want 2)"; failures=$((failures + 1)); fi

# Version lines (D21). The repository is its own 'origin', so that origin/<line>/platform exists after a fetch.
git_ remote add origin "$work/repo"
fetch() { git_ fetch -q origin; }

git_ checkout -q -b 1.0/platform platform
write .jabiz-platform-line 1.0
commit "platform line 1.0"
fetch

git_ checkout -q -b 1.0/culture 1.0/platform
cat >"$work/repo/.jabiz-app-paths" <<'PATHS'
backend/culture/**
docs/culture/**
PATHS
write backend/culture/build.gradle.kts
commit "culture on 1.0"
expect_line 0 "since 'origin/1.0/platform'" "the base is the platform branch of the line in .jabiz-platform-line"

git_ checkout -q -b 1.0/culture-2-work 1.0/culture
write docs/culture/notes.md
commit "a work branch of the line"
expect_line 0 "within .jabiz-app-paths" "a work branch named after its line passes"

# Line 1.1 of the platform, with an incompatible change.
git_ checkout -q -b 1.1/platform 1.0/platform
write .jabiz-platform-line 1.1
write backend/core/Core.java "1.1 changed the core"
commit "platform line 1.1"
fetch

git_ checkout -q -b 1.0/culture-edits-line 1.0/culture
write .jabiz-platform-line 1.1
commit "an application moves its own line instead of merging"
APP_PATHS_BRANCH=culture-edits-line expect_line 1 ".jabiz-platform-line" \
  "an application that changes .jabiz-platform-line itself fails"

# An application branch opened for 1.1 without merging 1.1/platform is still on 1.0.
git_ checkout -q -b 1.1/culture 1.0/culture
expect_line 1 "belongs to line 1.1" "a branch named after a line it has not merged fails"

git_ merge -q --no-edit 1.1/platform
write backend/culture/Upgraded.java
commit "culture adapted to 1.1"
expect_line 0 "since 'origin/1.1/platform'" "an application upgraded to a new line is checked against that line"

# A fix on the older line, merged forward into the newer line and from there into the application.
git_ checkout -q 1.0/platform
write backend/runtime/Fix.java "fixed on 1.0"
commit "fix on 1.0"
git_ checkout -q 1.1/platform
git_ merge -q --no-edit 1.0/platform
fetch
git_ checkout -q 1.1/culture
git_ merge -q --no-edit 1.1/platform
expect_line 0 "within .jabiz-app-paths" "a fix merged forward through the lines is not counted"

git_ checkout -q -b 1.1/phase-14a-lagging 1.0/platform
expect_line 1 "belongs to line 1.1" "a platform work branch of a new line needs the new line in the file"

git_ checkout -q -b 1.1/culture-bad-line 1.1/culture
write .jabiz-platform-line "one point one"
commit "a malformed line"
expect_line 2 "must hold a line" "a malformed .jabiz-platform-line cannot be checked (exit 2)"

git_ checkout -q -b 1.2/platform 1.1/platform
write .jabiz-platform-line 1.2
commit "platform line 1.2, not pushed"
git_ checkout -q -b 1.2/culture 1.1/culture
git_ merge -q --no-edit 1.2/platform
expect_line 2 "origin/1.2/platform' not found" "a line whose platform branch was not fetched cannot be checked (exit 2)"

git_ checkout -q 1.1/culture
APP_PATHS_BRANCH=1.0/culture expect_line 1 "belongs to line 1.0" "APP_PATHS_BRANCH names the branch where HEAD is detached"
git_ checkout -q --detach 1.1/culture
expect_line 0 "within .jabiz-app-paths" "a detached HEAD without APP_PATHS_BRANCH is checked by its line alone"

git_ checkout -q only-app
expect_line 0 "since 'origin/platform'" "a branch from before version lines (no line file) is checked against origin/platform"

if ((failures > 0)); then
  echo "$failures test(s) failed"
  exit 1
fi
echo "all tests passed"
