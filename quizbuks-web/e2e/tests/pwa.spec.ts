import type { Page } from '@playwright/test'
import { expect, test } from './support'

/**
 * The app as a PWA at /taker/ (docs/quizbuks/plans/QP-pages.md sections 2 and 10, Q30; decision D43 items 6 and 10):
 * its manifest and Service Worker under /taker/, nothing of the kind for the sponsor console, the administration or the
 * business site, and the app's worker leaves their pages and the API to the network. The bare address opens the
 * business site; the app's old addresses at the root go on to /taker/ (platform 16zf-a).
 */

/** Waits until the app's worker is active and controls the page (the first one claims the page). */
async function controlled(page: Page) {
  await expect.poll(() => page.evaluate(async () => {
    const registration = await navigator.serviceWorker.getRegistration()
    return !!registration?.active && !!navigator.serviceWorker.controller
  })).toBe(true)
}

test('the app has a manifest and the only Service Worker of the site', async ({ request }) => {
  const manifest = await request.get('/taker/manifest.webmanifest')
  expect(manifest.status()).toBe(200)
  expect(manifest.headers()['content-type']).toContain('application/manifest+json')
  const body = await manifest.json()
  expect(body).toMatchObject({ name: 'QuizBuks', start_url: '/taker/', scope: '/taker/', display: 'standalone' })
  expect((body.icons as { sizes: string }[]).map((icon) => icon.sizes)).toEqual(['192x192', '512x512', '512x512'])
  for (const icon of body.icons as { src: string }[]) {
    expect((await request.get(icon.src)).headers()['content-type']).toContain('image/png')
  }

  expect((await request.get('/taker/sw.js', { headers: { 'Service-Worker': 'script' } })).status()).toBe(200)
  // Neither the sponsor console nor the administration is a PWA.
  for (const path of ['/sponsor/sw.js', '/admin/sw.js', '/biz/sw.js', '/sponsor/manifest.webmanifest', '/sw.js']) {
    expect((await request.get(path, { headers: { 'Service-Worker': 'script' } })).status(), path).toBe(404)
  }
  // Client routes of each front end fall back to its own page.
  for (const path of ['/taker/welcome', '/sponsor/onboarding', '/sponsor/wallet', '/admin/data']) {
    const page = await request.get(path)
    expect(page.status(), path).toBe(200)
    expect(await page.text(), path).toContain('id="root"')
  }
  // The bare address opens the business site; the app's old addresses go on to the app (Q30, platform 16zf-a).
  const root = await request.get('/?ref=card', { maxRedirects: 0 })
  expect(root.status()).toBe(302)
  expect(root.headers()['location']).toBe('/biz/?ref=card')
  const old = await request.get('/p/some-id', { maxRedirects: 0 })
  expect(old.status()).toBe(301)
  expect(old.headers()['location']).toBe('/taker/p/some-id')
  const biz = await request.get('/biz/')
  expect(biz.status()).toBe(200)
  expect(await biz.text()).toContain('QuizBuks')
})

test('the app\'s worker leaves the sponsor console, the administration and the API to the network', async ({
  page,
  context,
}) => {
  const fromWorker: string[] = []
  context.on('response', (response) => {
    const path = new URL(response.url()).pathname
    if ((path.startsWith('/api') || path.startsWith('/sponsor') || path.startsWith('/admin'))
      && response.fromServiceWorker()) {
      fromWorker.push(path)
    }
  })
  await page.goto('/taker/login')
  await controlled(page)
  const scope = await page.evaluate(async () => (await navigator.serviceWorker.getRegistration())!.scope)
  expect(new URL(scope).pathname).toBe('/taker/')

  await page.goto('/sponsor/login')
  await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible()
  await page.goto('/admin/login')
  await expect(page.locator('#root')).not.toBeEmpty()
  await page.goto('/taker/login')
  await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible()
  expect(fromWorker).toEqual([])

  // The caches hold the app's shell only: nothing of the API, of the other front ends, or a page with a token.
  const cached = await page.evaluate(async () => {
    const urls: string[] = []
    for (const name of await caches.keys()) {
      for (const request of await (await caches.open(name)).keys()) urls.push(`${name} ${new URL(request.url).pathname}`)
    }
    return urls
  })
  expect(cached.length).toBeGreaterThan(0)
  for (const entry of cached) {
    expect(entry).toMatch(/^jabiz-shell-taker-[0-9a-f]+ \//)
    expect(entry).not.toMatch(/ \/(api|sponsor|admin|biz)\//)
  }
})

test('offline, the app shows its shell and says so', async ({ page, context }) => {
  await page.goto('/taker/login')
  await controlled(page)
  await context.setOffline(true)
  try {
    await page.goto('/taker/signup')
    await expect(page.getByTestId('offline-banner')).toBeVisible()
  } finally {
    await context.setOffline(false)
  }
})

test('a new version of the app waits until the user reloads', async ({ page }) => {
  await page.goto('/taker/login')
  await controlled(page)
  const before = await page.evaluate(() => navigator.serviceWorker.controller!.scriptURL)
  // A new version, as the browser finds one after a deployment: the same script under another address of the scope.
  await page.evaluate(() => navigator.serviceWorker.register('/taker/sw.js?next', { scope: '/taker/' }))
  await expect(page.getByTestId('pwa-update')).toBeVisible()
  await page.waitForTimeout(1000)
  expect(await page.evaluate(() => navigator.serviceWorker.controller!.scriptURL)).toBe(before)
  await Promise.all([page.waitForEvent('load'), page.getByTestId('pwa-update-reload').click()])
  await expect.poll(() => page.evaluate(() => navigator.serviceWorker.controller?.scriptURL ?? null))
    .toMatch(/\/sw\.js\?next$/)
})
