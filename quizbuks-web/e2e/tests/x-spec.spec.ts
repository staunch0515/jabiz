import type { Page } from '@playwright/test'
import { expect, prepareQuizbuks, test } from './support'

/*
 * The taker app measured against the timeline's layout (docs/quizbuks/plans/Q113-taker-ui-tone.md section 9, as measured
 * on x.com; the plan's section 3 item 2). Each phase of Q113 adds the rows of its own pages; Q113f the entry pages
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
