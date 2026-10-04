import { defineConfig } from '@playwright/test'

/** The performance data builder (build.sh) and load test (tools/finance/load): long tests through the API. */
export default defineConfig({
  testDir: '.',
  timeout: 24 * 60 * 60_000,
  workers: 1,
  retries: 0,
  reporter: 'list',
  outputDir: 'test-results',
  use: { baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080' },
})
