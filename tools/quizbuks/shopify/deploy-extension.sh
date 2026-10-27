#!/usr/bin/env bash
# Deploys QuizBuks for Shopify's app configuration and its storefront block (docs/quizbuks/shopify/S2b-ui.md
# section 3): builds the block's script (quizbuks-web/storefront, within its size budget) into the extension's assets,
# then runs the Shopify CLI from this directory, signed in to the Partner account that owns the app. The app's
# shopify.app.toml must have its client_id and host filled in first (see the template's comments).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
storefront="$here/../../../quizbuks-web/storefront"

(cd "$storefront" && pnpm install --frozen-lockfile && pnpm build)
cd "$here"
shopify app deploy "$@"
