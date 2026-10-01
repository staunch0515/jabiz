import { expect, test } from '@playwright/test'
import { ACCOUNTANT, prepareBooks, signIn, unique } from './books'

/**
 * Journal import in the browser (FIN-GL-019): the accountant opens the import from the finance menu, uploads a file,
 * previews it (every entry run and rolled back) and imports it; the entry posts at once under the approval rules
 * (small enough to need none) and shows in the register.
 */
test.beforeEach(async ({ request }) => {
  await prepareBooks(request)
})

test('journal entries are imported from a file through the import wizard', async ({ page }) => {
  await signIn(page, ACCOUNTANT)
  // The finance menu's group, not the platform's own Imports page, which shows once its catalog has loaded.
  await page.locator('.ant-menu-submenu-title').filter({ hasText: 'Imports' }).click()
  await page.getByRole('link', { name: 'Import journal entries' }).click()
  await expect(page.getByTestId('page-title')).toHaveText('Journal entries')

  const document = unique('IMP')
  const description = `Imported accrual ${document}`
  const csv = `document,posting_date,description,account,debit,credit\n${document},2026-01-27,${description},6400,250.00,\n${document},2026-01-27,,2100,,250.00\n`
  await page.locator('input[type=file]').setInputFiles({ name: `${document}.csv`, mimeType: 'text/csv', buffer: Buffer.from(csv) })
  await expect(page.getByTestId('import-sample')).toBeVisible()

  await page.getByTestId('import-preview').click()
  await expect(page.getByText('No problems: the file can be imported.')).toBeVisible()
  await page.getByTestId('import-commit').click()
  await page.locator('.ant-popconfirm').getByRole('button', { name: /Import/ }).click()
  await expect(page.getByText('The file was imported.').first()).toBeVisible()

  await page.goto('/gl/journals')
  const row = page.getByTestId('journal-table').locator('tr', { hasText: description })
  await expect(row).toContainText('250.00')
  await expect(row).toContainText('Posted')
})
