import { defineConfig } from '@playwright/test'

/** The operations tools' steps against a running finance application (drill.sh, upgrade-check.sh): API only. */
export default defineConfig({
  testDir: '.',
  timeout: 10 * 60_000,
  workers: 1,
  retries: 0,
  reporter: 'list',
  outputDir: 'test-results',
  use: { baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080' },
})
