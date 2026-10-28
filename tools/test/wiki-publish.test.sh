#!/usr/bin/env bash
# Tests of tools/wiki/publish.sh (docs/guide/wiki.md): a throwaway remote with version-line branches and a throwaway
# wiki repository; only the newest line publishes, a second run changes nothing, and a rejected push is retried.
set -euo pipefail

script="$(cd "$(dirname "$0")/.." && pwd)/wiki/publish.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0
export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.invalid GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.invalid

# run <exit code> <output fragment> <name> <branch> <kind>: publishes the platform section of the source tree.
run() {
  local want="$1" fragment="$2" name="$3" out code=0
  out="$(cd "$work/src" && WIKI_BRANCH="$4" WIKI_LINE_KIND="$5" WIKI_URL="$work/wiki.git" \
    "$script" --repo o/r --section Platform:docs:design --title 平台 2>&1)" || code=$?
  if [[ "$code" != "$want" || "$out" != *"$fragment"* ]]; then
    echo "FAIL $name: exit $code (want $want), want output containing '$fragment'"
    sed 's/^/    /' <<<"$out"
    failures=$((failures + 1))
  else
    echo "ok   $name"
  fi
}

wiki_log() { git -C "$work/wiki.git" log --oneline | wc -l | tr -d ' '; }
wiki_file() { git -C "$work/wiki.git" show "HEAD:$1"; }

# The source repository, its remote with two lines of the platform and one of an application, and the wiki with the
# first page created by hand.
git init -q --bare "$work/origin.git"
git init -q -b 1.3/platform "$work/src"
mkdir -p "$work/src/docs/design"
printf '# Decisions\n' >"$work/src/docs/design/09-decisions.md"
git -C "$work/src" add -A && git -C "$work/src" commit -q -m src
git -C "$work/src" remote add origin "$work/origin.git"
for b in 1.2/platform 1.3/platform 1.10/quizbuks; do git -C "$work/src" push -q origin "HEAD:refs/heads/$b"; done
git init -q -b master "$work/wiki-init"
printf 'Hand-made\n' >"$work/wiki-init/Home.md"
git -C "$work/wiki-init" add -A && git -C "$work/wiki-init" commit -q -m init
git clone -q --bare "$work/wiki-init" "$work/wiki.git"

run 0 "line 1.2 is not the newest (1.3)" "an older line does not publish" 1.2/platform platform
[ "$(wiki_log)" = 1 ] || { echo "FAIL the older line changed the wiki"; failures=$((failures + 1)); }
run 1 "not a line branch of platform" "a work branch is refused" 1.3/phase-17h-x platform

run 0 "the wiki is updated" "the newest line publishes" 1.3/platform platform
wiki_file Platform/Platform-design-09-decisions.md | grep -q '^# Decisions' \
  || { echo "FAIL the page is missing"; failures=$((failures + 1)); }
wiki_file Home.md | grep -q 'wiki-section:Platform' || { echo "FAIL Home has no section block"; failures=$((failures + 1)); }
run 0 "the wiki is up to date" "an unchanged tree changes nothing" 1.3/platform platform
[ "$(wiki_log)" = 2 ] || { echo "FAIL an unchanged run committed"; failures=$((failures + 1)); }

# A push rejected once (another section pushed first) is redone on a fresh clone.
printf '# Decisions, changed\n' >"$work/src/docs/design/09-decisions.md"
cat >"$work/wiki.git/hooks/pre-receive" <<HOOK
#!/bin/sh
[ -f "$work/rejected" ] && exit 0
touch "$work/rejected"
exit 1
HOOK
chmod +x "$work/wiki.git/hooks/pre-receive"
run 0 "retrying on the newer wiki" "a rejected push is retried" 1.3/platform platform
wiki_file Platform/Platform-design-09-decisions.md | grep -q 'changed' \
  || { echo "FAIL the retried push did not land"; failures=$((failures + 1)); }

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
