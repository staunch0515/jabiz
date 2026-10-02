import { expect, test } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { AP_CLERK, CONTROLLER, newVendor, preparePayables, signIn, signInWithCode, type Treasurer } from './books'

/**
 * Payables in the browser (ROADMAP F4e): the payables clerk enters a five-line bill by keyboard alone and posts it
 * (FIN-UI-002 acceptance 1), finds it in the bill register, which adds up and opens it (FIN-UI-004); proposes a check
 * run that pays it and submits it; the controller approves the run on its page; the treasurer, signed in with a second
 * factor, releases it, makes the check and positive pay files and saves the positive pay file (FIN-AP-011, 013,
 * FIN-BK-011).
 */
let treasurer: Treasurer

/** Another person at the same browser: the session is dropped before the next signs in. */
async function signOut(page: import('@playwright/test').Page) {
  await page.context().clearCookies()
  await page.evaluate(() => {
    localStorage.clear()
    sessionStorage.clear()
  })
}

test.beforeEach(async ({ request }) => {
  treasurer = await preparePayables(request)
})

test('a bill is keyed in, posted, found in the register, and paid by a check run', async ({ page, request }) => {
  const vendor = await newVendor(request)
  const number = `INV-${Date.now().toString(36).toUpperCase()}`
  await signIn(page, AP_CLERK)

  await test.step('enter and post a five-line bill by keyboard alone', async () => {
    await page.getByRole('menuitem', { name: /Payables/ }).click()
    await page.getByRole('link', { name: 'New bill' }).click()
    await expect(page.getByTestId('bill-vendor')).toBeFocused()
    await page.keyboard.type(vendor)
    await expect(page.getByTestId('bill-vendor-name')).toHaveText(`Vendor ${vendor}`)
    await page.keyboard.press('Tab')
    await expect(page.getByTestId('bill-number')).toBeFocused()
    await page.keyboard.type(number)
    await page.keyboard.press('Tab')
    // The date field takes the month, day and year in turn.
    await page.keyboard.type('01202026')
    await page.getByLabel('Description, line 1', { exact: true }).focus()
    const lines = [
      ['Strategy workshop', '1500'],
      ['Market report', '1250.50'],
      ['Interviews', '900'],
      ['Travel', '650'],
      ['Expenses', '199.50'],
    ]
    for (const [i, [text, amount]] of lines.entries()) {
      await page.keyboard.type(text)
      await page.keyboard.press('Tab')
      await page.keyboard.type(amount)
      // Account, use tax, department and 1099 form and box: the vendor's defaults.
      for (let tab = 0; tab < 5; tab++) await page.keyboard.press('Tab')
      if (i < lines.length - 1) {
        await page.keyboard.press('Enter')
        await expect(page.getByLabel(`Description, line ${i + 2}`, { exact: true })).toBeFocused()
      }
    }
    await expect(page.getByTestId('lines-total')).toHaveText('4,500.00')
    await page.keyboard.press('Control+Enter')
    await expect(page.getByTestId('bill-view')).toBeVisible()
    await expect(page.getByTestId('page-title')).toHaveText(/^Bill BILL-\d+$/)
    await expect(page.getByTestId('bill-status')).toHaveText('Posted')
    await expect(page.getByTestId('bill-total')).toHaveText('4,500.00')
    await expect(page.getByTestId('bill-view-lines')).toContainText('6400')
  })
  const billNo = (await page.getByTestId('page-title').textContent())!.replace('Bill ', '')
  const billUrl = page.url()

  await test.step('find it in the register, which adds up', async () => {
    await page.getByRole('link', { name: 'Bills', exact: true }).first().click()
    await page.getByPlaceholder('Any vendor').fill(vendor)
    await expect(page.getByTestId('bill-table').locator('.ant-table-row')).toHaveCount(1)
    await expect(page.getByTestId('register-total')).toHaveText('4,500.00')
    await expect(page.getByTestId('register-open')).toHaveText('4,500.00')
    await page.getByRole('link', { name: billNo, exact: true }).click()
    await expect(page).toHaveURL(billUrl)
  })

  let runUrl = ''
  await test.step('propose a check run for the vendor and submit it', async () => {
    await page.goto('/payables/runs/new')
    await expect(page.getByTestId('run-date')).toBeFocused()
    await page.keyboard.type('01302026')
    await page.getByTestId('run-method').click()
    await page.getByTitle('Check', { exact: true }).click()
    await page.getByTestId('run-due').fill('2026-12-31')
    await page.getByTestId('run-vendors').fill(vendor)
    await page.getByTestId('run-propose').click()
    await expect(page.getByTestId('run-page')).toBeVisible()
    await expect(page.getByTestId('run-total')).toHaveText('4,500.00')
    await expect(page.getByTestId('run-lines')).toContainText(billNo)
    runUrl = page.url()
    await page.getByTestId('run-submit').click()
    await expect(page.getByTestId('run-status')).toHaveText('Waiting for approval')
  })

  await test.step('the controller approves it on its page', async () => {
    await signOut(page)
    await signIn(page, CONTROLLER)
    await page.goto(runUrl)
    await page.getByTestId('approval-approve').click()
    await expect(page.getByTestId('run-status')).toHaveText('Approved')
  })

  await test.step('the treasurer, signed in with a code, releases it and makes the files for the bank', async () => {
    await signOut(page)
    await signInWithCode(page, treasurer)
    await page.goto(runUrl)
    await page.getByTestId('run-release').click()
    await page.getByRole('button', { name: 'Release' }).last().click()
    await expect(page.getByTestId('run-status')).toHaveText('Released')
    await expect(page.getByTestId('run-payments')).toContainText(`Vendor ${vendor}`)
    const checkNo = (await page.getByTestId('run-payments').locator('.ant-table-row td').nth(3).textContent())!.trim()
    expect(checkNo).toMatch(/^\d+$/)
    await page.getByTestId('generate-CHECKS').click()
    await expect(page.getByTestId('run-files')).toContainText('-checks.csv')
    await page.getByTestId('generate-POSITIVE_PAY').click()
    await expect(page.getByTestId('run-files')).toContainText('-positive-pay.csv')
    const download = page.waitForEvent('download')
    await page.getByTestId('download-POSITIVE_PAY').click()
    const saved = await download
    expect(saved.suggestedFilename()).toMatch(/-positive-pay\.csv$/)
    const content = await readFile((await saved.path())!, 'utf8')
    expect(content).toContain(`000123456789,${checkNo},2026-01-30,4500.00,Vendor ${vendor},I`)
    await page.goto(billUrl)
    await expect(page.getByTestId('bill-open')).toHaveText('0.00')
  })
})
