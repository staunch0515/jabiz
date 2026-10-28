#!/usr/bin/env bash
# Writes the apple-app-site-association of the iOS app (docs/quizbuks/plans/Q49c-ios.md) to stdout:
#   apple-app-site-association.sh <app ID>      (<Team ID>.<bundle ID>, e.g. ABCDE12345.com.quizbuks.app)
# The deploy workflow runs it with the environment's variable QUIZBUKS_IOS_APP_ID and uploads the result, which
# Caddy serves at /.well-known/apple-app-site-association (server/site.caddy.tpl): Universal Links for the takers'
# app's addresses only (/taker/), so a mail's link opens the store app and the sponsor console stays in Safari.
# Bad input fails without output.
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "usage: $0 <team-id>.<bundle-id>" >&2
  exit 2
fi
app_id="$1"
if [[ ! $app_id =~ ^[A-Z0-9]{10}\.[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+$ ]]; then
  echo "not an iOS app ID (<10-character Team ID>.<bundle ID>): $app_id" >&2
  exit 1
fi

jq -n --arg app "$app_id" '{
  applinks: { details: [{ appIDs: [$app], components: [{ "/": "/taker/*", comment: "the takers app only" }] }] }
}'
