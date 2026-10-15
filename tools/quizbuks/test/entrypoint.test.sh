#!/usr/bin/env bash
# Tests of deploy/quizbuks/entrypoint.sh (deploy/quizbuks/README.md, "本机的 compose"): the entrypoint runs with a
# fake platform entrypoint that prints the Stripe settings it was handed, and a temporary secrets volume.
set -euo pipefail

script="$(cd "$(dirname "$0")/../../.." && pwd)/deploy/quizbuks/entrypoint.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

# The platform's entrypoint stand-in: prints what it received.
cat >"$work/next.sh" <<'NEXT'
#!/bin/sh
echo "next: $*"
echo "STRIPE_API_KEY=${STRIPE_API_KEY:-<unset>}"
echo "STRIPE_WEBHOOK_SECRET=${STRIPE_WEBHOOK_SECRET:-<unset>}"
echo "STRIPE_CONNECT_WEBHOOK_SECRET=${STRIPE_CONNECT_WEBHOOK_SECRET:-<unset>}"
NEXT
chmod +x "$work/next.sh"

# run <secrets dir> [VAR=value …]: the entrypoint (with /bin/sh, as in the image) in a clean environment; its
# stdout and stderr in $out.
run() {
  local secrets="$1"
  shift
  mkdir -p "$secrets"
  out="$(env -i PATH="$PATH" QB_ENTRYPOINT_SECRETS="$secrets" QB_ENTRYPOINT_NEXT="$work/next.sh" "$@" \
    /bin/sh "$script" --spring.main.banner-mode=off 2>&1)"
}

value() { sed -n "s/^$1=//p" <<<"$out"; }

check() { # name, condition (a bash expression)
  if eval "$2"; then
    echo "ok   $1"
  else
    echo "FAIL $1: $2"
    sed 's/^/    /' <<<"$out"
    failures=$((failures + 1))
  fi
}

# 1. Nothing set (compose's default): placeholders, generated once and kept.
run "$work/s1" QB_STRIPE_PLACEHOLDERS=true
check "arguments are passed on" '[[ "$out" == *"next: --spring.main.banner-mode=off"* ]]'
check "placeholder key is a fake test-mode key" '[[ "$(value STRIPE_API_KEY)" == sk_test_*local_placeholder* ]]'
check "webhook secret is whsec_ and 48 hex" '[[ "$(value STRIPE_WEBHOOK_SECRET)" =~ ^whsec_[0-9a-f]{48}$ ]]'
check "connect secret is whsec_ and 48 hex" '[[ "$(value STRIPE_CONNECT_WEBHOOK_SECRET)" =~ ^whsec_[0-9a-f]{48}$ ]]'
check "the two secrets differ" '[[ "$(value STRIPE_WEBHOOK_SECRET)" != "$(value STRIPE_CONNECT_WEBHOOK_SECRET)" ]]'
check "secrets are kept in the volume" \
  '[[ "$(cat "$work/s1/stripe-webhook-secret")" == "$(value STRIPE_WEBHOOK_SECRET)" &&
      "$(cat "$work/s1/stripe-connect-webhook-secret")" == "$(value STRIPE_CONNECT_WEBHOOK_SECRET)" ]]'
check "secret files are private" '[[ "$(stat -c %a "$work/s1/stripe-webhook-secret")" == 600 ]]'
check "one warning per placeholder" '[[ "$(grep -c "WARNING: .*Stripe is not configured" <<<"$out")" == 3 ]]'
check "generation is announced" '[[ "$(grep -c "generated a placeholder Stripe" <<<"$out")" == 2 ]]'
first_secret="$(value STRIPE_WEBHOOK_SECRET)"
first_connect="$(value STRIPE_CONNECT_WEBHOOK_SECRET)"
run "$work/s1" QB_STRIPE_PLACEHOLDERS=true
check "second start reuses the secrets" \
  '[[ "$(value STRIPE_WEBHOOK_SECRET)" == "$first_secret" && "$(value STRIPE_CONNECT_WEBHOOK_SECRET)" == "$first_connect" ]]'
check "second start generates nothing" '! grep -q "generated" <<<"$out"'
check "second start still warns" '[[ "$(grep -c "WARNING: " <<<"$out")" == 3 ]]'
run "$work/s1b" QB_STRIPE_PLACEHOLDERS=true QB_STRIPE_LIVEMODE=FALSE
check "explicit false is test mode" '[[ "$(value STRIPE_API_KEY)" == sk_test_* ]]'

# 2. Values that are set win, each on its own.
run "$work/s2" QB_STRIPE_PLACEHOLDERS=true STRIPE_API_KEY=rk_test_real STRIPE_WEBHOOK_SECRET=whsec_mine \
  STRIPE_CONNECT_WEBHOOK_SECRET=whsec_connect_mine
check "set values are untouched" '[[ "$(value STRIPE_API_KEY)" == rk_test_real &&
  "$(value STRIPE_WEBHOOK_SECRET)" == whsec_mine && "$(value STRIPE_CONNECT_WEBHOOK_SECRET)" == whsec_connect_mine ]]'
check "set values: no warning, nothing written" '! grep -q WARNING <<<"$out" && [[ -z "$(ls -A "$work/s2")" ]]'
run "$work/s2b" QB_STRIPE_PLACEHOLDERS=true STRIPE_WEBHOOK_SECRET=whsec_mine
check "only the unset ones are filled" '[[ "$(value STRIPE_WEBHOOK_SECRET)" == whsec_mine &&
  "$(value STRIPE_API_KEY)" == sk_test_* && ! -e "$work/s2b/stripe-webhook-secret" &&
  "$(grep -c WARNING <<<"$out")" == 2 ]]'

# 3. Live mode: nothing filled, so the startup check fails as before.
for live in QB_STRIPE_LIVEMODE=true QUIZBUKS_STRIPE_LIVEMODE=true QB_STRIPE_LIVEMODE=yes; do
  run "$work/s3-$live" QB_STRIPE_PLACEHOLDERS=true "$live"
  check "live mode ($live): no placeholders" '[[ "$(value STRIPE_API_KEY)" == "<unset>" &&
    "$(value STRIPE_WEBHOOK_SECRET)" == "<unset>" && "$(value STRIPE_CONNECT_WEBHOOK_SECRET)" == "<unset>" &&
    -z "$(ls -A "$work/s3-$live")" ]]'
done
run "$work/s3b" QB_STRIPE_PLACEHOLDERS=true QB_STRIPE_LIVEMODE=true STRIPE_API_KEY=sk_live_x
check "live mode keeps set values" '[[ "$(value STRIPE_API_KEY)" == sk_live_x ]]'

# 4. Without the switch (the image outside compose, a server): nothing filled.
run "$work/s4"
check "no switch: no placeholders" '[[ "$(value STRIPE_API_KEY)" == "<unset>" &&
  "$(value STRIPE_WEBHOOK_SECRET)" == "<unset>" && -z "$(ls -A "$work/s4")" ]] && ! grep -q WARNING <<<"$out"'
run "$work/s4b" QB_STRIPE_PLACEHOLDERS=false
check "switch false: no placeholders" '[[ "$(value STRIPE_API_KEY)" == "<unset>" ]]'

if ((failures)); then
  echo "$failures failed"
  exit 1
fi
echo "all passed"
