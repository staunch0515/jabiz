import { expect, test } from '@playwright/test'
import { ACCOUNTANT, CONTROLLER, prepareCloseYear, run, signIn, token, unique } from './books'

/**
 * The financial statements in the browser (ROADMAP F9d; FIN-RP-006, FIN-UI-004, FIN-UI-005, FIN-RP-021). It posts a
 * professional fee of its own in May 2028, a month no other test posts in, and only reads: the controller opens the
 * income statement, drills from Professional Fees to the entries, finds the fee and opens its journal entry; the
 * statement exports to PDF; the dashboard opens the balance sheet. The tables only grow, so it runs again on the same
 * database.
 */
const DAY = '2028-05-31'

test('a statement figure drills to its entries and their documents', async ({ page, request }) => {
  await prepareCloseYear(request)
  const accountant = await token(request, ACCOUNTANT)
  const description = unique('E2E statement fee')
  const saved = await run(request, accountant, 'FIN_JOURNAL_SAVE', { postingDate: '2028-05-15', description,
    lines: [{ accountCode: '6400', debit: '123.45', memo: description }, { accountCode: '2100', credit: '123.45' }] })
  expect(saved.status, JSON.stringify(saved.body)).toBe(200)
  const submitted = await run(request, accountant, 'FIN_JOURNAL_SUBMIT', { journalId: saved.body.output.journalId })
  expect(submitted.status, JSON.stringify(submitted.body)).toBe(200)
  expect(submitted.body.output.status).toBe('POSTED')

  await signIn(page, CONTROLLER)

  await test.step('the controller runs the income statement through May 2028', async () => {
    await page.locator('.ant-menu-submenu-title').filter({ hasText: /^Financial statements$/ }).click()
    await page.getByRole('link', { name: 'Income statement' }).click()
    await expect(page.getByTestId('page-title')).toHaveText('Income statement')
    await page.getByTestId('param-through').fill(DAY)
    await page.getByTestId('run').click()
    await expect(page.getByTestId('cell-NET_INCOME-month')).toBeVisible()
    // Expenses are shown in parentheses (FIN-UI-005).
    await expect(page.getByTestId('cell-OPERATING_EXPENSES.6400-month')).toContainText(/^\(\d[\d,]*\.\d{2}\)$/)
  })

  await test.step('Professional Fees drills to the entries, the fee among them', async () => {
    await page.getByTestId('drill-OPERATING_EXPENSES.6400-month').click()
    await expect(page.getByTestId('page-title')).toHaveText('Detail of Professional Fees')
    const fee = page.getByTestId('lines').getByRole('row').filter({ hasText: description })
    await expect(fee).toContainText('123.45')
    await fee.getByRole('link').click()
    await expect(page).toHaveURL(/\/gl\/journals\/[^/]+$/)
    await page.goBack()
  })

  await test.step('the statement exports to PDF through the platform', async () => {
    await page.goto(`/statements/income-statement?through=${DAY}`)
    const download = page.waitForEvent('download')
    await page.getByTestId('export-pdf').click()
    expect((await download).suggestedFilename()).toMatch(/\.pdf$/)
  })

  await test.step('the dashboard opens the balance sheet', async () => {
    await page.goto('/finance-dashboard')
    await page.getByTestId('dashboard-day').fill(DAY)
    await page.getByTestId('dashboard-day').press('Enter')
    await expect(page.getByTestId('value-close')).toContainText('2028-05')
    await page.getByTestId('tile-cash').click()
    await expect(page).toHaveURL(new RegExp(`/statements/balance-sheet\\?asOf=${DAY}`))
    await expect(page.getByTestId('cell-TOTAL_ASSETS-amount')).toBeVisible()
  })
})
