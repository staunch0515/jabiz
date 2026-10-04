import { defineConfig } from '@playwright/test'

/** The FIN-NF-002 load test (run.sh): one long test through the API. */
export default defineConfig({
  testDir: '.',
  timeout: 6 * 60 * 60_000,
  workers: 1,
  retries: 0,
  reporter: 'list',
  outputDir: 'test-results',
  use: { baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080' },
})
