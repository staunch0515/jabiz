import { expect, seedWorld, settled, tabTo, test, type World } from './support'

test.describe.configure({ mode: 'serial' })

let w: World

test.beforeAll(async ({ request, browser }) => {
  w = await seedWorld(request, browser)
})

/**
 * Only the keyboard: home → theme → story → play the video (docs/culture/ROADMAP.md, C3). And nothing is requested
 * from anywhere but the site itself until the video is asked for (design section 9.3).
 */
test('keyboard only, home to a playing video, with no third-party request before the click', async ({ page, context }) => {
  const thirdParty: string[] = []
  const origin = new URL(process.env.E2E_BASE_URL ?? 'http://localhost:8080').origin
  await context.route(
    (url) => url.origin !== origin,
    async (route) => {
      thirdParty.push(route.request().url())
      await route.abort()
    },
  )
  await page.goto('/en/')
  await settled(page)

  await tabTo(page, page.getByRole('link', { name: new RegExp(w.themeQuestion.replace(/[()?]/g, '.')) }), 400)
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(w.themeQuestion)
  await expect(page.locator('main')).toBeFocused()

  await tabTo(page, page.getByRole('link', { name: w.storyTitle }))
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(w.storyTitle)
  await settled(page)

  const play = page.getByRole('button', { name: `Play video: ${w.storyTitle}` })
  await tabTo(page, play)
  expect(thirdParty, 'requests to other hosts before the click').toEqual([])
  await expect(page.locator('iframe')).toHaveCount(0)

  await page.keyboard.press('Enter')
  const frame = page.locator('iframe')
  await expect(frame).toHaveAttribute('src', /^https:\/\/www\.youtube-nocookie\.com\/embed\/aqz-KE-bpKQ\?.*cc_load_policy=1/)
  await expect(frame).toHaveAttribute('title', `Video: ${w.storyTitle}`)
  await expect(frame).toBeFocused()
  await expect.poll(() => thirdParty.some((u) => u.startsWith('https://www.youtube-nocookie.com/'))).toBe(true)
  expect(thirdParty.every((u) => u.startsWith('https://www.youtube-nocookie.com/'))).toBe(true)
})

test('the site sets no cookies and stores nothing', async ({ page, context }) => {
  await page.goto(`/en/stories/${w.story}`)
  await settled(page)
  expect(await context.cookies()).toEqual([])
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0)
})

test('on a phone the menu opens with the focus in it, and Esc closes it', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 740 })
  await page.goto('/en/')
  await settled(page)
  const menu = page.getByRole('button', { name: 'Menu' })
  await expect(page.getByRole('navigation', { name: 'Main' })).toBeHidden()
  await tabTo(page, menu)
  await page.keyboard.press('Enter')
  await expect(menu).toHaveAttribute('aria-expanded', 'true')
  await expect(page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Home' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(menu).toHaveAttribute('aria-expanded', 'false')
  await expect(menu).toBeFocused()

  await menu.click()
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Themes' }).click()
  await expect(page).toHaveURL('/en/themes')
  await expect(menu).toHaveAttribute('aria-expanded', 'false')
})

test('with reduced motion nothing moves', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/en/')
  await settled(page)
  const animated = await page.evaluate(() =>
    [...document.querySelectorAll('*')].filter((el) => getComputedStyle(el).animationName !== 'none').length,
  )
  expect(animated).toBe(0)
})

test('the language switch keeps the page', async ({ page }) => {
  await page.goto(`/en/themes/${w.theme}`)
  await settled(page)
  await page.getByRole('list', { name: 'Language' }).getByRole('link', { name: '日本語' }).click()
  await expect(page).toHaveURL(`/ja/themes/${w.theme}`)
  await expect(page.locator('html')).toHaveAttribute('lang', 'ja')
  await expect(page.getByRole('button', { name: '並べて比べる' })).toBeVisible()
})

test('/ goes to the browser language', async ({ browser }) => {
  const context = await browser.newContext({ locale: 'zh-CN' })
  const page = await context.newPage()
  await page.goto('/')
  await expect(page).toHaveURL(/\/zh\/$/)
  await context.close()
})
