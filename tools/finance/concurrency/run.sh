#!/usr/bin/env bash
# FIN-NF-003 (docs/finance/perf.md §7): 20 people posting for 30 minutes through the API of a running finance
# application while it is killed twice, then the invariants. With the platform frontend's packages, as the end-to-end
# tests (tools/finance/e2e.sh). Environment:
#   E2E_BASE_URL (http://localhost:8080), E2E_ADMIN_USER / E2E_ADMIN_PASSWORD (the bootstrap administrator);
#   NF003_START  the command that starts the application again in the background (required when it is killed);
#   NF003_KILL   the command that kills it (default: kill -9 of the finance jar); NF003_MINUTES (30), NF003_USERS (20),
#   NF003_KILLS (2); PG* for psql, the application's database (the invariants are read there).
# Only against a disposable installation: it makes users with known passwords and documents that stay.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$root/frontend"
NODE_PATH="$root/frontend/node_modules" exec node_modules/.bin/playwright test \
  --config "$root/tools/finance/concurrency/playwright.config.ts" "$@"
