import { expect, test } from '@playwright/test'
import { CLERK, newCustomer, prepareReceivables, signIn } from './books'

/**
 * Receivables in the browser (ROADMAP F3d-2): the clerk enters a two-line invoice by keyboard alone and posts it
 * (FIN-UI-002), sees its tax explained (FIN-UI-007), issues its document and saves the PDF (FIN-AR-005); the
 * invoice register filtered to the customer adds up to what was posted and opens the invoice (FIN-UI-004); a receipt
 * is recorded and applied as suggested, and the invoice's open amount goes down (FIN-AR-007).
 */
test.beforeEach(async ({ request }) => {
  await prepareReceivables(request)
})

test('an invoice is keyed in, posted, explained, issued and paid', async ({ page, request }) => {
  const customer = await newCustomer(request)
  await signIn(page, CLERK)

  await test.step('enter and post the invoice by keyboard', async () => {
    await page.getByRole('menuitem', { name: /Receivables/ }).click()
    await page.getByRole('link', { name: 'New invoice' }).click()
    await expect(page.getByTestId('invoice-customer')).toBeFocused()
    await page.keyboard.type(customer)
    await expect(page.getByTestId('invoice-customer-name')).toHaveText(`Customer ${customer}`)
    await page.getByLabel('Invoice date', { exact: true }).fill('2026-01-28')

    await page.getByLabel('Description, line 1', { exact: true }).focus()
    await page.keyboard.type('Components')
    await page.keyboard.press('Tab')
    await page.keyboard.type('3')
    await page.keyboard.press('Tab')
    await page.keyboard.type('1,000')
    await page.keyboard.press('Tab')
    await page.keyboard.type('4000')
    await page.keyboard.press('Tab')
    await page.keyboard.press('Enter')
    await expect(page.getByLabel('Description, line 2', { exact: true })).toBeFocused()
    await page.keyboard.type('Engineering services')
    await page.keyboard.press('Tab')
    await page.keyboard.type('1')
    await page.keyboard.press('Tab')
    await page.keyboard.type('500')
    await page.keyboard.press('Tab')
    await page.keyboard.type('4100')
    await page.keyboard.press('Tab')
    await page.keyboard.type('NT')
    await expect(page.getByTestId('lines-subtotal')).toHaveText('3,500.00')

    await page.keyboard.press('Control+Enter')
    await expect(page.getByTestId('invoice-view')).toBeVisible()
    await expect(page.getByTestId('page-title')).toHaveText(/^Invoice INV-\d+$/)
    await expect(page.getByTestId('invoice-status')).toHaveText('Posted')
    // 8.25% of the components only: 247.50.
    await expect(page.getByTestId('invoice-tax-total')).toHaveText('247.50')
    await expect(page.getByTestId('invoice-total')).toHaveText('3,747.50')
  })
  const invoiceNo = (await page.getByTestId('page-title').textContent())!.replace('Invoice ', '')
  const invoiceUrl = page.url()

  await test.step('see how the tax was computed', async () => {
    const jurisdictions = page.getByTestId('tax-jurisdictions')
    await expect(jurisdictions).toContainText('6.25%')
    await expect(jurisdictions).toContainText('187.50')
    await expect(jurisdictions).toContainText('2%')
    await expect(jurisdictions).toContainText('60.00')
    await expect(page.getByTestId('tax-lines')).toContainText('Not taxable')
  })

  await test.step('issue the invoice and save its PDF', async () => {
    await page.getByTestId('document-issue').click()
    const issued = page.locator('[data-testid^="document-download-"]')
    await expect(issued).toHaveCount(1)
    const download = page.waitForEvent('download')
    await issued.first().click()
    expect((await download).suggestedFilename()).toBe(`${invoiceNo}.pdf`)
  })

  await test.step('find it in the register, which adds up', async () => {
    await page.getByRole('link', { name: 'Invoices', exact: true }).first().click()
    await page.getByPlaceholder('Any customer').fill(customer)
    await expect(page.getByTestId('invoice-table').locator('.ant-table-row')).toHaveCount(1)
    await expect(page.getByTestId('register-total')).toHaveText('3,747.50')
    await page.getByRole('link', { name: invoiceNo, exact: true }).click()
    await expect(page).toHaveURL(invoiceUrl)
  })

  await test.step('record a receipt applied as suggested', async () => {
    await page.goto('/receivables/receipts/new')
    await expect(page.getByTestId('receipt-customer')).toBeFocused()
    await page.keyboard.type(customer)
    await page.getByTestId('receipt-date').fill('2026-01-30')
    await page.getByTestId('receipt-amount').fill('1000')
    await page.getByTestId('apply-suggested').click()
    await expect(page.getByTestId(`apply-${invoiceNo}`)).toHaveValue('1000.00')
    await expect(page.getByTestId('left-unapplied')).toContainText('0.00')
    await page.keyboard.press('Control+Enter')
    await expect(page.getByTestId('receipt-view')).toBeVisible()
    await expect(page.getByTestId('receipt-applications')).toContainText(invoiceNo)
    await page.goto(invoiceUrl)
    await expect(page.getByTestId('invoice-open')).toHaveText('2,747.50')
  })
})
