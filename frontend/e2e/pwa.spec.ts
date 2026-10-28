import { expect, type Page } from '@playwright/test'
import { PORTAL, portalCustomer, signInToPortal } from './portal-support'
import { test } from './support'
import { expectAccessible } from '@jabiz/ui/testing/playwright'

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

/** Android browsers as phones sold in China carry them (phase 17m): they never offer their own installation. */
const HUAWEI_BROWSER = 'Mozilla/5.0 (Linux; Android 12; HarmonyOS; NOH-AN00; HMSCore 6.13.0.302) AppleWebKit/537.36 '
  + '(KHTML, like Gecko) Chrome/114.0.5735.196 HuaweiBrowser/15.0.4.312 Mobile Safari/537.36'
const WECHAT = 'Mozilla/5.0 (Linux; Android 14; 23116PN5BC Build/UKQ1.230804.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) '
  + 'Version/4.0 Chrome/116.0.0.0 Mobile Safari/537.36 XWEB/1160117 MicroMessenger/8.0.49.2600(0x28003133) WeChat/arm64'

test('a browser without its own installation gets the steps of its menu; WeChat, the way out', async ({ browser, request }) => {
  const customer = await portalCustomer(request)
  for (const [userAgent, situation] of [[HUAWEI_BROWSER, 'menu'], [WECHAT, 'in-app']] as const) {
    const context = await browser.newContext({ userAgent, viewport: { width: 390, height: 844 }, hasTouch: true, locale: 'zh-CN' })
    // Neither browser ever offers its own installation; Chromium, under their names here, keeps quiet too.
    await context.addInitScript(() => window.addEventListener('beforeinstallprompt',
      (event) => event.stopImmediatePropagation(), true))
    const page = await context.newPage()
    await signInToPortal(page, customer)
    const offer = page.getByTestId('pwa-install')
    await expect(offer).toHaveAttribute('data-situation', situation)
    if (situation === 'menu') {
      await offer.getByRole('button', { name: '查看步骤' }).click()
      const guide = page.getByTestId('pwa-install-guide')
      await expect(guide).toHaveAttribute('data-browser', 'huawei')
      await expect(guide).toContainText('添加到桌面')
      await expect(page.getByTestId('pwa-install-permission')).toContainText('桌面快捷方式')
      await expectAccessible(page, 'install guide of a browser menu', { expect })
    } else {
      await expect(offer).toContainText('点右上角的"···"')
      await expectAccessible(page, 'open in the browser from WeChat', { expect })
    }
    await context.close()
  }
})
