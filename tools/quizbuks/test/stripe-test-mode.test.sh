#!/usr/bin/env bash
# Tests of tools/quizbuks/stripe-test-mode/run.sh (docs/quizbuks/stripe-test-mode.md): the safety guard, the argument
# parsing and that no secret is ever printed. Offline: every case is refused, or stops, before any request is made
# (the guard runs first), so nothing reaches Stripe or an application.
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
script="$root/tools/quizbuks/stripe-test-mode/run.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

# Fake keys, assembled at run time so that no key-shaped literal is in the repository (push protection).
sk=sk; rk=rk; pk=pk
KEY=${sk}_test_51TestOnlyAbCdEfGhIjKlMnOp
LIVE=${sk}_live_51LiveKeyMustNeverPrint00
ADMIN=admin-password-never-printed

# Production hosts of the tests' own (the real list beside the script is empty).
printf '# test list\nprod.quizbuks.test\n*.live.quizbuks.test\n' > "$work/production-hosts"

# run [VAR=value …] -- <run.sh arguments>: run.sh in a clean environment; its output in $out, its exit code in $code.
run() {
  local vars=()
  while [[ $1 != -- ]]; do vars+=("$1"); shift; done
  shift
  set +e
  out="$(env -i PATH="$PATH" HOME="$work" STRIPE_TM_STATE="$work/state" \
    STRIPE_TM_PRODUCTION_HOSTS_FILE="$work/production-hosts" "${vars[@]}" "$script" "$@" 2>&1)"
  code=$?
  set -e
}

check() { # name, condition (a bash expression)
  if eval "$2"; then
    echo "ok   $1"
  else
    echo "FAIL $1: $2 (exit $code)"
    sed 's/^/    /' <<<"$out"
    failures=$((failures + 1))
  fi
}

ok_env=(STRIPE_TEST_SECRET_KEY=$KEY STRIPE_TM_ADMIN_PASSWORD=$ADMIN)

# 1. The key.
run STRIPE_TM_ADMIN_PASSWORD=$ADMIN -- guard
check "no key: refused" '[[ $code == 2 && $out == *REFUSED*"STRIPE_TEST_SECRET_KEY is not set"* ]]'
run STRIPE_TEST_SECRET_KEY=$LIVE STRIPE_TM_ADMIN_PASSWORD=$ADMIN -- guard
check "live key: refused" '[[ $code == 2 && $out == *"LIVE key"* ]]'
check "live key: never printed" '[[ $out != *"$LIVE"* && $out != *51LiveKey* ]]'
run STRIPE_TEST_SECRET_KEY=${rk}_live_51Restricted0000000 STRIPE_TM_ADMIN_PASSWORD=$ADMIN -- guard
check "restricted live key: refused" '[[ $code == 2 && $out == *"LIVE key"* && $out != *51Restricted* ]]'
run STRIPE_TEST_SECRET_KEY=${rk}_test_51Restricted0000000 STRIPE_TM_ADMIN_PASSWORD=$ADMIN -- guard
check "restricted test key: refused" '[[ $code == 2 && $out == *"restricted key"* && $out != *51Restricted* ]]'
run STRIPE_TEST_SECRET_KEY=${pk}_test_51Publishable000000 STRIPE_TM_ADMIN_PASSWORD=$ADMIN -- guard
check "publishable key: refused" '[[ $code == 2 && $out == *"not a secret test key"* ]]'
run "${ok_env[@]}" -- guard
check "test key on localhost: ok" '[[ $code == 0 && $out == *"guard: ok"* ]]'
check "test key: never printed" '[[ $out != *"$KEY"* && $out != *"$ADMIN"* ]]'
run STRIPE_TEST_SECRET_KEY=$KEY -- guard
check "no administrator password: refused" '[[ $code == 2 && $out == *STRIPE_TM_ADMIN_PASSWORD* ]]'

# 2. The target.
for base in http://127.0.0.1:8080 'http://[::1]:8080' http://127.0.0.2:9000 http://LOCALHOST:8080/; do
  run "${ok_env[@]}" STRIPE_TM_BASE_URL="$base" -- guard
  check "loopback $base: ok" '[[ $code == 0 ]]'
done
run "${ok_env[@]}" -- guard --base-url https://test.quizbuks.test
check "remote without --allow-remote: refused" '[[ $code == 2 && $out == *"--allow-remote"* ]]'
run "${ok_env[@]}" -- guard --base-url https://test.quizbuks.test --allow-remote
check "remote https with --allow-remote: ok" '[[ $code == 0 ]]'
run "${ok_env[@]}" STRIPE_TM_ALLOW_REMOTE=yes -- guard --base-url https://test.quizbuks.test
check "STRIPE_TM_ALLOW_REMOTE=yes: ok" '[[ $code == 0 ]]'
run "${ok_env[@]}" -- guard --base-url http://test.quizbuks.test --allow-remote
check "remote plain http: refused" '[[ $code == 2 && $out == *"must be https"* ]]'
run "${ok_env[@]}" -- guard --base-url http://localhost.quizbuks.test --allow-remote
check "a name that only starts like localhost is remote" '[[ $code == 2 && $out == *"must be https"* ]]'
run "${ok_env[@]}" -- guard --base-url https://prod.quizbuks.test --allow-remote
check "production host: refused even with --allow-remote" '[[ $code == 2 && $out == *"production host"* ]]'
run "${ok_env[@]}" -- guard --base-url https://api.live.quizbuks.test --allow-remote
check "production wildcard: refused" '[[ $code == 2 && $out == *"production host"* ]]'
run "${ok_env[@]}" -- guard --base-url https://live.quizbuks.test --allow-remote
check "production wildcard covers the domain itself" '[[ $code == 2 && $out == *"production host"* ]]'
run "${ok_env[@]}" STRIPE_TM_PRODUCTION_HOSTS=Other.Example -- guard --base-url https://other.example. --allow-remote
check "STRIPE_TM_PRODUCTION_HOSTS adds hosts (case and trailing dot ignored)" '[[ $code == 2 && $out == *"production host"* ]]'
run "${ok_env[@]}" -- guard --base-url "https://user:pw@test.quizbuks.test" --allow-remote
check "credentials in the URL: refused" '[[ $code == 2 && $out == *credentials* ]]'
run "${ok_env[@]}" -- guard --base-url ftp://localhost
check "not http(s): refused" '[[ $code == 2 && $out == *"not an http(s) address"* ]]'
run "${ok_env[@]}" -- guard --base-url https://prod.quizbuks.test
check "every problem at once" '[[ $code == 2 && $out == *"production host"* && $out == *"--allow-remote"* ]]'

# 3. Live mode in this environment.
run "${ok_env[@]}" QB_STRIPE_LIVEMODE=true -- guard
check "QB_STRIPE_LIVEMODE=true: refused" '[[ $code == 2 && $out == *"live mode is never driven"* ]]'
run "${ok_env[@]}" QUIZBUKS_STRIPE_LIVEMODE=yes -- guard
check "QUIZBUKS_STRIPE_LIVEMODE=yes: refused" '[[ $code == 2 && $out == *QUIZBUKS_STRIPE_LIVEMODE* ]]'
run "${ok_env[@]}" QB_STRIPE_LIVEMODE=FALSE -- guard
check "QB_STRIPE_LIVEMODE=FALSE: ok" '[[ $code == 0 ]]'

# 4. The stub (dry run): no key needed, this machine only, a live key still refused.
run STRIPE_TM_ADMIN_PASSWORD=$ADMIN -- guard --stub
check "stub without a key: ok" '[[ $code == 0 && $out == *"(stub)"* ]]'
run STRIPE_TM_ADMIN_PASSWORD=$ADMIN STRIPE_TEST_SECRET_KEY=$LIVE -- guard --stub
check "stub with a live key around: refused" '[[ $code == 2 && $out != *51LiveKey* ]]'
run STRIPE_TM_ADMIN_PASSWORD=$ADMIN -- guard --stub --base-url https://test.quizbuks.test --allow-remote
check "stub against a remote: refused" '[[ $code == 2 && $out == *"--stub only drives this machine"* ]]'
run STRIPE_TM_ADMIN_PASSWORD=$ADMIN -- guard --stub --stub-url http://stub.quizbuks.test:8093
check "remote stub: refused" '[[ $code == 2 && $out == *"stub URL"* ]]'

# 5. The other commands guard first and parse their arguments.
run STRIPE_TEST_SECRET_KEY=$LIVE STRIPE_TM_ADMIN_PASSWORD=$ADMIN -- run --base-url http://localhost:1
check "run with a live key: refused before any request" '[[ $code == 2 && $out == *REFUSED* && $out != *"Connection refused"* ]]'
run "${ok_env[@]}" -- payout-check po_123 --base-url https://prod.quizbuks.test --allow-remote
check "payout-check is guarded" '[[ $code == 2 && $out == *"production host"* ]]'
run "${ok_env[@]}" -- payout-check
check "payout-check needs an id" '[[ $code == 2 && $out == *"needs a payout id"* ]]'
run "${ok_env[@]}" -- run --topup 0
check "amounts must be positive" '[[ $code == 2 && $out == *"--topup must be positive"* ]]'
run "${ok_env[@]}" -- run --topup 2000 --reward 2000 --refund 1000
check "the top-up must cover the reward and the refund" '[[ $code == 2 && $out == *"must cover"* ]]'
run "${ok_env[@]}" -- run --no-such-option
check "unknown option: refused" '[[ $code == 2 && $out == *"unrecognized arguments"* ]]'
run --
check "no command: usage" '[[ $code == 2 && $out == *"run.sh serve"* ]]'
run "${ok_env[@]}" -- serve --nonsense
check "serve: unknown option" '[[ $code == 1 && $out == *"unknown option"* ]]'

# 6. serve's settings reach run only for serve's own address, and never over a set value.
mkdir -p "$work/state"
printf 'STRIPE_TM_BASE_URL=http://localhost:8080\nSTRIPE_TM_ADMIN_PASSWORD=from-serve-123\nIGNORED=x\n' > "$work/state/run.env"
run STRIPE_TEST_SECRET_KEY=$KEY -- guard
check "run.env gives serve's administrator" '[[ $code == 0 && $out != *from-serve-123* ]]'
run STRIPE_TEST_SECRET_KEY=$KEY -- guard --base-url https://test.quizbuks.test --allow-remote
check "run.env is not used for another target" '[[ $code == 2 && $out == *STRIPE_TM_ADMIN_PASSWORD* ]]'
rm "$work/state/run.env"

# 7. The helpers: TOTP (RFC 6238's SHA-1 vector, 6 digits) and the redaction of anything that looks secret.
helpers="$(python3 -I -B -c '
import sys
sys.path.insert(0, sys.argv[1])
import stm
print(stm.totp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59)[0], stm.totp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 1111111109)[0])
stm.secret("hunter2-password")
print(stm.redact("key sk_test_abc123 rk_live_XYZ whsec_0123abcd eyJa.eyJb.sig hunter2-password ok"))
print(stm.is_production("A.Live.Quizbuks.Test", ["*.live.quizbuks.test"]), stm.is_loopback("127.1.2.3"),
      stm.is_loopback("[::1]"), stm.is_loopback("localhost.evil.test"))
' "$root/tools/quizbuks/stripe-test-mode" 2>&1)" || true
out=$helpers
check "TOTP matches RFC 6238" '[[ $(sed -n 1p <<<"$out") == "287082 081804" ]]'
check "redaction" '[[ $(sed -n 2p <<<"$out") == "key sk_test_*** rk_live_*** whsec_*** <token> *** ok" ]]'
check "host matching" '[[ $(sed -n 3p <<<"$out") == "True True True False" ]]'

# 8. The Stripe-mode branches with fakes (checkout checks, funding, dispute, payout): stm_test.py.
set +e
out="$(env -i PATH="$PATH" STRIPE_TM_STATE="$work/state" python3 -I -B "$root/tools/quizbuks/test/stm_test.py" 2>&1)"
code=$?
set -e
check "stm_test.py (fakes of Stripe and the application)" '[[ $code == 0 && $out == *OK* ]]'

if ((failures)); then
  echo "$failures failed"
  exit 1
fi
echo "all passed"
