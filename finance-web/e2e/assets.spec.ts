import { expect, test } from '@playwright/test'
import { ACCOUNTANT, prepareAsset, reverseRuns, signIn } from './books'

/**
 * Fixed assets in the browser (ROADMAP F6c): with an asset of its own acquired in January, the accountant runs the
 * next month's depreciation (FIN-FA-005), sees the asset's month taken and its months ahead on its page (FIN-FA-009),
 * takes the run back and finds the register among the reports. Every run takes its month back, and anything a failed
 * run left posted is taken back before and after, so January is the month run however often the tests run.
 */
let description: string

test.beforeEach(async ({ request }) => {
  description = await prepareAsset(request)
})

test.afterEach(async ({ request }) => {
  await reverseRuns(request)
})

test('a month is depreciated, shown on the asset and taken back', async ({ page }) => {
  await signIn(page, ACCOUNTANT)
  let runNo = ''

  await test.step('run the next month', async () => {
    await page.locator('.ant-menu-submenu-title').filter({ hasText: 'Fixed assets' }).click()
    await page.getByRole('link', { name: 'Depreciation runs' }).click()
    await expect(page.getByTestId('page-title')).toHaveText('Depreciation runs')
    const month = page.getByTestId('run-month')
    // With every run taken back there is no posted month to follow: January, the month after the cutover.
    await expect(month).toHaveValue('')
    await month.fill('2026-01')
    await page.getByTestId('run').click()
    const result = page.getByTestId('run-result')
    await expect(result).toContainText(/^DEP-2601(-\d+)? posted: /)
    runNo = (await result.textContent())?.match(/DEP-\d{4}(-\d+)?/)?.[0] ?? ''
    await expect(page.getByTestId('run-table')).toContainText(runNo)
  })

  await test.step('see the month taken and the months ahead on the asset', async () => {
    await page.getByRole('link', { name: 'Assets', exact: true }).click()
    await page.getByLabel('Search assets').fill(description)
    await page.getByTestId('asset-table').getByRole('link').first().click()
    await expect(page.getByTestId('page-title')).toContainText(description)
    await expect(page.getByTestId('taken-table')).toContainText(runNo)
    await expect(page.getByTestId('asset-cost')).toHaveText('3,600.00')
    await expect(page.getByTestId('ahead-table').locator('.ant-table-row').first()).toContainText('100.00')
  })

  await test.step('take the run back', async () => {
    await page.getByRole('link', { name: 'Depreciation runs' }).click()
    await page.getByTestId('reverse').click()
    await page.getByTestId('reverse-reason').fill('End-to-end test')
    await page.getByTestId('reverse-confirm').click()
    await expect(page.getByTestId('run-table').locator('.ant-table-row', { hasText: runNo }))
      .toContainText('Reversed')
  })

  await test.step('find the register among the reports', async () => {
    await page.getByRole('link', { name: 'Asset register' }).click()
    await expect(page.getByText('Fixed asset register').first()).toBeVisible()
  })
})
