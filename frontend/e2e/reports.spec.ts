import { expect } from '@playwright/test'
import { signIn, test } from './support'

/** ROADMAP phase 14d-1: reports are listed and run from a form generated from their parameters. */
test('a report runs from the reports page', async ({ page }) => {
  await signIn(page)
  await page.getByRole('link', { name: '报表' }).first().click()
  await expect(page).toHaveURL(/\/reports$/)

  // Without required parameters a report runs at once.
  await page.getByTestId('report-commerce.stock_availability').click()
  await expect(page.getByTestId('page-title')).toHaveText('库存可用量')
  await expect(page.getByRole('columnheader', { name: '可用' })).toBeVisible()

  // The trial balance needs its date first.
  await page.goto('/reports/run?id=jabiz.ledger.account_balances')
  await expect(page.getByTestId('report-not-run')).toBeVisible()
  await page.getByRole('button', { name: /运\s*行/ }).click()
  await expect(page.getByText('asOf: REQUIRED')).toBeVisible()
  await page.getByLabel('asOf').fill('2099-12-31 23:59:59')
  await page.getByLabel('asOf').press('Enter')
  await page.getByRole('button', { name: /运\s*行/ }).click()
  await expect(page.getByRole('columnheader', { name: '余额' })).toBeVisible()
  await expect(page.getByTestId('report-not-run')).toHaveCount(0)
})
