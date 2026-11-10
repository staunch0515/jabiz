import { readFileSync } from 'node:fs'
import { expect, expectAccessible, openArea, prepareQuizbuks, signIn, signOutSponsor, test } from './support'
import { approvedSponsor } from './wallet'

/*
 * F-F3 (docs/quizbuks/plans/impl/F-F3.md): the Sponsor app's home and eight areas. Every old address of the one list
 * (`sponsor/src/shell/legacy-paths.json`, the same list the unit test reads) leads to its new place, with its query
 * where the entry keeps it; the phone's bottom menu, "+ New" and the avatar menu work; the sponsored benefits are no
 * menu entry without a benefit role.
 */

interface LegacyRule {
  from: string
  example: string
  expect: string
}

const LEGACY: LegacyRule[] = JSON.parse(readFileSync(new URL('../../sponsor/src/shell/legacy-paths.json',
  import.meta.url), 'utf8'))

test.beforeAll(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('every old address of the Sponsor app leads to its new place', async ({ page, request }) => {
  test.setTimeout(180_000)
  expect(LEGACY.length).toBeGreaterThan(10)
  const sponsor = await approvedSponsor(request)
  await signIn(page, '/sponsor/', sponsor.email)
  await expect(page).toHaveURL(/\/sponsor\/home$/)
  for (const rule of LEGACY) {
    await page.goto(`/sponsor${rule.example}`)
    await expect(page, rule.example).toHaveURL(new URL(`/sponsor${rule.expect}`, page.url()).href)
  }
})

test('the menu of areas, the bottom menu on a phone, "+ New" and the avatar menu', async ({ page, request }) => {
  test.setTimeout(120_000)
  const sponsor = await approvedSponsor(request)
  await page.setViewportSize({ width: 1280, height: 900 })
  await signIn(page, '/sponsor/', sponsor.email)
  await expect(page).toHaveURL(/\/sponsor\/home$/)
  // The home and the areas; the sponsored benefits only for a holder of a benefit role.
  for (const area of ['home', 'quizzes', 'publications', 'prizes', 'messages', 'money', 'assets', 'hub']) {
    await expect(page.getByTestId(`menu-${area}`), area).toBeVisible()
  }
  await expect(page.getByTestId('menu-benefits')).toHaveCount(0)
  // A tab is an address of its own: opening it marks it current, and the main button sits beside the title.
  await openArea(page, 'money', 'bills')
  await expect(page).toHaveURL(/\/sponsor\/money\/bills$/)
  await expect(page.getByTestId('page-tab-bills')).toHaveAttribute('aria-current', 'page')

  // "+ New" from any page: a new quiz opens the quiz list's dialog.
  await page.getByTestId('global-create').click()
  await page.getByTestId('global-create-quiz').click()
  await expect(page).toHaveURL(/\/sponsor\/quizzes\?new=1$/)
  await expect(page.getByTestId('new-quiz-dialog')).toBeVisible()
  await page.keyboard.press('Escape')
  // ... and importing a publication's settings asks for the file within the same click.
  const chooser = page.waitForEvent('filechooser')
  await page.getByTestId('global-create').click()
  await page.getByTestId('global-create-import-settings').click()
  await chooser

  // A phone: the bottom menu of the parameter's areas (the declared value) and "More" with the whole menu.
  await page.setViewportSize({ width: 360, height: 780 })
  for (const area of ['home', 'publications', 'prizes', 'messages']) {
    await expect(page.getByTestId(`bottom-bar-${area}`), area).toBeVisible()
  }
  await page.getByTestId('bottom-bar-prizes').click()
  await expect(page).toHaveURL(/\/sponsor\/prizes\/redeem$/)
  await expect(page.getByTestId('bottom-bar-prizes')).toHaveAttribute('aria-current', 'page')
  await page.getByTestId('bottom-bar-more').click()
  await expect(page.getByTestId('bottom-bar-sheet').getByTestId('menu-hub')).toBeVisible()
  await expectAccessible(page, 'sponsor bottom menu more')
  await page.getByTestId('bottom-bar-sheet').getByTestId('menu-hub').getByRole('link').click()
  await expect(page).toHaveURL(/\/sponsor\/profile$/)
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth))
    .toBeLessThanOrEqual(0)

  // The avatar menu: the account, then signing out.
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.getByTestId('avatar-menu-open').click()
  await page.getByTestId('avatar-account').click()
  await expect(page).toHaveURL(/\/sponsor\/account$/)
  await signOutSponsor(page)
  await expect(page).toHaveURL(/\/sponsor\/login$/)
})
