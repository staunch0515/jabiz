import { defineConfig } from '@playwright/test'
import main from './playwright.config'

/**
 * The first launch's end-to-end test (F-LAUNCH Q4, first-launch/): an application started from the first-launch
 * sample on an empty database, without a first administrator (tools/quizbuks/e2e.sh first-launch; CI's job
 * e2e-first-launch). The main suite (playwright.config.ts) needs that administrator, so neither runs the other's tests.
 */
export default defineConfig({
  ...main,
  testDir: './first-launch',
  timeout: 600_000,
})
