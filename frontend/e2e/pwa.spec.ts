import { expect, type Page } from '@playwright/test'
import { PORTAL, portalCustomer, signInToPortal } from './portal-support'
import { test } from './support'

/**
 * The portal as a PWA (ROADMAP phase 16c-2; decision D43 items 6 and 10): its Service Worker controls /portal/ only,
 * caches the application's shell and nothing of the API, answers pages from the shell when offline, and a new
 * version takes over only when the user agrees.
 */

/** Waits until the portal's worker is active and controls the page (the first one claims the page). */
async function controlled(page: Page) {
  await expect.poll(() => page.evaluate(async () => {
    const registration = await navigator.serviceWorker.getRegistration()
    return !!registration?.active && !!navigator.serviceWorker.controller
  })).toBe(true)
}

test('the worker controls the portal only and never the API', async ({ page, context, request }) => {
  const customer = await portalCustomer(request)
  const fromWorker: string[] = []
  page.on('response', (response) => {
    if (new URL(response.url()).pathname.startsWith('/api') && response.fromServiceWorker()) {
      fromWorker.push(response.url())
    }
  })
  await signInToPortal(page, customer)
  await controlled(page)

  const scope = await page.evaluate(async () => (await navigator.serviceWorker.getRegistration())!.scope)
  expect(new URL(scope).pathname).toBe(PORTAL)
  // Pages and API calls under the worker: the API is never answered by it.
  await page.reload()
  await expect(page.getByTestId('orders-empty')).toBeVisible()
  expect(await page.evaluate(() => !!navigator.serviceWorker.controller)).toBe(true)
  expect(fromWorker).toEqual([])

  // The caches hold the shell only: the portal's own files, nothing of the API, no page with a token.
  const cached = await page.evaluate(async () => {
    const urls: string[] = []
    for (const name of await caches.keys()) {
      for (const request of await (await caches.open(name)).keys()) urls.push(`${name} ${new URL(request.url).pathname}`)
    }
    return urls
  })
  expect(cached.length).toBeGreaterThan(0)
  for (const entry of cached) {
    expect(entry).toMatch(/^jabiz-shell-portal-[0-9a-f]+ \/portal\//)
    expect(entry).not.toContain('/api')
  }
  expect(cached.some((entry) => entry.endsWith('/portal/index.html'))).toBe(true)

  // The administration at / is not under the worker.
  const admin = await context.newPage()
  await admin.goto('/login')
  expect(await admin.evaluate(() => navigator.serviceWorker.controller)).toBeNull()
  expect(await admin.evaluate(async () => (await navigator.serviceWorker.getRegistration('/'))?.scope ?? null))
    .not.toBe(new URL('/', admin.url()).href)
})

test('offline, a page of the portal shows its shell and says it is offline', async ({ page, context, request }) => {
  const customer = await portalCustomer(request)
  await signInToPortal(page, customer)
  await controlled(page)

  await context.setOffline(true)
  try {
    await page.goto(`${PORTAL}order`)
    await expect(page.getByTestId('offline-banner')).toBeVisible()
  } finally {
    await context.setOffline(false)
  }
})

test('a new version waits until the user reloads', async ({ page }) => {
  await page.goto(`${PORTAL}login`)
  await controlled(page)
  const before = await page.evaluate(() => navigator.serviceWorker.controller!.scriptURL)

  // A new version of the worker, as the browser finds one after a new build: here the same script under another
  // address of the same scope (the server answers /portal/sw.js whatever the query), which the browser installs as an
  // update next to the one in control.
  await page.evaluate(() => navigator.serviceWorker.register('/portal/sw.js?next', { scope: '/portal/' }))
  await expect(page.getByTestId('pwa-update')).toBeVisible()
  // It waits, and nothing reloads by itself.
  await page.waitForTimeout(1000)
  await expect(page.getByTestId('pwa-update')).toBeVisible()
  expect(await page.evaluate(() => navigator.serviceWorker.controller!.scriptURL)).toBe(before)
  expect(await page.evaluate(async () => (await navigator.serviceWorker.getRegistration())!.waiting?.scriptURL))
    .toMatch(/\/portal\/sw\.js\?next$/)

  // The user agrees: the new version takes over and the page loads again under it.
  await Promise.all([page.waitForEvent('load'), page.getByTestId('pwa-update-reload').click()])
  await expect.poll(() => page.evaluate(() => navigator.serviceWorker.controller?.scriptURL ?? null))
    .toMatch(/\/portal\/sw\.js\?next$/)
})
