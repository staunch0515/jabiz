import { expect, test } from '@playwright/test'
import { ADMIN, find, newCustomer, prepareReceivables, run, signIn, token } from './books'

/**
 * FIN-UI-008 (ROADMAP F11e): a customer changed twice (its name, then its contact address) shows both changes in its
 * history, each with the values before and after, and can be seen as it was at an earlier version.
 */
const CUSTOMERS = 'urn:jabiz:dataset:default:FinCustomer'

test('FIN-UI-008: a customer changed twice shows both versions and the changes between them', async ({ page, request }) => {
  await prepareReceivables(request)
  const code = await newCustomer(request)
  const admin = await token(request, ADMIN)
  const [customer] = await find(request, admin, CUSTOMERS, 'customerCode', code)
  const address = { street: '1 Test Way', city: 'Austin', state: 'TX', postalCode: '78701', country: 'United States' }
  const email = `${code.toLowerCase()}@customers.example.com`
  const base = { customerCode: code, currency: 'USD', termsDays: 30, taxCode: 'TX-AUSTIN', billing: address,
    shipping: address }
  for (const change of [{ legalName: `Customer ${code} LLC`, contactEmail: email },
    { legalName: `Customer ${code} LLC`, contactEmail: `ar@${code.toLowerCase()}.example.com` }]) {
    const saved = await run(request, admin, 'FIN_CUSTOMER_SAVE', { ...base, ...change })
    expect(saved.status, JSON.stringify(saved.body)).toBe(200)
  }

  await signIn(page, ADMIN)
  await page.goto(`/data/${encodeURIComponent(CUSTOMERS)}/${encodeURIComponent(customer.id)}/history`)
  const timeline = page.getByTestId('history-timeline')
  await expect(timeline.locator('[data-testid^="version-"]')).toHaveCount(3)
  // Each change in its version's table of changes: the field, the value before and after.
  const change = (version: number, field: string) =>
    page.getByTestId(`version-${version}`).locator(`tr[data-row-key="${field}"] td`)
  await expect(change(2, 'legalName').nth(1)).toHaveText(`Customer ${code}`)
  await expect(change(2, 'legalName').nth(2)).toHaveText(`Customer ${code} LLC`)
  await expect(change(3, 'contactEmail').nth(1)).toHaveText(email)
  await expect(change(3, 'contactEmail').nth(2)).toHaveText(`ar@${code.toLowerCase()}.example.com`)
  // As it was at the first version.
  await page.getByTestId('version-1').getByTestId('view-at').click()
  await expect(page.getByTestId('point-in-time').locator('[data-field="legalName"]')).toHaveText(`Customer ${code}`)
})
