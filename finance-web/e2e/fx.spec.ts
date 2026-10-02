import { expect, test } from '@playwright/test'
import { ACCOUNTANT, prepareFx, signIn } from './books'

/**
 * Foreign currency in the browser (ROADMAP F7d): the accountant revalues November 2026 (FIN-FX-005) — the first time
 * the run is made, after that it is the run already made, so the test runs again on the same database — simulates the
 * month as recorded when it was run (no change), and finds the gains and losses report.
 */
test.beforeEach(async ({ request }) => {
  await prepareFx(request)
})

test('a month is revalued once, simulated as recorded and its report found', async ({ page }) => {
  await signIn(page, ACCOUNTANT)

  await test.step('revalue November', async () => {
    await page.locator('.ant-menu-submenu-title').filter({ hasText: 'Foreign currency' }).click()
    await page.getByRole('link', { name: 'Revaluations' }).click()
    await expect(page.getByTestId('page-title')).toHaveText('Foreign currency revaluations')
    await page.getByTestId('run-month').fill('2026-11')
    await page.getByTestId('run').click()
    await expect(page.getByTestId('run-result')).toContainText(/^FXR-2611 (posted|was run already)/)
    await expect(page.getByTestId('run-table')).toContainText('FXR-2611')
  })

  await test.step('simulate it as recorded', async () => {
    await page.getByTestId('simulate-month').fill('2026-11')
    await page.getByTestId('simulate-recorded').check()
    await page.getByTestId('simulate').click()
    await expect(page.getByTestId('simulate-summary')).toContainText('FXR-2611 posted')
    await expect(page.getByTestId('simulate-summary')).toContainText('a change of 0.00')
  })

  await test.step('find the gains and losses report', async () => {
    await page.getByRole('link', { name: 'Gains and losses' }).click()
    // The report's own title (the menu says "Gains and losses"), and no refusal.
    await expect(page.getByText('Exchange gains and losses').first()).toBeVisible()
    await expect(page.locator('.ant-alert-error')).toHaveCount(0)
  })
})
