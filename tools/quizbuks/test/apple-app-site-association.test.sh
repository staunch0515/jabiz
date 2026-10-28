#!/usr/bin/env bash
# Tests of deploy/quizbuks/apple-app-site-association.sh (docs/quizbuks/plans/Q49c-ios.md): the statement Caddy serves
# at /.well-known/apple-app-site-association for the iOS app's Universal Links.
set -euo pipefail

script="$(cd "$(dirname "$0")/../../.." && pwd)/deploy/quizbuks/apple-app-site-association.sh"
failures=0

fail() {
  echo "FAIL: $*" >&2
  failures=$((failures + 1))
}

# run <args…>: stdout in $out, exit status in $status.
run() {
  status=0
  out="$("$script" "$@" 2>/dev/null)" || status=$?
}

run ABCDE12345.com.quizbuks.app
[ "$status" -eq 0 ] || fail "a valid app ID: exit $status"
[ "$(jq -c '.applinks.details[0].appIDs' <<<"$out")" = '["ABCDE12345.com.quizbuks.app"]' ] || fail "app ID: $out"
[ "$(jq -r '.applinks.details[0].components | length' <<<"$out")" = 1 ] || fail "one component: $out"
[ "$(jq -r '.applinks.details[0].components[0]["/"]' <<<"$out")" = '/taker/*' ] || fail "only /taker/: $out"

for bad in "com.quizbuks.app" "abcde12345.com.quizbuks.app" "ABCDE1234.com.quizbuks.app" "ABCDE12345.com" \
  "ABCDE12345.com.quizbuks.app\"}" "ABCDE12345.com.quizbuks.app x"; do
  run "$bad"
  [ "$status" -ne 0 ] && [ -z "$out" ] || fail "app ID '$bad' accepted"
done
run
[ "$status" -eq 2 ] || fail "missing argument: exit $status"

if [ "$failures" -ne 0 ]; then
  echo "$failures failure(s)" >&2
  exit 1
fi
echo "apple-app-site-association.sh: all passed"
