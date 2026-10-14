import { defineConfig, devices } from '@playwright/test'

/**
 * QuizBuks end-to-end tests (docs/quizbuks/plans/QP-pages.md section 7) against the packaged jar, which serves the app
 * at /, the sponsor console at /sponsor/, the administration at /admin/ and the API on one origin.
 *
 * - E2E_BASE_URL: the application (default http://localhost:8080); its mail goes to Mailpit, read through
 *   E2E_MAILPIT_URL (default http://localhost:8025).
 * - E2E_ADMIN_USER / E2E_ADMIN_PASSWORD: the first administrator (JABIZ_BOOTSTRAP_ADMIN_* on the server).
 * - E2E_CHROMIUM: a preinstalled Chromium instead of Playwright's own.
 *
 * Every test creates data of its own, so the suite can run against the same database more than once.
 */
export default defineConfig({
  testDir: './tests',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080',
    locale: 'en-US',
    timezoneId: 'Asia/Tokyo',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        launchOptions: process.env.E2E_CHROMIUM ? { executablePath: process.env.E2E_CHROMIUM } : {},
      },
    },
  ],
})
