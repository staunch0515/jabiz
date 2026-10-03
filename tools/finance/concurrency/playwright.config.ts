import { defineConfig } from '@playwright/test'

/**
 * FIN-NF-003 against a running finance application (tools/finance/concurrency/run.sh): one long test through the API,
 * no browser. Its time is the run's (NF003_MINUTES) and the restarts'.
 */
const minutes = Number(process.env.NF003_MINUTES ?? 30)

export default defineConfig({
  testDir: '.',
  timeout: (minutes + 30) * 60_000,
  workers: 1,
  retries: 0,
  reporter: 'list',
  outputDir: 'test-results',
  use: { baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080' },
})
