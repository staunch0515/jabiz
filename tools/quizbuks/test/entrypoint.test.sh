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
echo "JABIZ_JWT_SECRET=${JABIZ_JWT_SECRET:-<unset>}"
echo "JABIZ_BOOTSTRAP_ADMIN_PASSWORD=${JABIZ_BOOTSTRAP_ADMIN_PASSWORD:-<unset>}"
NEXT
chmod +x "$work/next.sh"

# The platform's secrets as a server is given them (Q10b, M5: without them the entrypoint refuses to start).
SECRETS_SET=(JABIZ_JWT_SECRET=jwt-from-the-store JABIZ_INTEGRITY_KEY=integrity-from-the-store
  JABIZ_MFA_KEY=mfa-from-the-store JABIZ_BOOTSTRAP_ADMIN_PASSWORD=admin-from-the-store)

# bare <secrets dir> [VAR=value …]: the entrypoint (with /bin/sh, as in the image) in a clean environment; its
# stdout and stderr in $out, its exit status in $status.
bare() {
  local secrets="$1"
  shift
  mkdir -p "$secrets"
  status=0
  out="$(env -i PATH="$PATH" QB_ENTRYPOINT_SECRETS="$secrets" QB_ENTRYPOINT_NEXT="$work/next.sh" "$@" \
    /bin/sh "$script" --spring.main.banner-mode=off 2>&1)" || status=$?
}

# run <secrets dir> [VAR=value …]: as bare, with the platform's secrets set.
run() {
  local secrets="$1"
  shift
  bare "$secrets" "${SECRETS_SET[@]}" "$@"
}

# The output without the stand-in's line for the administrator's password (where it is shown on purpose).
printed() { grep -v '^JABIZ_BOOTSTRAP_ADMIN_PASSWORD=' <<<"$out" || true; }

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

# 5. The platform's secrets (Q10b, M5): a server must give them all; only a local demonstration generates them.
bare "$work/s5"
check "nothing set: refused" '[[ $status != 0 && "$out" == *"refusing to start"* && "$out" != *"next:"* ]]'
check "the refusal names every missing secret, nothing written" '[[ "$out" == *JABIZ_JWT_SECRET* &&
  "$out" == *JABIZ_INTEGRITY_KEY* && "$out" == *JABIZ_MFA_KEY* && "$out" == *JABIZ_BOOTSTRAP_ADMIN_PASSWORD* &&
  -z "$(ls -A "$work/s5")" ]]'
for name in JABIZ_JWT_SECRET JABIZ_INTEGRITY_KEY JABIZ_MFA_KEY JABIZ_BOOTSTRAP_ADMIN_PASSWORD; do
  given=()
  for pair in "${SECRETS_SET[@]}"; do [[ $pair == "$name="* ]] || given+=("$pair"); done
  bare "$work/s5-$name" "${given[@]}"
  check "missing $name alone: refused" '[[ $status != 0 && "$out" == *"not set: $name."* ]]'
done
run "$work/s5b"
check "all set: started, nothing generated" '[[ $status == 0 && "$(value JABIZ_JWT_SECRET)" == jwt-from-the-store &&
  "$(value JABIZ_BOOTSTRAP_ADMIN_PASSWORD)" == admin-from-the-store && -z "$(ls -A "$work/s5b")" ]]'
bare "$work/s5c" JABIZ_JWT_SECRET=a JABIZ_INTEGRITY_KEY=b JABIZ_MFA_KEY=c QB_BOOTSTRAP_ADMIN_DONE=true
check "administrator exists: a random password nobody sees" '[[ $status == 0 &&
  "$(value JABIZ_BOOTSTRAP_ADMIN_PASSWORD)" =~ ^[0-9a-f]{24}$ && -z "$(ls -A "$work/s5c")" ]] &&
  ! printed | grep -q "$(value JABIZ_BOOTSTRAP_ADMIN_PASSWORD)"'
bare "$work/s5d" QB_BOOTSTRAP_ADMIN_DONE=true
check "administrator exists, keys missing: refused" '[[ $status != 0 &&
  "$out" == *"not set: JABIZ_JWT_SECRET JABIZ_INTEGRITY_KEY JABIZ_MFA_KEY."* ]]'

# Local demonstration: the platform's entrypoint generates the keys; the administrator's password is made here, in a
# read-only file, and never printed.
bare "$work/s6" QB_LOCAL_SECRETS=true
password="$(value JABIZ_BOOTSTRAP_ADMIN_PASSWORD)"
check "local: started, the keys left to the platform" '[[ $status == 0 && "$out" == *"next:"* &&
  "$(value JABIZ_JWT_SECRET)" == "<unset>" ]]'
check "local: the password is made and kept in a read-only file" '[[ "$password" =~ ^[0-9a-f]{24}$ &&
  "$(cat "$work/s6/admin-password")" == "$password" && "$(stat -c %a "$work/s6/admin-password")" == 400 ]]'
check "local: the password is not printed, its path is" '[[ "$out" == *"$work/s6/admin-password"* ]] &&
  ! printed | grep -q "$password"'
bare "$work/s6" QB_LOCAL_SECRETS=true
check "local, second start: the same password, nothing announced" '[[ "$(value JABIZ_BOOTSTRAP_ADMIN_PASSWORD)" == "$password" &&
  "$out" != *"first start"* ]]'
bare "$work/s6b" QB_LOCAL_SECRETS=true JABIZ_BOOTSTRAP_ADMIN_PASSWORD=given
check "local: a given password wins" '[[ "$(value JABIZ_BOOTSTRAP_ADMIN_PASSWORD)" == given &&
  ! -e "$work/s6b/admin-password" ]]'
for live in QB_STRIPE_LIVEMODE=true QUIZBUKS_STRIPE_LIVEMODE=TRUE STRIPE_API_KEY=sk_live_x STRIPE_API_KEY=rk_live_x; do
  bare "$work/s7-$live" QB_LOCAL_SECRETS=true "$live"
  check "live mode ($live): nothing generated, refused" '[[ $status != 0 && "$out" == *"refusing to start"* &&
    -z "$(ls -A "$work/s7-$live")" ]]'
done
bare "$work/s8" QB_LOCAL_SECRETS=yes
check "QB_LOCAL_SECRETS other than true: refused" '[[ $status != 0 && "$out" == *"refusing to start"* ]]'

if ((failures)); then
  echo "$failures failed"
  exit 1
fi
echo "all passed"
