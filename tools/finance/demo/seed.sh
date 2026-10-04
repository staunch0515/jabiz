#!/usr/bin/env bash
# Fills a newly installed finance application with the demonstration data (docs/finance/install.md §6): the sample
# company Northwind Components, January 2026 posted and closed, February's approvals waiting, five people with their
# roles. Through the API, as its people would; once, on empty books (a second run changes nothing).
#
#   E2E_ADMIN_PASSWORD=… DEMO_PASSWORD=<12 characters or more> tools/finance/demo/seed.sh
#
# Environment: E2E_BASE_URL (http://localhost:8080), E2E_ADMIN_USER (admin) / E2E_ADMIN_PASSWORD (the bootstrap
# administrator; the application with JABIZ_SECURITY_MFA_ADMINISTRATION=false, as finance setup otherwise asks for
# the administrator's code), DEMO_PASSWORD (the five people's password). Needs Node 22 and the platform frontend's
# packages (cd frontend && pnpm install), as the end-to-end tests.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$root/frontend"
NODE_PATH="$root/frontend/node_modules" exec node_modules/.bin/playwright test \
  --config "$root/tools/finance/demo/playwright.config.ts" seed.spec.ts "$@"
