#!/usr/bin/env bash
# Tests of tools/quizbuks/ci/scope.sh: which pull requests may skip the full check of quizbuks.yml.
set -euo pipefail

script="$(cd "$(dirname "$0")/.." && pwd)/ci/scope.sh"
failures=0

# expect <true|false> <name> <changed path>...: runs the script on those paths.
expect() {
  local want="$1" name="$2" out
  shift 2
  out="$(printf '%s\n' "$@" | "$script")"
  if [[ "$out" != "$want" ]]; then
    echo "FAIL $name: printed '$out' (want '$want')"
    failures=$((failures + 1))
  else
    echo "ok   $name"
  fi
}

expect false 'one document' docs/quizbuks/first-launch-progress.md
expect false 'documents, nested and not' docs/ROADMAP.md docs/quizbuks/plans/impl/F-F11.md docs/quizbuks/store/play-listing.en.md
expect true 'a document and code' docs/quizbuks/a.md quizbuks-web/sponsor/src/App.tsx
expect true 'a document and the workflow' docs/quizbuks/a.md .github/workflows/quizbuks.yml
expect true 'the workflow alone' .github/workflows/quizbuks.yml
expect true 'the script itself' tools/quizbuks/ci/scope.sh
expect true 'the root CLAUDE.md is not under docs/' CLAUDE.md
expect true 'a help page inside the backend is code' backend/quizbuks/src/main/resources/quizbuks/help/manual-sponsor.en.md
expect true 'docs/ later in the path is not docs/' backend/quizbuks/docs/a.md
expect true 'a folder that only starts like docs' docsx/a.md
expect true 'nothing changed' ''
expect true 'a blank line only' ' '

if [[ "$failures" -gt 0 ]]; then
  echo "$failures failure(s)"
  exit 1
fi
