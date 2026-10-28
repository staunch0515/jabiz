#!/usr/bin/env bash
# Writes the Digital Asset Links statement of the Android app (docs/quizbuks/plans/Q49b-android.md) to stdout:
#   assetlinks.sh <package> <SHA-256 fingerprints, comma-separated>
# The deploy workflow runs it with the environment's variables QUIZBUKS_ANDROID_PACKAGE and
# QUIZBUKS_ANDROID_CERT_SHA256 (the app signing key's fingerprint from Play Console, and the upload key's) and uploads
# the result, which Caddy serves at /.well-known/assetlinks.json (server/site.caddy.tpl). Bad input fails without output:
# a wrong statement would only make the app show the browser's address bar, silently.
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "usage: $0 <package> <sha256-fingerprints,comma-separated>" >&2
  exit 2
fi
package="$1"
if [[ ! $package =~ ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$ ]]; then
  echo "not an Android package name: $package" >&2
  exit 1
fi
fingerprints=()
IFS=',' read -r -a given <<<"$2"
for fingerprint in "${given[@]}"; do
  # Play Console shows them upper-case with colons; spaces around the commas are allowed.
  fingerprint="$(tr -d '[:space:]' <<<"$fingerprint" | tr '[:lower:]' '[:upper:]')"
  [ -n "$fingerprint" ] || continue
  if [[ ! $fingerprint =~ ^([0-9A-F]{2}:){31}[0-9A-F]{2}$ ]]; then
    echo "not a SHA-256 certificate fingerprint (32 bytes, colon-separated): $fingerprint" >&2
    exit 1
  fi
  fingerprints+=("$fingerprint")
done
if [ ${#fingerprints[@]} -eq 0 ]; then
  echo "no fingerprint given" >&2
  exit 1
fi

jq -n --arg package "$package" --args '[{
  relation: ["delegate_permission/common.handle_all_urls"],
  target: { namespace: "android_app", package_name: $package, sha256_cert_fingerprints: $ARGS.positional }
}]' "${fingerprints[@]}"
