#!/usr/bin/env bash
# Tests of deploy/quizbuks/assetlinks.sh (docs/quizbuks/plans/Q49b-android.md): the statement Caddy serves at
# /.well-known/assetlinks.json for the Android app.
set -euo pipefail

script="$(cd "$(dirname "$0")/../../.." && pwd)/deploy/quizbuks/assetlinks.sh"
failures=0
A="$(printf 'AB:%.0s' {1..31})CD"
B="$(printf '0f:%.0s' {1..31})e1"

fail() {
  echo "FAIL: $*" >&2
  failures=$((failures + 1))
}

# run <args…>: stdout in $out, stderr in $err, exit status in $status.
run() {
  status=0
  out="$("$script" "$@" 2>"${TMPDIR:-/tmp}/assetlinks.err")" || status=$?
  err="$(cat "${TMPDIR:-/tmp}/assetlinks.err")"
}

run com.quizbuks.app "$A, $B"
[ "$status" -eq 0 ] || fail "two fingerprints: exit $status ($err)"
[ "$(jq -r '.[0].target.package_name' <<<"$out")" = com.quizbuks.app ] || fail "package name: $out"
[ "$(jq -r '.[0].target.namespace' <<<"$out")" = android_app ] || fail "namespace: $out"
[ "$(jq -r '.[0].relation[0]' <<<"$out")" = delegate_permission/common.handle_all_urls ] || fail "relation: $out"
[ "$(jq -c '.[0].target.sha256_cert_fingerprints' <<<"$out")" = "[\"$A\",\"${B^^}\"]" ] \
  || fail "both fingerprints, upper-case: $out"

run com.quizbuks.app "$A"
[ "$status" -eq 0 ] && [ "$(jq '.[0].target.sha256_cert_fingerprints | length' <<<"$out")" = 1 ] \
  || fail "one fingerprint: exit $status, $out"

for bad in "Com.Quizbuks" "quizbuks" "com.quizbuks.app;rm"; do
  run "$bad" "$A"
  [ "$status" -ne 0 ] && [ -z "$out" ] || fail "package $bad accepted"
done
for bad in "" " , " "${A%:CD}" "$A:00" "SHA256:$A" "$A\"}]"; do
  run com.quizbuks.app "$bad"
  [ "$status" -ne 0 ] && [ -z "$out" ] || fail "fingerprint '$bad' accepted"
done
run com.quizbuks.app
[ "$status" -eq 2 ] || fail "missing argument: exit $status"

if [ "$failures" -ne 0 ]; then
  echo "$failures failure(s)" >&2
  exit 1
fi
echo "assetlinks.sh: all passed"
