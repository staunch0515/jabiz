import { defineConfig, devices } from '@playwright/test'

/**
 * End-to-end tests of the public site against the running culture application (docs/culture/00-design.md section 13):
 * the packaged jar serves the site at / and the API on one origin, with public access switched on
 * (JABIZ_PUBLIC_ENABLED=true). Content is prepared through the admin API as the first administrator
 * (E2E_ADMIN_USER / E2E_ADMIN_PASSWORD); every run adds content of its own and deletes nothing.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080',
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
