import { defineConfig, devices } from '@playwright/test'

/**
 * End-to-end tests against a running application (docs/design/12-frontend.md section 8): the packaged jar serves the
 * built frontend and the API on one origin. E2E_BASE_URL points at it (default http://localhost:8080); the first
 * administrator comes from E2E_ADMIN_USER / E2E_ADMIN_PASSWORD, set up by JABIZ_BOOTSTRAP_ADMIN_* on the server.
 * Every test creates data of its own, so the suite can run against the same database more than once.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080',
    locale: 'zh-CN',
    timezoneId: 'Asia/Tokyo',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // A preinstalled Chromium can be used instead of a downloaded one.
        launchOptions: process.env.E2E_CHROMIUM ? { executablePath: process.env.E2E_CHROMIUM } : {},
      },
    },
  ],
})
