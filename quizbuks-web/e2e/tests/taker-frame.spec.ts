import { expect, expectAccessible, prepareQuizbuks, signIn, takerArea, test, type TakerArea, openTakerArea } from './support'
import { takerInJapan } from './wallet'

/*
 * The taker app's frame (F-F4, docs/quizbuks/plans/impl/F-F4.md sections 2, 3 and 7; Q113 §9.2 since Q113a-2): the
 * bottom menu of four icons and the floating scan button on a phone, "Me" in the avatar's drawer, the three columns on
 * a wide screen, every area without a sideways scroll at 360 px, every address the app had still a page of the right
 * area, and the redirects of `LEGACY_APP_PATHS`.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** The bottom menu's areas, in its order (Q113 §9.2). */
const BOTTOM: TakerArea[] = ['home', 'search', 'history', 'messages']

/** The width the page is wider than the screen by (zero or less when it fits). */
const overflow = (page: import('@playwright/test').Page) =>
  page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)

test('a phone has the bottom menu of four icons, scanning floating, "Me" in the drawer, and every area fits 360 px', async ({
  page, request,
}) => {
  test.setTimeout(180_000)
  const me = await takerInJapan(request, 'Frame')
  await page.setViewportSize({ width: 360, height: 780 })
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()

  const bar = page.getByRole('navigation', { name: 'App' })
  await expect(bar.getByRole('link')).toHaveCount(BOTTOM.length)
  await expect(page.getByTestId('side-rail')).toBeHidden()
  await expect(page.getByTestId('side-aside')).toBeHidden()
  await expect(page.getByTestId('bottom-bar-more')).toHaveCount(0)
  await expect(page.getByTestId('page-fab')).toBeVisible()

  for (const area of BOTTOM) {
    await expect(page.getByTestId(`bottom-bar-${area}`)).toBeVisible()
    await page.getByTestId(`bottom-bar-${area}`).click()
    await expect(page.getByTestId(`bottom-bar-${area}`)).toHaveAttribute('aria-current', 'page')
    if (area !== 'home' && area !== 'search') await expect(page.locator('[data-slot="page-title"]').first()).toBeVisible()
    await page.waitForLoadState('networkidle')
    expect(await overflow(page), `${area} fits 360 px`).toBeLessThanOrEqual(0)
  }
  // Scanning from the floating button; "Me" from the drawer: pages under another, no entry of the menu marked.
  for (const area of ['scan', 'me'] as const) {
    await openTakerArea(page, area)
    await expect(page.getByTestId('header-back')).toBeVisible()
    await expect(bar.locator('[aria-current="page"]')).toHaveCount(0)
    await page.waitForLoadState('networkidle')
    expect(await overflow(page), `${area} fits 360 px`).toBeLessThanOrEqual(0)
  }
  // The pages of "Me" and the tabs too.
  for (const path of ['/taker/history/prizes', '/taker/scan/pass', '/taker/conversations', '/taker/account/blocks',
    '/taker/account/preferences', '/taker/account/id', '/taker/wallet']) {
    await page.goto(path)
    await page.waitForLoadState('networkidle')
    expect(await overflow(page), `${path} fits 360 px`).toBeLessThanOrEqual(0)
  }
  await expectAccessible(page, 'app wallet at 360px')
})

test('a wide screen has the three columns: the left one, the page and the right one, and no bottom menu', async ({
  page, request,
}) => {
  test.setTimeout(180_000)
  const me = await takerInJapan(request, 'Column')
  await page.setViewportSize({ width: 1280, height: 900 })
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()

  const rail = page.getByTestId('side-rail')
  await expect(rail).toBeVisible()
  await expect(page.getByTestId('side-aside')).toBeVisible()
  await expect(page.getByTestId('bottom-menu')).toBeHidden()
  await expect(page.getByTestId('page-fab')).toBeHidden()
  // The left column, then the page beside it.
  const railBox = (await rail.boundingBox())!
  const homeBox = (await page.getByTestId('home').boundingBox())!
  expect(homeBox.x).toBeGreaterThanOrEqual(railBox.x + railBox.width)

  for (const area of [...BOTTOM, 'me', 'scan'] as const) {
    await page.getByTestId(`side-rail-${area}`).click()
    if (area !== 'scan') await expect(page.getByTestId(`side-rail-${area}`)).toHaveAttribute('aria-current', 'page')
    else await expect(page).toHaveURL(/\/taker\/scan$/)
  }
  await expectAccessible(page, 'app left column')
})

/**
 * Every address the app had when F-F4 began, and the area that marks it (`OLD_ROUTES` in `frame/areas.ts`, with the
 * unit test that pins its areas). An id is a sample: the page for an id there is none of is the page's own "not found",
 * inside the frame. Pages outside the areas are a signed-in taker's redirect or public page.
 */
const OLD_ROUTES: [string, TakerArea | null][] = [
  ['/', 'home'], ['/?q=sample', 'home'], ['/p/sample', 'home'],
  // Q113a-2's new address.
  ['/search', 'search'], ['/search?q=sample', 'search'],
  ['/history', 'history'], ['/result/sample', 'history'],
  ['/scan', 'scan'], ['/redeem/sample', 'scan'], ['/pass/sample', 'scan'],
  ['/inbox', 'messages'], ['/inbox/message/sample', 'messages'], ['/inbox/broadcast/sample', 'messages'],
  ['/conversations', 'messages'], ['/conversations/sample', 'messages'],
  ['/account', 'me'], ['/account/profile', 'me'], ['/account/details', 'me'], ['/account/certificates', 'me'],
  ['/account/security', 'me'], ['/account/mail', 'me'], ['/account/preferences', 'me'], ['/account/id', 'me'],
  ['/account/delete', 'me'],
  ['/wallet', 'me'], ['/wallet/balance', 'me'], ['/wallet/rewards', 'me'], ['/wallet/history', 'me'],
  ['/wallet/kudos/PLATFORM', 'me'], ['/wallet/kudos/SPONSOR_CASH', 'me'], ['/wallet/kudos/OFFLINE_REDEEM', 'me'],
  ['/wallet/kudos/DIGITAL_PRIZE', 'me'], ['/wallet/payout', 'me'], ['/wallet/bank', 'me'],
  ['/referrals', 'me'], ['/referral/sample', 'me'], ['/my-sponsor', 'me'], ['/staff', 'me'], ['/staff/handled', 'me'],
  ['/help', null], ['/about', null], ['/legal/terms', null], ['/legal/privacy', null], ['/legal/contest-rules', null],
  ['/verify/sample', null], ['/u/sample', null], ['/s/sample', null],
  // F-F4b's new addresses.
  ['/history/prizes', 'history'], ['/scan/pass', 'scan'], ['/account/blocks', 'me'],
]

test('every address the app had is still a page, in the area that marks it', async ({ page, request }) => {
  test.setTimeout(420_000)
  const me = await takerInJapan(request, 'Routes')
  await page.setViewportSize({ width: 390, height: 844 })
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()
  for (const [path, area] of OLD_ROUTES) {
    await page.goto(`/taker${path}`)
    await expect(page, path).toHaveURL(new RegExp(`/taker${path.replace(/[?/]/g, (c) => `\\${c}`)}$`))
    if (area === 'scan' || area === 'me') {
      // Reached from the floating button and the drawer: the bottom menu is there, no entry of it marked.
      await expect(page.getByTestId('bottom-menu'), path).toBeVisible()
      await expect(page.locator('[data-slot="bottom-bar"] [aria-current="page"]'), path).toHaveCount(0)
    } else if (area) await expect(takerArea(page, area), path).toHaveAttribute('aria-current', 'page')
    else await expect(page.getByTestId('bottom-menu'), path).toHaveCount(0)
  }
})

test('an address that changed leads to its page now', async ({ page, request }) => {
  const me = await takerInJapan(request, 'Legacy')
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()
  // LEGACY_APP_PATHS: the stores' address of a deletion without the app, and the account's old section.
  await page.goto('/taker/delete-account')
  await expect(page).toHaveURL(/\/taker\/auth\/account-deletion$/)
  await page.goto('/taker/account?section=details')
  await expect(page).toHaveURL(/\/taker\/account\/details$/)
  await expect(page.getByTestId('header-back')).toHaveAccessibleName('Back to Me')
})

test('"Coming soon" beside the Kudos is the same on "Me" and on the wallet', async ({ page, request }) => {
  const me = await takerInJapan(request, 'Soon')
  await page.setViewportSize({ width: 390, height: 844 })
  await signIn(page, '/taker/', me.email)
  await openTakerArea(page, 'me')
  await expect(page.getByTestId('me-number-kudos-value')).not.toHaveText('–')
  const onMe = await page.getByTestId('me-number-kudos-note').count()
  await page.getByTestId('me-number-kudos').click()
  await expect(page).toHaveURL(/\/taker\/wallet$/)
  await expect(page.getByTestId('wallet-available')).toBeVisible()
  expect(await page.getByTestId('wallet-soon').count()).toBe(onMe)
})

test('the old menu is gone, and each of its entries has a place', async ({ page, request }) => {
  const me = await takerInJapan(request, 'Menu')
  await page.setViewportSize({ width: 390, height: 844 })
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()
  await expect(page.getByTestId('app-menu')).toHaveCount(0)
  // Scanning: the floating button. Referrals: "What I have". Appearance: App settings. Signing out: the drawer, and
  // the bottom of "Me".
  await openTakerArea(page, 'me')
  await page.getByTestId('account-menu-referrals').click()
  await expect(page).toHaveURL(/\/taker\/referrals$/)
  await openTakerArea(page, 'me')
  await page.getByTestId('account-menu-preferences').click()
  await page.getByTestId('appearance-dark').click()
  await expect(page.getByTestId('appearance-dark')).toHaveAttribute('aria-pressed', 'true')
  await page.getByTestId('appearance-light').click()
  await openTakerArea(page, 'me')
  await page.getByTestId('sign-out').click()
  await expect(page).toHaveURL(/\/login$/)
})
