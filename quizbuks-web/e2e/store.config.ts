import { defineConfig } from '@playwright/test'

/**
 * The store's screenshots (store/screenshots.spec.ts, docs/quizbuks/plans/Q49d-store.md), against the packaged jar as
 * the e2e tests (same environment variables). Not part of `pnpm e2e`: `pnpm store` (the QuizBuks store workflow).
 * STORE_OUT: where the pictures go (default store-screenshots).
 */
export default defineConfig({
  testDir: './store',
  timeout: 300_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080',
    trace: 'retain-on-failure',
    launchOptions: process.env.E2E_CHROMIUM ? { executablePath: process.env.E2E_CHROMIUM } : {},
  },
})
