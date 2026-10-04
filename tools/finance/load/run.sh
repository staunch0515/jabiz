#!/usr/bin/env bash
# FIN-NF-002 (docs/finance/perf.md §8) on a running finance application with the performance data
# (tools/finance/perf/build.sh): `run.sh` the load test (load.spec.ts), `run.sh batch` the batch timings (batch.spec.ts,
# which closes the year: run it last). Environment: E2E_BASE_URL, E2E_ADMIN_USER / E2E_ADMIN_PASSWORD; PERF_YEAR
# (2025); for the load test LOAD_USERS (50), LOAD_MINUTES (10), LOAD_DATE (posting day, PERF_YEAR-12-15), LOAD_REPORT
# (a JSON file for the results); for the batches PG* (the application's database, its name with "perf"), BATCH_LINES
# (5,000), BATCH_REPORT. With the platform frontend's packages, as the end-to-end tests (tools/finance/e2e.sh). Both
# post documents: only on a database of its own.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
spec=load.spec.ts
if [[ "${1:-}" == batch ]]; then
  shift
  spec=batch.spec.ts
  if [[ "${PGDATABASE:-}" != *perf* ]]; then
    echo "run: PGDATABASE '${PGDATABASE:-}' is not a performance database (its name must contain 'perf')" >&2
    exit 2
  fi
fi
cd "$root/frontend"
NODE_PATH="$root/frontend/node_modules" exec node_modules/.bin/playwright test \
  --config "$root/tools/finance/load/playwright.config.ts" "$spec" "$@"
