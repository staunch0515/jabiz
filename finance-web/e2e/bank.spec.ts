import { expect, test, type Page } from '@playwright/test'
import { ACCOUNTANT, CONTROLLER, prepareBank, preparePayables, signIn } from './books'

/**
 * The bank in the browser (ROADMAP F5d): on a bank account of its own with January's statement imported, the
 * accountant accepts the proposed matches, makes the service fee into its entry, undoes a match and makes it again by
 * hand (FIN-BK-004…006); prepares the January reconciliation, whose difference is zero, and completes it; the
 * controller signs it off on its page and issues it, and its archived report is downloaded (FIN-BK-007, 008).
 */
let bankCode: string

/** Another person at the same browser: the session is dropped before the next signs in. */
async function signOut(page: Page) {
  await page.context().clearCookies()
  await page.evaluate(() => {
    localStorage.clear()
    sessionStorage.clear()
  })
}

test.beforeEach(async ({ request }) => {
  bankCode = await prepareBank(request, await preparePayables(request))
})

test('a statement is matched, reconciled, signed off and issued', async ({ page }) => {
  await signIn(page, ACCOUNTANT)

  await test.step('accept the proposed matches', async () => {
    await page.locator('.ant-menu-submenu-title').filter({ hasText: 'Cash and bank' }).click()
    await page.getByRole('link', { name: 'Bank matching' }).click()
    await expect(page.getByTestId('page-title')).toHaveText('Bank matching')
    await page.goto(`/bank/matching?bank=${bankCode}`)
    const proposals = page.getByTestId('proposal-table')
    await expect(proposals.locator('.ant-table-row')).toHaveCount(2)
    await expect(proposals).toContainText('amount equal')
    await page.getByTestId('accept-proposals').click()
    await expect(page.getByText('2 matches accepted')).toBeVisible()
    await expect(proposals).toContainText('Nothing to propose.')
  })

  await test.step('make the service fee into its entry', async () => {
    await page.getByTestId(`make-entry-${bankCode}-3`).click()
    await expect(page.getByText(/^BANK-FEE-2601(-\d+)? posted and matched$/)).toBeVisible()
    await expect(page.getByTestId('line-table')).toContainText('Every statement line is matched.')
  })

  await test.step('undo a match and make it again by hand', async () => {
    await page.getByTestId(`undo-${bankCode}-1`).click()
    await page.getByTestId('undo-reason').fill('Checking the transfer')
    await page.getByTestId('undo-confirm').click()
    await expect(page.getByText('Match undone')).toBeVisible()
    const lines = page.getByTestId('line-table')
    await lines.locator('.ant-table-row', { hasText: `${bankCode}-1` }).locator('input[type=checkbox]').check()
    await page.getByTestId('item-table').locator('.ant-table-row', { hasText: '1,200.00' })
      .locator('input[type=checkbox]').check()
    await expect(page.getByTestId('hand-totals')).toHaveText('Lines 1,200.00 · book items 1,200.00')
    await page.getByLabel('Reason', { exact: true }).fill('Transfer confirmed')
    await page.getByTestId('match-by-hand').click()
    await expect(lines).toContainText('Every statement line is matched.')
    const history = page.getByTestId('history-table')
    await expect(history).toContainText('Checking the transfer')
    await expect(history).toContainText('Transfer confirmed')
  })

  let recUrl = ''
  await test.step('prepare the January reconciliation and complete it', async () => {
    await page.getByRole('link', { name: 'Bank reconciliations' }).click()
    // Searched for: every run adds a bank account, and the list shows only some at a time.
    await page.getByTestId('prepare-bank').click()
    await page.keyboard.type(bankCode)
    await page.getByTitle(bankCode, { exact: true }).click()
    await page.getByTestId('prepare-day').click()
    await page.locator('.ant-select-item-option', { hasText: '885.00' }).click()
    await page.getByTestId('prepare').click()
    await expect(page.getByTestId('rec-page')).toBeVisible()
    await expect(page.getByTestId('rec-adjusted')).toHaveText('885.00')
    await expect(page.getByTestId('rec-book')).toHaveText('885.00')
    await expect(page.getByTestId('rec-difference')).toHaveText('0.00')
    recUrl = page.url()
    await page.getByTestId('rec-complete').click()
    await page.getByTestId('rec-complete-confirm').click()
    await expect(page.getByTestId('rec-status')).toHaveText('Waiting for review')
  })

  await test.step('the controller signs it off and issues it', async () => {
    await signOut(page)
    await signIn(page, CONTROLLER)
    await page.goto(recUrl)
    await page.getByTestId('approval-approve').click()
    await expect(page.getByTestId('rec-status')).toHaveText('Signed off')
    await page.getByTestId('rec-issue').click()
    await expect(page.getByTestId('rec-report')).toBeVisible()
    const download = page.waitForEvent('download')
    await page.getByTestId('rec-pdf').click()
    expect((await download).suggestedFilename()).toMatch(/\.pdf$/)
  })
})
