import { expect, expectAccessible, setAppearance, test } from './support'

/**
 * The business site at /biz/ (docs/quizbuks/plans/Q31-biz-site.md): every page in three languages with the same
 * layout, the language a first visitor is sent to by the browser's language, the switch that is remembered, the
 * sign-up buttons (a phone-sized window for takers on a desktop, a new tab for merchants), and axe on both
 * appearances. The server's part (a page per directory, platform 16zf-b) is what makes /biz/zh/ answer.
 */

const PAGES = ['', 'merchants/', 'takers/', 'surveys/', 'pricing/', 'contact/']
const LANGUAGES: Record<string, string> = { '': 'en', 'zh/': 'zh-Hans', 'ja/': 'ja' }

test('every page of every language is served with its own text and alternates', async ({ request }) => {
  for (const [prefix, lang] of Object.entries(LANGUAGES)) {
    for (const page of PAGES) {
      const response = await request.get(`/biz/${prefix}${page}`)
      expect(response.status(), `/biz/${prefix}${page}`).toBe(200)
      const html = await response.text()
      expect(html, `/biz/${prefix}${page}`).toContain(`<html lang="${lang}"`)
      expect(html).toContain('hreflang="x-default"')
      expect(html).toContain('<link rel="canonical" href="https://')
      expect(response.headers()['content-security-policy']).toContain("default-src 'self'")
    }
  }
  // Without a trailing slash, and a directory that is no page falls back to the English home.
  expect(await (await request.get('/biz/zh/pricing')).text()).toContain('<html lang="zh-Hans"')
  expect(await (await request.get('/biz/zh/nothing-here')).text()).toContain('<html lang="en"')
  const sitemap = await request.get('/biz/sitemap.xml')
  expect(sitemap.status()).toBe(200)
  expect(await sitemap.text()).toContain('<loc>https://quizbuks.com/biz/ja/pricing/</loc>')
})

test('a Chinese browser is sent to the Chinese page, keeps the page, and the switch is remembered', async ({
  browser,
}) => {
  const context = await browser.newContext({ locale: 'zh-CN' })
  const page = await context.newPage()
  await page.goto('/biz/pricing/')
  await expect(page).toHaveURL(/\/biz\/zh\/pricing\/$/)
  await expect(page.locator('html')).toHaveAttribute('lang', 'zh-Hans')
  // The visible switch to Japanese is remembered: the English root now goes to Japanese.
  await page.getByRole('link', { name: '日本語' }).click()
  await expect(page).toHaveURL(/\/biz\/ja\/pricing\/$/)
  // Following the site's own links is a choice: English chosen on the page stays English, whatever the browser says.
  await page.getByRole('link', { name: 'English' }).click()
  await expect(page).toHaveURL(/\/biz\/pricing\/$/)
  await page.waitForLoadState('networkidle')
  await expect(page.locator('html')).toHaveAttribute('lang', 'en')
  await page.getByRole('link', { name: '日本語' }).click()
  await expect(page).toHaveURL(/\/biz\/ja\/pricing\/$/)
  await page.goto('/biz/')
  await expect(page).toHaveURL(/\/biz\/ja\/$/)
  // A language page opened directly (a shared link) is never moved.
  await page.goto('/biz/zh/')
  await expect(page).toHaveURL(/\/biz\/zh\/$/)
  await context.close()
})

test('an English browser stays on the English pages', async ({ browser }) => {
  const context = await browser.newContext({ locale: 'en-US' })
  const page = await context.newPage()
  await page.goto('/biz/')
  await page.waitForLoadState('networkidle')
  await expect(page).toHaveURL(/\/biz\/$/)
  await expect(page.locator('h1')).toHaveText('Attention you can measure.')
  await context.close()
})

test('the taker button opens a phone-sized window on a desktop, the merchant button a new tab', async ({
  browser,
}) => {
  const context = await browser.newContext({ locale: 'en-US', viewport: { width: 1366, height: 800 } })
  const page = await context.newPage()
  await page.goto('/biz/')
  const [phone] = await Promise.all([
    context.waitForEvent('page'),
    page.getByRole('link', { name: 'Start answering — it’s free' }).click(),
  ])
  await phone.waitForLoadState()
  expect(new URL(phone.url()).pathname).toBe('/taker/signup')
  // Playwright gives a popup the context's viewport, so the window features are not observable here: the named
  // window is the one `window.open` was asked for, and it is the only one (no blocked-popup fallback fired too).
  expect(await phone.evaluate(() => window.name)).toBe('quizbuks-taker')
  expect(context.pages()).toHaveLength(2)
  await expect(phone.getByRole('button', { name: 'Create the account' })).toBeVisible()
  await phone.close()

  const [tab] = await Promise.all([
    context.waitForEvent('page'),
    page.getByRole('link', { name: 'Register your business' }).first().click(),
  ])
  await tab.waitForLoadState()
  expect(new URL(tab.url()).pathname).toBe('/sponsor/signup')
  await context.close()
})

test('the header signs a taker in through the phone-sized window and a merchant in a new tab (Q34)', async ({
  browser,
}) => {
  const context = await browser.newContext({ locale: 'en-US', viewport: { width: 1366, height: 800 } })
  const page = await context.newPage()
  await page.goto('/biz/')
  const signIn = page.getByRole('group', { name: 'Sign in' })
  const [phone] = await Promise.all([
    context.waitForEvent('page'),
    signIn.getByRole('link', { name: 'Taker sign-in' }).click(),
  ])
  await phone.waitForLoadState()
  expect(new URL(phone.url()).pathname).toBe('/taker/login')
  expect(await phone.evaluate(() => window.name)).toBe('quizbuks-taker')
  await phone.close()
  const [tab] = await Promise.all([
    context.waitForEvent('page'),
    signIn.getByRole('link', { name: 'Merchant sign-in' }).click(),
  ])
  await tab.waitForLoadState()
  expect(new URL(tab.url()).pathname).toBe('/sponsor/login')
  await context.close()
})

test('on a phone the taker button navigates in place', async ({ browser }) => {
  const context = await browser.newContext({ locale: 'en-US', viewport: { width: 390, height: 844 }, hasTouch: true,
    isMobile: true })
  const page = await context.newPage()
  await page.goto('/biz/')
  await page.getByRole('link', { name: 'Start answering — it’s free' }).click()
  await expect(page).toHaveURL(/\/taker\/signup$/)
  await context.close()
})

for (const appearance of ['light', 'dark'] as const) {
  test(`the business site passes axe (${appearance})`, async ({ page }) => {
    await setAppearance(page, appearance)
    for (const width of [390, 1366]) {
      await page.setViewportSize({ width, height: 900 })
      for (const path of ['/biz/', '/biz/ja/pricing/', '/biz/zh/merchants/', '/biz/contact/']) {
        await page.goto(path)
        await expect(page.locator('h1')).toBeVisible()
        if (width < 1180) {
          await page.getByRole('button', { name: /menu|菜单|メニュー/i }).click()
          await expect(page.getByRole('navigation', { name: /^(Site|站点|サイト)$/ })).toBeVisible()
        }
        await expectAccessible(page, `biz ${path} ${width}px (${appearance})`)
      }
    }
  })
}
