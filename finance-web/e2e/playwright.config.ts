import { defineConfig, devices } from '@playwright/test'

/**
 * End-to-end tests of the finance pages against the packaged finance application (backend/finance bootJar), as the
 * platform's own (frontend/playwright.config.ts) but in the company's language and zone: US English, Chicago.
 * Run from frontend/ with its packages: tools/finance/e2e.sh. Every test makes data of its own.
 */
export default defineConfig({
  testDir: '.',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]] : 'list',
  outputDir: 'test-results',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080',
    locale: 'en-US',
    timezoneId: 'America/Chicago',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1600, height: 1000 },
        launchOptions: process.env.E2E_CHROMIUM ? { executablePath: process.env.E2E_CHROMIUM } : {},
      },
    },
  ],
})
