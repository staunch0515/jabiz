import { expect, test } from '@playwright/test'
import { signIn, unique } from './support'

/** ROADMAP phase 10 requirement 6: a process form generated from the process input type. */
test('a process runs from its generated form', async ({ page }) => {
  await signIn(page)
  await page.goto('/processes')
  await page.getByTestId('process-LEDGER_ACCOUNT_OPEN-1').click()
  const code = unique('9')
  await page.getByLabel('accountCode').fill(code)
  await page.getByLabel('accountName').fill(`E2E account ${code}`)
  await page.getByLabel('accountType').fill('ASSET')
  await page.getByRole('button', { name: /执\s*行/ }).click()
  await expect(page.getByTestId('process-result')).toContainText(code)

  // A missing required value is refused before sending.
  await page.reload()
  await page.getByRole('button', { name: /执\s*行/ }).click()
  await expect(page.getByText('accountCode: REQUIRED')).toBeVisible()
})
