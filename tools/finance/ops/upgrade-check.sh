#!/usr/bin/env bash
# The upgrade check of FIN-NF-006 (docs/finance/install.md §5): the reports of FIN-EXP-01 … 14 are read through the
# API before an upgrade and again after it, and must be the same.
#
#   upgrade-check.sh before   writes UPGRADE_DIR/before.json (stop the users' work first)
#   … upgrade: docker compose -f deploy/finance/docker-compose.yml up -d --build (the migrations run at start) …
#   upgrade-check.sh after    writes UPGRADE_DIR/after.json and compares it with before.json
#
# Environment: E2E_BASE_URL (http://localhost:8080), REPORTS_USER / REPORTS_PASSWORD (a user with the reports'
# permissions; default E2E_ADMIN_USER / E2E_ADMIN_PASSWORD), REPORTS_FROM / REPORTS_THROUGH (the month; January 2026),
# REPORTS_BANK (OPERATING), UPGRADE_DIR (./upgrade-check). With the platform frontend's packages, as tools/finance/e2e.sh.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
dir="$(mkdir -p "${UPGRADE_DIR:-upgrade-check}" && cd "${UPGRADE_DIR:-upgrade-check}" && pwd)"
case "${1:-}" in
  before) export REPORTS_OUT="$dir/before.json"; unset REPORTS_COMPARE ;;
  after) export REPORTS_OUT="$dir/after.json" REPORTS_COMPARE="$dir/before.json" ;;
  *) echo "usage: $0 before|after" >&2; exit 2 ;;
esac
cd "$root/frontend"
NODE_PATH="$root/frontend/node_modules" exec node_modules/.bin/playwright test \
  --config "$root/tools/finance/ops/playwright.config.ts" reports.spec.ts
