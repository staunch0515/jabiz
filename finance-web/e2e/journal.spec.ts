import { expect, test } from '@playwright/test'
import { ACCOUNTANT, CONTROLLER, pasteInto, prepareBooks, signIn, unique } from './books'

/**
 * The journal entry grid in the browser (FIN-GL-020 acceptance 1, FIN-UI-002/003, FIN-SCN-02 step 2): a 50-line
 * journal pasted from a spreadsheet shows its bad cells and the difference until it balances; submitted, it waits for
 * the controller, who approves it, and it posts.
 */
test.beforeEach(async ({ request }) => {
  await prepareBooks(request)
})

test('a 50-line journal pasted from a spreadsheet is corrected, submitted, approved and posted', async ({ browser }) => {
  const accountant = await browser.newPage()
  await signIn(accountant, ACCOUNTANT)
  await accountant.getByTestId('new-journal').click()
  await expect(accountant).toHaveURL(/\/gl\/journals\/new$/)

  const description = unique('Quarterly professional fees')
  await accountant.getByLabel('Posting date', { exact: true }).fill('2026-01-31')
  await accountant.getByLabel('Description', { exact: true }).fill(description)

  const rows = ['Account\tDebit\tCredit\tMemo']
  for (let i = 1; i <= 49; i++) rows.push(`${i === 10 ? '9999' : '6400'}\t${(i * 100).toLocaleString('en-US')}.00\t\tFee ${i}`)
  rows.push('2100\t\t122,499.99\tAccrued fees')
  await pasteInto(accountant, 'Account, line 1', rows.join('\r\n'))

  await expect(accountant.locator('[data-testid^="grid-row-"]')).toHaveCount(50)
  await expect(accountant.getByLabel('Account, line 10', { exact: true })).toHaveAttribute('aria-invalid', 'true')
  await expect(accountant.getByTestId('grid-check-9')).toHaveText('There is no account 9999.')
  await expect(accountant.getByTestId('total-difference')).toHaveText('Out of balance by 0.01')

  await accountant.getByLabel('Account, line 10', { exact: true }).fill('6400')
  await accountant.getByLabel('Credit, line 50', { exact: true }).fill('122500')
  await accountant.getByLabel('Credit, line 50', { exact: true }).press('Tab')
  await expect(accountant.getByLabel('Credit, line 50', { exact: true })).toHaveValue('122500.00')
  await expect(accountant.getByLabel('Account, line 10', { exact: true })).not.toHaveAttribute('aria-invalid')
  await expect(accountant.getByTestId('total-difference')).toHaveText('Balanced')
  await expect(accountant.getByTestId('total-debit')).toHaveText('122,500.00')

  await accountant.getByLabel('Memo, line 1', { exact: true }).press('Control+Enter')
  await expect(accountant.getByTestId('journal-status')).toHaveText('Waiting for approval')
  await expect(accountant.getByTestId('page-title')).toHaveText(/Journal entry JE-\d{4}/)
  const entryUrl = accountant.url()
  // The accountant holds no approval rights: nothing to decide here.
  await expect(accountant.getByTestId(/approval-panel-/)).toHaveCount(0)

  const controller = await browser.newPage()
  await signIn(controller, CONTROLLER)
  await controller.goto(entryUrl)
  await expect(controller.getByTestId('journal-status')).toHaveText('Waiting for approval')
  await controller.getByTestId('approval-approve').click()
  await expect(controller.getByTestId('journal-status')).toHaveText('Posted', { timeout: 60_000 })
  await expect(controller.getByTestId('journal-gl-no')).toHaveText(/GJ-MAN-2026-\d{6}/)

  // The register lists it with its amount.
  await accountant.goto('/gl/journals')
  const row = accountant.getByTestId('journal-table').locator('tr', { hasText: description })
  await expect(row).toContainText('122,500.00')
  await expect(row).toContainText('Posted')
})

test('a small entry is made, saved and submitted by keyboard alone, and posts at once', async ({ page }) => {
  await signIn(page, ACCOUNTANT)
  await page.goto('/gl/journals/new')
  const description = unique('Software licenses')
  await page.getByLabel('Posting date', { exact: true }).fill('2026-01-30')
  await page.getByLabel('Description', { exact: true }).fill(description)

  await page.getByLabel('Account, line 1', { exact: true }).focus()
  await page.keyboard.type('6500')
  await page.keyboard.press('Tab')
  await page.keyboard.type('450')
  await page.keyboard.press('Tab')
  await page.keyboard.press('Tab')
  await page.keyboard.type('Annual licenses')
  await page.keyboard.press('Enter')
  await page.keyboard.press('ArrowUp')
  await expect(page.getByLabel('Memo, line 1', { exact: true })).toBeFocused()
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Control+d')
  await expect(page.getByLabel('Memo, line 2', { exact: true })).toHaveValue('Annual licenses')
  await page.keyboard.press('Shift+Tab')
  await page.keyboard.type('450')
  await page.keyboard.press('Shift+Tab')
  await page.keyboard.press('Shift+Tab')
  await page.keyboard.type('2100')
  await expect(page.getByTestId('total-difference')).toHaveText('Balanced')

  await page.keyboard.press('Control+s')
  await expect(page).toHaveURL(/\/gl\/journals\/[0-9a-f-]{36}$/)
  await expect(page.getByTestId('journal-status')).toHaveText('Draft')
  await page.keyboard.press('Control+Enter')
  await expect(page.getByTestId('journal-status')).toHaveText('Posted')
  await expect(page.getByTestId('journal-gl-no')).toHaveText(/GJ-MAN-2026-\d{6}/)
  await expect(page.getByLabel('Account, line 1', { exact: true })).toHaveAttribute('readonly', '')
})
