import { accessibleInBoth, expect, prepareQuizbuks, signIn, test } from './support'
import { takerInJapan } from './wallet'

/*
 * A taker's own avatar (Q114a, docs/quizbuks/plans/impl/Q114.md): chosen on "Edit profile", shown in a first-level
 * page's head, the drawer, "Me" and the wide screen's account block, and kept across sign-ins. Only the taker sees it.
 */

test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('a taker chooses a preset avatar and sees it in the four places, after signing in again too', async ({
  page, browser, request,
}) => {
  test.setTimeout(180_000)
  const me = await takerInJapan(request, 'Avatar')
  await page.setViewportSize({ width: 390, height: 844 })
  await signIn(page, '/taker/', me.email)
  // Nothing chosen: the initial.
  await expect(page.getByTestId('head-avatar').locator('[data-avatar="initial"]')).toHaveText('A')

  await page.goto('/taker/account/profile')
  const picker = page.getByTestId('avatar-picker')
  await expect(picker.getByTestId('avatar-current')).toHaveAttribute('data-avatar', 'initial')
  await picker.getByTestId('avatar-change').click()
  const dialog = page.getByTestId('avatar-dialog')
  const choices = dialog.getByRole('group', { name: 'Avatars' })
  await expect(choices.getByRole('button')).toHaveCount(13)
  await accessibleInBoth(page, 'avatar choices')
  await choices.getByRole('button', { name: 'Mortarboard' }).click()
  await expect(choices.getByRole('button', { name: 'Mortarboard' })).toHaveAttribute('aria-pressed', 'true')
  await expect(picker.getByTestId('avatar-current')).toHaveAttribute('data-avatar', 'p09')
  // The drawing is the site's own file, and it loads.
  const drawing = picker.getByTestId('avatar-current').locator('img')
  await expect(drawing).toHaveAttribute('src', '/taker/avatars/p09.svg')
  await expect.poll(() => drawing.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0))
    .toBe(true)
  await page.keyboard.press('Escape')

  // "Me", a first-level page's head and the drawer it opens.
  await page.goto('/taker/account')
  await expect(page.getByTestId('me-avatar')).toHaveAttribute('data-avatar', 'p09')
  await page.goto('/taker/history')
  await expect(page.getByTestId('head-avatar').locator('[data-avatar="p09"]')).toBeVisible()
  await page.getByTestId('head-avatar').click()
  await expect(page.getByRole('dialog').locator('[data-avatar="p09"]')).toBeVisible()
  await page.keyboard.press('Escape')
  // The wide screen's account block.
  await page.setViewportSize({ width: 1280, height: 900 })
  await expect(page.getByTestId('side-rail-account').locator('[data-avatar="p09"]')).toBeVisible()

  // Another browser, signed in again: the same avatar.
  const other = await browser.newContext()
  const again = await other.newPage()
  await again.setViewportSize({ width: 390, height: 844 })
  await signIn(again, '/taker/', me.email)
  await expect(again.getByTestId('head-avatar').locator('[data-avatar="p09"]')).toBeVisible()
  await other.close()

  // Back to the initial.
  await page.goto('/taker/account/profile')
  await page.getByTestId('avatar-change').click()
  await page.getByTestId('avatar-dialog').getByRole('button', { name: 'Your initial' }).click()
  await expect(page.getByTestId('avatar-current')).toHaveAttribute('data-avatar', 'initial')
})
