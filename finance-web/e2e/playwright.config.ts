import { defineConfig, devices } from '@playwright/test'

/**
 * End-to-end tests of the finance pages against the packaged finance application (backend/finance bootJar), as the
 * platform's own (frontend/playwright.config.ts) but in the company's language and zone: US English, Chicago.
 * Run from frontend/ with its packages: tools/finance/e2e.sh. Every test makes data of its own.
 *
 * Browsers (FIN-UI-010): E2E_BROWSERS lists the projects to run, default chromium; CI runs chromium, firefox, webkit
 * and msedge (Edge through Playwright's channel). The others than chromium run at 1366 × 768, the smallest screen the
 * application must support; chromium at a common desktop size.
 */
const SMALLEST = { width: 1366, height: 768 }
const browsers = (process.env.E2E_BROWSERS ?? 'chromium').split(',').map((b) => b.trim()).filter(Boolean)
const KNOWN = ['chromium', 'firefox', 'webkit', 'msedge', 'chromium-1366']
const unknown = browsers.filter((b) => !KNOWN.includes(b))
if (unknown.length > 0) throw new Error(`E2E_BROWSERS: unknown ${unknown.join(', ')} (known: ${KNOWN.join(', ')})`)
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
    { name: 'firefox', use: { ...devices['Desktop Firefox'], viewport: SMALLEST } },
    { name: 'webkit', use: { ...devices['Desktop Safari'], viewport: SMALLEST } },
    { name: 'msedge', use: { ...devices['Desktop Edge'], viewport: SMALLEST } },
    // Chromium at the smallest screen, for checking the layout there without the other browsers.
    {
      name: 'chromium-1366',
      use: {
        ...devices['Desktop Chrome'],
        viewport: SMALLEST,
        launchOptions: process.env.E2E_CHROMIUM ? { executablePath: process.env.E2E_CHROMIUM } : {},
      },
    },
  ].filter((project) => browsers.includes(project.name)),
})
