import type { Page } from '@playwright/test'
import { expect, prepareQuizbuks, signIn, test } from './support'
import { takerInJapan } from './wallet'

/*
 * The taker app measured against the timeline's layout (docs/quizbuks/plans/Q113-taker-ui-tone.md section 9, as measured
 * on x.com; the plan's section 3 items 2 and 3). Each phase of Q113 adds the rows of its own pages; Q113a-1 the heads,
 * the tabs and the scrolling (sections 9.1 and 9.1a); Q113f the entry pages
 * (section 9.4): no card, the brand's name and mark, a column of pills 46 px high, fields 62 px high with the label in
 * the box, on a phone a column 38 px in from its sides and on a wide screen 384 px wide, 36 px in, the name at 64 px
 * and the mark large beside it.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** A CSS value of an element, by the computed style. */
const style = (page: Page, selector: string, property: string) =>
  page.locator(selector).first().evaluate((element, name) => getComputedStyle(element).getPropertyValue(name), property)

/** The sign-in page at a size, in English (the providers first, then the mailed link behind a button, then the password). */
async function signInPage(page: Page, width: number, height: number) {
  await page.setViewportSize({ width, height })
  await page.goto('/taker/login')
  await expect(page.getByTestId('login-user-name')).toBeVisible()
  await page.evaluate(() => document.fonts.ready)
}

test('the entry pages on a phone: the name and the mark at the top, a column of pills 38 px in (Q113 §9.4)', async ({ page }) => {
  await signInPage(page, 390, 844)
  await expect(page.locator('[data-slot="entry-layout"]')).toHaveAttribute('data-look', 'open')
  await expect(page.locator('[data-slot="card"]')).toHaveCount(0)

  const column = (await page.locator('[data-slot="entry-form"]').boundingBox())!
  expect(column.width, 'the column is at most 384 px wide').toBeLessThanOrEqual(384)
  expect(await style(page, '[data-slot="entry-form"]', 'padding-left')).toBe('38px')
  expect(await style(page, '[data-slot="entry-form"]', 'padding-right')).toBe('38px')

  // The name at 20 px bold with the small mark beside it; the large mark is a wide screen's.
  expect(await style(page, '[data-slot="entry-brand"]', 'font-size')).toBe('20px')
  await expect(page.locator('[data-slot="entry-form"] [data-testid="brand-mark"]')).toBeVisible()
  await expect(page.locator('[data-slot="entry-mark"]')).toBeHidden()

  // Every full-width button is a pill 46 px high across the column's content.
  const pills = page.locator('[data-slot="entry-form"] [data-slot="button"].w-full')
  expect(await pills.count()).toBeGreaterThan(0)
  for (const pill of await pills.all()) {
    if (!(await pill.isVisible())) continue
    const box = (await pill.boundingBox())!
    expect(box.height, 'a pill is 46 px high').toBeCloseTo(46, 0)
    expect(box.width, 'a pill spans the column').toBeCloseTo(column.width - 2 * 38, 0)
    expect(await pill.evaluate((b) => b.scrollWidth <= b.clientWidth), `"${await pill.textContent()}" fits its pill`).toBe(true)
    expect(parseFloat(await pill.evaluate((b) => getComputedStyle(b).borderTopLeftRadius)), 'round ends')
      .toBeGreaterThanOrEqual(box.height / 2)
  }
  // The address: 62 px high, corners of 6 px, its label inside the box.
  const field = page.getByTestId('login-user-name')
  expect((await field.boundingBox())!.height).toBeCloseTo(62, 0)
  expect(await field.evaluate((input) => getComputedStyle(input).borderTopLeftRadius)).toBe('6px')
  await expect(field).toHaveAccessibleName('E-mail address')
  await expect(page.locator('[data-slot="floating-input"]').filter({ has: field }).locator('label')).toBeVisible()

  // Nothing wider than the smallest phone the spec names.
  await page.setViewportSize({ width: 360, height: 780 })
  for (const pill of await pills.all()) {
    if (await pill.isVisible()) {
      expect(await pill.evaluate((b) => b.scrollWidth <= b.clientWidth), `"${await pill.textContent()}" fits at 360 px`).toBe(true)
    }
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth))
    .toBeLessThanOrEqual(0)
})

test('the entry pages on a wide screen: a column 384 px wide 36 px in, the name at 64 px, the mark large (Q113 §9.4)', async ({ page }) => {
  await signInPage(page, 1280, 900)
  const column = (await page.locator('[data-slot="entry-form"]').boundingBox())!
  expect(column.x, 'the column is 36 px in').toBeCloseTo(36, 0)
  expect(column.width, 'the column is 384 px wide').toBeCloseTo(384, 0)
  expect(await style(page, '[data-slot="entry-brand"]', 'font-size')).toBe('64px')
  expect(await style(page, '[data-slot="entry-brand"]', 'font-weight')).toBe('600')
  // The large mark beside the column, the small one gone.
  await expect(page.locator('[data-slot="entry-mark"]')).toBeVisible()
  await expect(page.locator('[data-slot="entry-form"] [data-testid="brand-mark"]')).toBeHidden()
  const mark = (await page.locator('[data-slot="entry-mark"]').boundingBox())!
  expect(mark.x, 'the mark is to the right of the column').toBeGreaterThan(column.x + column.width)
  for (const pill of await page.locator('[data-slot="entry-form"] [data-slot="button"].w-full').all()) {
    if (await pill.isVisible()) expect((await pill.boundingBox())!.height).toBeCloseTo(46, 0)
  }
  // The footer's links in small grey type.
  expect(await style(page, '[data-slot="entry-footer"]', 'font-size')).toBe('11px')
})

/** The translation of an element on the y axis (its computed transform's last number), once its transition ended. */
const shiftOf = (page: Page, selector: string) =>
  page.locator(selector).first().evaluate((element) => new DOMMatrix(getComputedStyle(element).transform).m42)

/**
 * Makes the page long, as a full history is: 3000 px more of the page's own column, the one its head sticks in (a
 * sticky head stays only within its column, so a longer <body> would take it away with a short column).
 */
const lengthen = (page: Page) => page.locator('[data-slot="x-head"]').first().evaluate((head) => {
  const more = document.createElement('div')
  more.style.height = '3000px'
  head.parentElement!.append(more)
})

/** The bottom menu's opacity. */
const barOpacity = (page: Page) =>
  page.locator('[data-slot="bottom-bar"]').evaluate((bar) => Number(getComputedStyle(bar).opacity))

test.describe('the heads and the tabs (Q113 §9.1)', () => {
  test('a first-level page, a page under another and the home on a phone', async ({ page, request }) => {
    const me = await takerInJapan(request, 'Heads')
    await page.setViewportSize({ width: 390, height: 844 })
    await signIn(page, '/taker/', me.email)
    await expect(page.getByTestId('home')).toBeVisible()

    // The home: a head of 53 px, the avatar of 32 px 16 px in, the mark in the middle.
    const home = (await page.getByTestId('header').boundingBox())!
    expect(home.height, 'the head is 53 px and its border').toBeCloseTo(54, 0)
    const avatar = (await page.getByTestId('head-avatar').boundingBox())!
    expect(avatar.width).toBeCloseTo(32, 0)
    expect(avatar.x, 'the avatar is 16 px in').toBeCloseTo(16, 0)
    const mark = (await page.getByTestId('head-mark').locator('svg').boundingBox())!
    expect(Math.abs(mark.x + mark.width / 2 - 390 / 2), 'the mark is in the middle').toBeLessThanOrEqual(1)

    // A first-level page with tabs: the head, then the tabs of 53 px with the bar as wide as the words.
    await page.goto('/taker/history')
    const head = page.getByTestId('area-header')
    await expect(head.locator('[data-head="top"]')).toBeVisible()
    expect((await head.boundingBox())!.height, 'two rows of 53 px and the border').toBeCloseTo(107, 0)
    expect(await style(page, '[data-slot="page-title"]', 'font-size')).toBe('20px')
    expect(await style(page, '[data-slot="page-title"]', 'font-weight')).toBe('700')
    expect((await page.locator('[data-slot="page-title"]').boundingBox())!.x, 'the title is 72 px in').toBeCloseTo(72, 0)
    const tab = page.locator('[data-slot="page-tab"][aria-current="page"]').first()
    expect((await tab.boundingBox())!.height).toBeCloseTo(53, 0)
    const bar = (await page.locator('[data-slot="page-tab-indicator"]').boundingBox())!
    expect(bar.height, 'the bar is 4 px').toBeCloseTo(4, 0)
    const words = (await tab.locator('[data-slot="page-tab-label"]').boundingBox())!
    expect(bar.width, 'as wide as the words, 56 px at least').toBeCloseTo(Math.max(56, words.width), 0)

    // A page under another: the way back, 36 px, 8 px in, then the title.
    await page.goto('/taker/account/profile')
    const back = (await page.getByTestId('header-back').boundingBox())!
    expect(back.width).toBeCloseTo(36, 0)
    expect(back.height).toBeCloseTo(36, 0)
    expect(back.x, 'the way back is 8 px in').toBeCloseTo(8, 0)
    // The title starts 72 px in, as on a first-level page (the prototype's alignment).
    expect((await page.locator('[data-slot="page-title"]').boundingBox())!.x).toBeCloseTo(72, 0)
    await expect(page.getByTestId('area-header').locator('[data-head="sub"]')).toBeVisible()
    // No round badge of the area before the title any more (P4).
    await expect(page.getByTestId('title-badge')).toHaveCount(0)
  })

  test('the heads stick on a wide screen and do not leave with the scrolling', async ({ page, request }) => {
    const me = await takerInJapan(request, 'Wide')
    await page.setViewportSize({ width: 1280, height: 900 })
    await signIn(page, '/taker/', me.email)
    await page.goto('/taker/history')
    await expect(page.getByTestId('area-header')).toBeVisible()
    await lengthen(page)
    await page.evaluate(() => window.scrollTo(0, 600))
    await page.waitForTimeout(500)
    expect(await shiftOf(page, '[data-slot="x-head"]')).toBe(0)
    expect((await page.getByTestId('area-header').boundingBox())!.y).toBeCloseTo(0, 0)
  })
})

test('scrolling on a phone: the head follows down after 20 px, only a quick swipe up brings it back (Q113 §9.1a)', async ({
  page, request,
}) => {
  const me = await takerInJapan(request, 'Scroll')
  await page.setViewportSize({ width: 390, height: 844 })
  await signIn(page, '/taker/', me.email)
  await page.goto('/taker/history')
  await expect(page.getByTestId('area-header')).toBeVisible()
  // A long page: the history of a new taker is short.
  await lengthen(page)
  const head = '[data-slot="x-head"]'
  const to = async (y: number) => {
    await page.evaluate((top) => window.scrollTo(0, top), y)
    await page.waitForTimeout(400)
  }

  await to(60)
  await expect.poll(() => shiftOf(page, head), { message: '20 px of slack, then one to one' }).toBeCloseTo(-40, 0)
  await expect.poll(() => barOpacity(page)).toBeCloseTo(1 - 0.7 * 40 / 106, 2)

  await to(1000)
  await expect.poll(() => shiftOf(page, head), { message: 'out: the head and its tabs' }).toBeCloseTo(-106, 0)
  await expect.poll(() => barOpacity(page)).toBeCloseTo(0.3, 2)
  // Faded, the bottom menu is still there to tap.
  await expect(page.getByTestId('bottom-bar-home')).toBeVisible()

  // Five slow moves up of 22 px: nothing comes back.
  for (const y of [978, 956, 934, 912, 890]) await to(y)
  expect(await shiftOf(page, head)).toBeCloseTo(-106, 0)

  // One move up of 60 px: everything is back.
  await to(830)
  await expect.poll(() => shiftOf(page, head)).toBeCloseTo(0, 0)
  await expect.poll(() => barOpacity(page)).toBeCloseTo(1, 2)

  // Down again, then the top: everything is back too.
  await to(2000)
  await expect.poll(() => shiftOf(page, head)).toBeCloseTo(-106, 0)
  await to(0)
  await expect.poll(() => shiftOf(page, head)).toBeCloseTo(0, 0)
})

test.describe('the navigation (Q113 §9.1, §9.2)', () => {
  test('a phone: the bottom menu of four icons, the floating scan button, the drawer of 280 px', async ({ page, request }) => {
    const me = await takerInJapan(request, 'Nav')
    await page.setViewportSize({ width: 390, height: 844 })
    await signIn(page, '/taker/', me.email)
    await expect(page.getByTestId('home')).toBeVisible()

    // The bar: 53 px over a divider of 1, four entries of equal width, their icons 26 px, no names shown.
    const bar = page.locator('[data-slot="bottom-bar"]')
    const barBox = (await bar.locator('ul').boundingBox())!
    expect(barBox.height, 'the bar is 53 px').toBeCloseTo(53, 0)
    const entries = bar.getByRole('link')
    await expect(entries).toHaveCount(4)
    const widths = await entries.evaluateAll((links) => links.map((link) => link.getBoundingClientRect().width))
    for (const width of widths) expect(width, 'the entries share the width').toBeCloseTo(390 / 4, 0)
    const icon = (await page.getByTestId('bottom-bar-home').locator('svg').boundingBox())!
    expect(icon.width, 'the icon is 26 px').toBeCloseTo(26, 0)
    await expect(page.getByTestId('bottom-bar-home').getByText('Home')).toHaveClass(/sr-only/)

    // The floating button: 56 px round, 20 px from the side and from the bar.
    const fab = (await page.getByTestId('page-fab').boundingBox())!
    expect(fab.width).toBeCloseTo(56, 0)
    expect(fab.height).toBeCloseTo(56, 0)
    expect(390 - (fab.x + fab.width), '20 px from the side').toBeCloseTo(20, 0)
    const barTop = (await bar.boundingBox())!.y
    expect(barTop - (fab.y + fab.height), '20 px above the bar').toBeCloseTo(20, 0)

    // The drawer: from the left, 280 px, its rows 20 px bold.
    await page.getByTestId('head-avatar').click()
    const drawer = page.getByTestId('drawer')
    await expect(drawer).toBeVisible()
    await page.waitForTimeout(600)
    const box = (await drawer.boundingBox())!
    expect(box.x, 'from the left').toBeCloseTo(0, 0)
    expect(box.width, 'the drawer is 280 px').toBeCloseTo(280, 0)
    expect(await style(page, '[data-testid="drawer-me"]', 'font-size')).toBe('20px')
    expect(await style(page, '[data-testid="drawer-me"]', 'font-weight')).toBe('700')
    await page.getByTestId('drawer-me').click()
    await expect(page).toHaveURL(/\/taker\/account$/)
    await expect(drawer).toBeHidden()
  })

  test('a wide screen: the left column of 275 px, the page of 600, the right one of 350 at 30', async ({ page, request }) => {
    const me = await takerInJapan(request, 'Columns')
    await page.setViewportSize({ width: 1280, height: 900 })
    await signIn(page, '/taker/', me.email)
    await page.goto('/taker/history')
    await expect(page.getByTestId('area-header')).toBeVisible()

    const rail = (await page.getByTestId('side-rail').boundingBox())!
    const column = (await page.getByTestId('x-column').boundingBox())!
    const aside = (await page.getByTestId('side-aside').boundingBox())!
    expect(rail.width, 'the left column').toBeCloseTo(275, 0)
    expect(column.width, 'the middle column (its two dividers in)').toBeCloseTo(600, 0)
    expect(aside.width, 'the right column').toBeCloseTo(350, 0)
    expect(column.x, 'the page beside the left column').toBeCloseTo(rail.x + rail.width, 0)
    expect(aside.x - (column.x + column.width), '30 px between the page and the right column').toBeCloseTo(30, 0)
    expect(Math.abs(rail.x - (1280 - (aside.x + aside.width))), 'the three in the middle').toBeLessThanOrEqual(1)

    // The rows: 58 px, a pill of 50, the icon 26, the name 20 px; the current one bold.
    const row = (await page.getByTestId('side-rail-history').locator('..').boundingBox())!
    expect(row.height, 'a row is 58 px').toBeCloseTo(58, 0)
    expect((await page.getByTestId('side-rail-history').boundingBox())!.height, 'its pill 50').toBeCloseTo(50, 0)
    expect((await page.getByTestId('side-rail-history').locator('svg').boundingBox())!.width).toBeCloseTo(26, 0)
    expect(await style(page, '[data-testid="side-rail-history"]', 'font-weight')).toBe('700')
    expect(await style(page, '[data-testid="side-rail-home"]', 'font-weight')).toBe('400')
    // The main button: scanning, 233 × 52, near black, 17 px bold.
    const scan = (await page.getByTestId('side-rail-scan').boundingBox())!
    expect(scan.width).toBeCloseTo(233, 0)
    expect(scan.height).toBeCloseTo(52, 0)
    expect(await style(page, '[data-testid="side-rail-scan"]', 'font-size')).toBe('17px')

    // A middle width: the left column of icons alone, no right column.
    await page.setViewportSize({ width: 1000, height: 900 })
    await expect(page.getByTestId('side-aside')).toBeHidden()
    await expect(page.getByTestId('side-rail-history').getByText('Answered')).toBeHidden()
    expect((await page.getByTestId('x-column').boundingBox())!.width).toBeCloseTo(600, 0)
  })
})
