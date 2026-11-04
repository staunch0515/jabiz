import type { Page } from '@playwright/test'
import { expect, expectAccessible, setAppearance, test } from './support'

/**
 * The business site at /biz/ (docs/quizbuks/plans/Q31-biz-site.md): every page in three languages with the same
 * layout, the language a first visitor is sent to by the browser's language, the switch that is remembered, the
 * sign-up buttons (a phone-sized window for takers on a desktop, a new tab for merchants), and axe on both
 * appearances. The server's part (a page per directory, platform 16zf-b) is what makes /biz/zh/ answer.
 */

const PAGES = ['', 'maps/', 'sponsors/', 'takers/', 'surveys/', 'pricing/', 'contact/']
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
  // Q95: the merchants' page moved to sponsors/; the old addresses of all three languages still lead there.
  for (const prefix of ['', 'zh/', 'ja/']) {
    const moved = await request.get(`/biz/${prefix}merchants/`, { maxRedirects: 0 })
    expect(moved.status()).toBe(301)
    expect(moved.headers()['location']).toContain(`/biz/${prefix}sponsors/`)
  }
  const sitemap = await request.get('/biz/sitemap.xml')
  expect(sitemap.status()).toBe(200)
  expect(await sitemap.text()).toContain('<loc>https://quizbuks.com/biz/ja/pricing/</loc>')
})

/** Picks a language in the header's drop-down (Q41): the links show once it is open. */
async function chooseLanguage(page: Page, name: string) {
  // Below 1,351px the languages are in the menu, as a visitor finds them.
  const menu = page.locator('[data-menu-toggle]')
  if (await menu.isVisible()) await menu.click()
  await page.getByTestId('lang-toggle').click()
  await page.locator('[data-lang-menu]').getByRole('link', { name }).click()
}

test('a Chinese browser is sent to the Chinese page, keeps the page, and the switch is remembered', async ({
  browser,
}) => {
  const context = await browser.newContext({ locale: 'zh-CN' })
  const page = await context.newPage()
  await page.goto('/biz/pricing/')
  await expect(page).toHaveURL(/\/biz\/zh\/pricing\/$/)
  await expect(page.locator('html')).toHaveAttribute('lang', 'zh-Hans')
  // The visible switch to Japanese is remembered: the English root now goes to Japanese.
  await chooseLanguage(page, '日本語')
  await expect(page).toHaveURL(/\/biz\/ja\/pricing\/$/)
  // Following the site's own links is a choice: English chosen on the page stays English, whatever the browser says.
  await chooseLanguage(page, 'English')
  await expect(page).toHaveURL(/\/biz\/pricing\/$/)
  await page.waitForLoadState('networkidle')
  await expect(page.locator('html')).toHaveAttribute('lang', 'en')
  await chooseLanguage(page, '日本語')
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

test('the taker button opens a phone-sized window on a desktop, the sponsor button a new tab', async ({
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
    page.getByRole('link', { name: 'Become a sponsor' }).first().click(),
  ])
  await tab.waitForLoadState()
  expect(new URL(tab.url()).pathname).toBe('/sponsor/signup')
  await context.close()
})

test('the header signs a taker in through the phone-sized window and a sponsor in a new tab (Q34)', async ({
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
    signIn.getByRole('link', { name: 'Sponsor sign-in' }).click(),
  ])
  await tab.waitForLoadState()
  expect(new URL(tab.url()).pathname).toBe('/sponsor/login')
  await context.close()
})

// Q95: seven links pushed the Japanese wordmark into a column. Every language keeps the header in one row where the
// menu is shown inline (above 1,350px), the wordmark on one line at every width, and no page scrolls sideways.
test('the header fits in every language, the wordmark on one line', async ({ browser }) => {
  for (const [prefix, locale] of [['', 'en-US'], ['zh/', 'zh-CN'], ['ja/', 'ja-JP']] as const) {
    const context = await browser.newContext({ locale })
    const page = await context.newPage()
    for (const width of [390, 1024, 1351, 1366, 1440, 1600, 1920]) {
      await page.setViewportSize({ width, height: 800 })
      await page.goto(`/biz/${prefix}pricing/`)
      const header = await page.evaluate(() => {
        const bar = document.querySelector('.bar')!.getBoundingClientRect()
        const menu = document.querySelector<HTMLElement>('.menu')!
        return {
          wordmark: document.querySelector('.bar .brand')!.getBoundingClientRect().height,
          inline: getComputedStyle(menu).display !== 'none',
          fits: menu.getBoundingClientRect().right <= bar.right + 0.5 && menu.scrollWidth <= menu.clientWidth + 0.5,
          sideways: document.documentElement.scrollWidth > window.innerWidth,
        }
      })
      const where = `/biz/${prefix} at ${width}px`
      expect(header.wordmark, where).toBeLessThan(40)
      expect(header.sideways, where).toBe(false)
      expect(header.inline, where).toBe(width > 1350)
      if (header.inline) expect(header.fits, where).toBe(true)
    }
    await context.close()
  }
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
      for (const path of ['/biz/', '/biz/ja/pricing/', '/biz/zh/sponsors/', '/biz/ja/maps/', '/biz/contact/']) {
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
