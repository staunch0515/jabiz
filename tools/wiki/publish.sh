#!/usr/bin/env bash
# Publishes one section of the GitHub wiki from the checked-out branch (docs/guide/wiki.md), for the workflows.
#
#   WIKI_BRANCH=<branch> WIKI_LINE_KIND=platform|<application> [WIKI_URL=<wiki repository>] [WIKI_REMOTE=origin] \
#     tools/wiki/publish.sh <arguments of tools/wiki/sync.mjs other than --wiki and --branch>
#
# Only the newest version line publishes: a branch "<major>.<minor>/<kind>" whose line is older than another branch
# of the same kind on the remote stops here, so a fix pushed to an older line never replaces the newer documents.
# The wiki has one history that two sections push to: a push that loses the race clones again and redoes its
# section on top of the other one (three attempts).
set -euo pipefail

: "${WIKI_BRANCH:?}" "${WIKI_LINE_KIND:?}"
remote="${WIKI_REMOTE:-origin}"
here="$(cd "$(dirname "$0")" && pwd)"

if [[ $WIKI_BRANCH =~ ^([0-9]+\.[0-9]+)/$WIKI_LINE_KIND$ ]]; then
  newest="$(git ls-remote --heads "$remote" | sed -n "s|.*refs/heads/\([0-9][0-9]*\.[0-9][0-9]*\)/$WIKI_LINE_KIND\$|\1|p" | sort -V | tail -1)"
  if [ -n "$newest" ] && [ "$newest" != "${BASH_REMATCH[1]}" ]; then
    echo "line ${BASH_REMATCH[1]} is not the newest ($newest): the wiki is not changed"
    exit 0
  fi
else
  echo "not a line branch of $WIKI_LINE_KIND: $WIKI_BRANCH" >&2
  exit 1
fi

url="${WIKI_URL:-}"
if [ -z "$url" ]; then
  : "${WIKI_TOKEN:?}" "${GITHUB_REPOSITORY:?}"
  url="https://x-access-token:${WIKI_TOKEN}@github.com/${GITHUB_REPOSITORY}.wiki.git"
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
for attempt in 1 2 3; do
  rm -rf "$work/wiki"
  if ! git clone --quiet --depth 1 "$url" "$work/wiki"; then
    echo "::error::cannot clone the wiki: Wikis must be on (Settings → General → Features) and its first page created" >&2
    exit 1
  fi
  node "$here/sync.mjs" --wiki "$work/wiki" --branch "$WIKI_BRANCH" "$@"
  git -C "$work/wiki" add -A
  if git -C "$work/wiki" diff --cached --quiet; then
    echo "the wiki is up to date"
    exit 0
  fi
  git -C "$work/wiki" -c user.name='github-actions[bot]' -c user.email='41898282+github-actions[bot]@users.noreply.github.com' \
    commit --quiet -m "Sync from $WIKI_BRANCH${GITHUB_SHA:+ ($GITHUB_SHA)}"
  if git -C "$work/wiki" push --quiet origin HEAD; then
    echo "the wiki is updated"
    exit 0
  fi
  echo "push failed (attempt $attempt), retrying on the newer wiki" >&2
  sleep $((attempt * 3))
done
echo "::error::the wiki could not be pushed (a token without write access to the wiki fails every attempt)" >&2
exit 1
