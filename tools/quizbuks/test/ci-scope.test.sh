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

# expect_tests <true|false> <name> <changed path>...: the same for the argument docs-tests.
expect_tests() {
  local want="$1" name="$2" out
  shift 2
  out="$(printf '%s\n' "$@" | "$script" docs-tests)"
  if [[ "$out" != "$want" ]]; then
    echo "FAIL docs-tests $name: printed '$out' (want '$want')"
    failures=$((failures + 1))
  else
    echo "ok   docs-tests $name"
  fi
}

expect_tests true 'a help page' docs/quizbuks/help/fee-account.md
expect_tests true 'a help page among other documents' docs/ROADMAP.md docs/quizbuks/help/site-profile.md
expect_tests true 'a prompt document' docs/quizbuks/profile-package-prompts.md
expect_tests true 'the other prompt document' docs/quizbuks/quiz-file-prompts.md
expect_tests false 'other documents' docs/quizbuks/operations.md docs/quizbuks/plans/impl/F-C7.md
expect_tests false 'code' backend/quizbuks/src/main/resources/quizbuks/help/manual-app.en.md
expect_tests false 'nothing changed' ''

# Every document a backend test reads (Path.of("../../docs/…")) is one the job docs checks when it changes.
root="$(cd "$(dirname "$0")/../../.." && pwd)"
read_count=0
while IFS= read -r document; do
  read_count=$((read_count + 1))
  expect_tests true "read by a test: $document" "$document"
done < <(grep -rhoE 'Path\.of\("\.\./\.\./docs/[^"]+"' "$root"/backend/*/src/test --include='*.java' | sed -E 's/^Path\.of\("\.\.\/\.\.\///; s/"$//' | sort -u)
if [[ "$read_count" == 0 ]]; then
  echo "FAIL no backend test reads a document: this check is out of date"
  failures=$((failures + 1))
fi

if [[ "$failures" -gt 0 ]]; then
  echo "$failures failure(s)"
  exit 1
fi
