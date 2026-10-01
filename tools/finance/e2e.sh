#!/usr/bin/env bash
# Runs the finance pages' end-to-end tests (finance-web/e2e) against a running finance application, with the
# platform frontend's packages: the extension has none of its own (decision D22), so Node finds @playwright/test
# through NODE_PATH. Environment as for the platform's tests: E2E_BASE_URL (default http://localhost:8080),
# E2E_ADMIN_USER / E2E_ADMIN_PASSWORD (the bootstrap administrator), E2E_CHROMIUM (an installed Chromium).
# Extra arguments go to Playwright (a test name, --headed …).
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root/frontend"
NODE_PATH="$root/frontend/node_modules" exec node_modules/.bin/playwright test \
  --config "$root/finance-web/e2e/playwright.config.ts" "$@"
