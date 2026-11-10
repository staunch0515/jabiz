import { expect, expectAccessible, prepareQuizbuks, signIn, takerArea, test, type TakerArea } from './support'
import { takerInJapan } from './wallet'

/*
 * The taker app's frame (F-F4, docs/quizbuks/plans/impl/F-F4.md sections 2, 3 and 7): the bottom menu of five with
 * scanning raised in the middle on a phone, the left column on a wide screen, every area without a sideways scroll at
 * 360 px, every address the app had still a page of the right area, and the redirects of `LEGACY_APP_PATHS`.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

const AREAS: TakerArea[] = ['home', 'history', 'scan', 'messages', 'me']

/** The width the page is wider than the screen by (zero or less when it fits). */
const overflow = (page: import('@playwright/test').Page) =>
  page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)

test('a phone has the bottom menu of five, scanning raised in the middle, and every area fits 360 px', async ({
  page, request,
}) => {
  test.setTimeout(180_000)
  const me = await takerInJapan(request, 'Frame')
  await page.setViewportSize({ width: 360, height: 780 })
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()

  const bar = page.getByRole('navigation', { name: 'App' })
  await expect(bar.getByRole('link')).toHaveCount(5)
  await expect(page.getByTestId('side-rail')).toBeHidden()
  await expect(page.getByTestId('bottom-bar-more')).toHaveCount(0)

  // Scanning is in the middle of the screen (to a pixel), and raised above the others.
  const scan = (await page.getByTestId('bottom-bar-scan').boundingBox())!
  expect(Math.abs(scan.x + scan.width / 2 - 360 / 2), 'scanning is in the middle').toBeLessThanOrEqual(1)
  const home = (await page.getByTestId('bottom-bar-home').locator('svg').boundingBox())!
  const raised = (await page.getByTestId('bottom-bar-scan').locator('svg').boundingBox())!
  expect(raised.y, 'scanning is raised above the others').toBeLessThan(home.y)

  for (const area of AREAS) {
    await expect(page.getByTestId(`bottom-bar-${area}`)).toBeVisible()
    await page.getByTestId(`bottom-bar-${area}`).click()
    await expect(page.getByTestId(`bottom-bar-${area}`)).toHaveAttribute('aria-current', 'page')
    if (area !== 'home') await expect(page.locator('[data-slot="page-title"]').first()).toBeVisible()
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

test('a wide screen has the left column of five and no bottom menu', async ({ page, request }) => {
  test.setTimeout(180_000)
  const me = await takerInJapan(request, 'Column')
  await page.setViewportSize({ width: 1280, height: 900 })
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()

  const rail = page.getByTestId('side-rail')
  await expect(rail).toBeVisible()
  await expect(rail.getByRole('link')).toHaveCount(5)
  await expect(page.getByTestId('bottom-menu')).toBeHidden()
  await expect(page.getByTestId('bottom-bar-scan')).toBeHidden()
  // The column is at the left edge, and the page is in what it leaves.
  const railBox = (await rail.boundingBox())!
  expect(railBox.x).toBe(0)
  const homeBox = (await page.getByTestId('home').boundingBox())!
  expect(homeBox.x).toBeGreaterThanOrEqual(railBox.x + railBox.width)

  for (const area of AREAS) {
    await page.getByTestId(`side-rail-${area}`).click()
    await expect(page.getByTestId(`side-rail-${area}`)).toHaveAttribute('aria-current', 'page')
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
    if (area) await expect(takerArea(page, area), path).toHaveAttribute('aria-current', 'page')
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
  await expect(takerArea(page, 'me')).toHaveAttribute('aria-current', 'page')
})

test('"Coming soon" beside the Kudos is the same on "Me" and on the wallet', async ({ page, request }) => {
  const me = await takerInJapan(request, 'Soon')
  await page.setViewportSize({ width: 390, height: 844 })
  await signIn(page, '/taker/', me.email)
  await takerArea(page, 'me').click()
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
  // Scanning: the middle entry. Referrals: "What I have". Appearance: App settings. Signing out: the bottom of "Me".
  await takerArea(page, 'me').click()
  await page.getByTestId('account-menu-referrals').click()
  await expect(page).toHaveURL(/\/taker\/referrals$/)
  await takerArea(page, 'me').click()
  await page.getByTestId('account-menu-preferences').click()
  await page.getByTestId('appearance-dark').click()
  await expect(page.getByTestId('appearance-dark')).toHaveAttribute('aria-pressed', 'true')
  await page.getByTestId('appearance-light').click()
  await takerArea(page, 'me').click()
  await page.getByTestId('sign-out').click()
  await expect(page).toHaveURL(/\/login$/)
})
