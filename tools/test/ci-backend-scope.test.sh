#!/usr/bin/env bash
# Tests of tools/ci-backend-scope.sh (ROADMAP 17zg): which pull requests may skip the backend's check.
set -euo pipefail

script="$(cd "$(dirname "$0")/.." && pwd)/ci-backend-scope.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

# expect <true|false> <name> <changed path>...: runs the script in $work on those paths.
expect() {
  local want="$1" name="$2" out
  shift 2
  out="$(cd "$work" && printf '%s\n' "$@" | "$script")"
  if [[ "$out" != "$want" ]]; then
    echo "FAIL $name: printed '$out' (want '$want')"
    failures=$((failures + 1))
  else
    echo "ok   $name"
  fi
}

expect false 'documents only' docs/ROADMAP.md docs/design/10-security.md
expect true 'a document and code' docs/ROADMAP.md backend/runtime/src/main/java/A.java
expect true 'the root CLAUDE.md is not under docs/' CLAUDE.md
expect true 'the workflow itself' .github/workflows/ci.yml
expect true 'nothing changed' ''

cat >"$work/.jabiz-ci-no-backend" <<'EOF'
# The business site's texts and pages (not its package.json: the SPA version check reads it).
quizbuks-web/biz/src/**   # nested folders too
quizbuks-web/biz/test/*.ts

EOF
expect false 'listed folders, nested' quizbuks-web/biz/src/content/zh.mjs quizbuks-web/biz/src/templates/page.mjs
expect false 'listed and documents' quizbuks-web/biz/test/content.test.ts docs/quizbuks/plans/Q95.md
expect true 'a file beside the listed folders' quizbuks-web/biz/package.json
expect true 'listed and the backend' quizbuks-web/biz/src/a.mjs backend/quizbuks/src/main/resources/application.yml
expect true 'a pattern is not a prefix' quizbuks-web/biz/srcx/a.mjs
expect true "'*' stays within one segment" quizbuks-web/biz/test/deep/a.ts
expect true "'.' is literal" quizbuks-web/biz/test/axts

if [[ "$failures" != 0 ]]; then
  echo "$failures failure(s)"
  exit 1
fi
