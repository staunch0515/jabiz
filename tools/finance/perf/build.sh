#!/usr/bin/env bash
# Builds the FIN-NF-001 performance data (docs/finance/perf.md §8) on a running finance application: build.spec.ts
# through the API and clone.sql in its database. Environment: E2E_BASE_URL, E2E_ADMIN_USER / E2E_ADMIN_PASSWORD (the
# bootstrap administrator; the application with JABIZ_SECURITY_MFA_ADMINISTRATION=false); PG* for psql, the
# application's database, its name with "perf"; PERF_YEAR (2025), PERF_SCALE (1). With the platform frontend's
# packages, as the end-to-end tests (tools/finance/e2e.sh). Only a database of its own: the copies stay.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
if [[ "${PGDATABASE:-}" != *perf* ]]; then
  echo "build: PGDATABASE '${PGDATABASE:-}' is not a performance database (its name must contain 'perf')" >&2
  exit 2
fi
cd "$root/frontend"
NODE_PATH="$root/frontend/node_modules" exec node_modules/.bin/playwright test \
  --config "$root/tools/finance/perf/playwright.config.ts" build.spec.ts "$@"
