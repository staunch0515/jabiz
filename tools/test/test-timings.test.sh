#!/usr/bin/env bash
# Tests of tools/ci/test-timings.py (ROADMAP 17zz13): the class times a module's test-timings.txt lists for splitting
# its tests into CI shards.
set -euo pipefail

script="$(cd "$(dirname "$0")/.." && pwd)/ci/test-timings.py"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

check() {
  if [ "$2" = yes ]; then echo "ok   $1"; else echo "FAIL $1: $out"; failures=$((failures + 1)); fi
}

suite() { # suite <class> <reported time> <test time>...
  local name=$1 time=$2 cases=''
  shift 2
  for t in "$@"; do cases="$cases<testcase name=\"t$t\" classname=\"$name\" time=\"$t\"/>"; done
  echo "<?xml version=\"1.0\"?><testsuite name=\"$name\" tests=\"$#\" time=\"$time\">$cases</testsuite>" \
    >"$work/results/TEST-$name.xml"
}

mkdir -p "$work/results"
suite a.SlowIT 30.25 10 12
suite a.QuickTest 0.04 0.01 0.02
# A jqwik container: open for the whole run, its tests took a fraction of a second.
suite a.PropertyTest 3000 0.2 0.1
out=$(python3 -I "$script" "$work/results")
lines=$(grep -v '^#' <<<"$out")
[ "$lines" = $'a.SlowIT 30.2\na.PropertyTest 0.3\na.QuickTest 0.0' ] && r=yes || r=no
check "longest first, one decimal, a jqwik container counts its tests" $r
grep -q '^# Regenerate' <<<"$out" && r=yes || r=no
check "says how to regenerate" $r

mkdir -p "$work/empty"
status=0; out=$(python3 -I "$script" "$work/empty" 2>&1) || status=$?
[ "$status" -eq 1 ] && r=yes || r=no
check "no results: exit 1" $r
status=0; out=$(python3 -I "$script" "$work/nowhere" 2>&1) || status=$?
[ "$status" -eq 2 ] && r=yes || r=no
check "no directory: usage, exit 2" $r

[ "$failures" -eq 0 ] || { echo "$failures failure(s)"; exit 1; }
echo "all test-timings tests passed"
