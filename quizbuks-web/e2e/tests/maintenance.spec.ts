import { createServer, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import { loadBrand } from '../../brands/brand.mjs'
import { maintenancePage } from '../../maintenance/build.mjs'
import { expect, expectAccessible, test } from './support'

/*
 * The maintenance page (docs/quizbuks/plans/Q55-maintenance-page.md) in a browser, served as Caddy serves it (503 for
 * every address) by a server of the test's own: it does not need the application. One language, by the time zone or
 * the browser's language; it asks for the opened address again and loads it once the server answers it.
 */

/** The brand's names in each language, as the page shows them (Q61). */
const NAMES = loadBrand().names

let server: Server
let base = ''
let down = true
let probes = 0

test.beforeAll(async () => {
  const page = maintenancePage()
  server = createServer((request, response) => {
    if (down) {
      probes++
      response.writeHead(503, { 'Content-Type': 'text/html; charset=utf-8', 'Retry-After': '60', 'Cache-Control': 'no-store' })
      response.end(page)
      return
    }
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html><html lang="en"><title>App</title><h1 data-testid="app">${request.url}</h1></html>`)
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
})

test.afterAll(() => new Promise<void>((resolve) => server.close(() => resolve())))

test.beforeEach(async ({ page }) => {
  down = true
  probes = 0
  // A second instead of ten, so that the test does not wait.
  await page.addInitScript(() => {
    (window as unknown as { __qbMaintenanceRetryMs: number }).__qbMaintenanceRetryMs = 1000
  })
})

/** The panels shown (one language). */
async function shown(page: import('@playwright/test').Page) {
  // The panels (the header's name follows the same language, Q61).
  return page.locator('main [data-l]').evaluateAll((panels) =>
    panels.filter((panel) => getComputedStyle(panel).display !== 'none').map((panel) => panel.getAttribute('data-l')))
}

test.describe('in Japan', () => {
  test.use({ timezoneId: 'Asia/Tokyo', locale: 'en-US' })

  test('shows Japanese only, by the time zone, and passes axe', async ({ page }) => {
    await page.goto(`${base}/taker/`)
    expect(await shown(page)).toEqual(['ja'])
    await expect(page.locator('header [data-l]:visible')).toHaveText(NAMES.ja)
    await expect(page.locator('html')).toHaveAttribute('lang', 'ja')
    await expect(page).toHaveTitle(`${NAMES.ja} — メンテナンス中`)
    await expect(page.getByRole('button', { name: '日本語' })).toHaveAttribute('aria-pressed', 'true')
    await expectAccessible(page, 'maintenance page')
  })
})

test.describe('in China', () => {
  test.use({ timezoneId: 'Asia/Shanghai', locale: 'en-US' })

  test('shows Chinese only; the link\'s language and the switch choose another', async ({ page }) => {
    await page.goto(`${base}/taker/`)
    expect(await shown(page)).toEqual(['zh'])
    await page.goto(`${base}/taker/p/x?lang=en`)
    expect(await shown(page)).toEqual(['en'])
    await page.getByRole('button', { name: '日本語' }).click()
    expect(await shown(page)).toEqual(['ja'])
    await expect(page.locator('html')).toHaveAttribute('lang', 'ja')
  })
})

test.describe('in the dark appearance', () => {
  test.use({ timezoneId: 'Asia/Shanghai', locale: 'zh-CN', colorScheme: 'dark' })

  test('passes axe', async ({ page }) => {
    await page.goto(`${base}/taker/`)
    await expectAccessible(page, 'maintenance page (dark)')
  })
})

test.describe('elsewhere', () => {
  test.use({ timezoneId: 'Europe/Paris', locale: 'zh-CN' })

  test('takes the browser\'s language, else English', async ({ page }) => {
    await page.goto(`${base}/taker/`)
    expect(await shown(page)).toEqual(['zh'])
  })
})

test.describe('in another time zone and language', () => {
  test.use({ timezoneId: 'America/New_York', locale: 'fr-FR' })

  test('shows English, asks again and returns to the address once the application answers', async ({ page }) => {
    await page.goto(`${base}/taker/p/abc?ref=card`)
    expect(await shown(page)).toEqual(['en'])
    // It keeps asking while the application is down.
    await expect.poll(() => probes, { timeout: 10_000 }).toBeGreaterThanOrEqual(3)
    await expect(page.locator('[data-l="en"] [data-status]')).toContainText(/Checking again in \d s|Checking…/)
    down = false
    await expect(page.getByTestId('app')).toHaveText('/taker/p/abc?ref=card', { timeout: 10_000 })
    expect(page.url()).toBe(`${base}/taker/p/abc?ref=card`)
  })
})
