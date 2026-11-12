import { expect, type Page } from '@playwright/test'
import { expectAccessible, setAppearance } from '@jabiz/ui/testing/playwright'
import { ADMIN, dataRows, signIn, test } from './support'

/*
 * A dialog longer than the screen keeps its buttons reachable (ROADMAP 17zz19): `DialogContent` is at most the
 * viewport's height less 2rem and scrolls what is longer. Seen in an application's end-to-end test: without the cap a
 * long dialog on a phone pushed its confirm button below the screen, where it could not be scrolled to (the dialog is
 * fixed, the page behind it does not scroll).
 */

const SMALL = { width: 320, height: 420 }

/** The dialog that ends a user's sessions, with a reason of many lines: the textarea grows with its content. */
async function longDialog(page: Page) {
  await page.goto('/security/sessions')
  await page.getByTestId('session-user-select').click()
  await page.keyboard.type(ADMIN.userName)
  await page.getByRole('listbox').getByRole('option', { name: new RegExp(`^${ADMIN.userName}\\b`) }).first().click()
  await expect(dataRows(page).first()).toBeVisible()
  await page.getByTestId('admin-session-revoke-all').click()
  const reason = page.getByTestId('admin-session-reason')
  await expect(reason).toBeVisible()
  await reason.fill(Array.from({ length: 30 }, (_, i) => `line ${i + 1}`).join('\n'))
  return page.getByRole('dialog')
}

for (const appearance of ['light', 'dark'] as const) {
  test(`a dialog taller than a small screen scrolls to its buttons (${appearance})`, async ({ page }) => {
    await setAppearance(page, appearance)
    await signIn(page)
    await page.setViewportSize(SMALL)
    const dialog = await longDialog(page)

    // Both buttons can be scrolled to within the dialog. The confirm button is not pressed: it would end the shared
    // administrator's sessions.
    const confirm = page.getByTestId('admin-session-confirm')
    await confirm.scrollIntoViewIfNeeded()
    await expect(confirm).toBeInViewport({ ratio: 1 })
    const cancel = dialog.getByRole('button', { name: '取消' })
    await cancel.scrollIntoViewIfNeeded()
    await expect(cancel).toBeInViewport({ ratio: 1 })

    // The content is longer than the screen, and the box stays on it.
    const size = await dialog.evaluate((element) => ({ scroll: element.scrollHeight, client: element.clientHeight }))
    expect(size.scroll).toBeGreaterThan(SMALL.height)
    expect(size.client).toBeLessThan(size.scroll)
    const box = await dialog.boundingBox()
    expect(box).not.toBeNull()
    expect(box!.y).toBeGreaterThanOrEqual(0)
    expect(box!.y + box!.height).toBeLessThanOrEqual(SMALL.height)

    await expectAccessible(page, `long dialog on a small screen (${appearance})`, { expect })

    await cancel.click()
    await expect(dialog).toHaveCount(0)
  })
}
