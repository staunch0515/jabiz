import { signInAdmin } from './admin'
import { adminToken, expect, expectAccessible, prepareQuizbuks, run, signIn, test } from './support'
import { approvedSponsor } from './wallet'

/**
 * The platform fees (docs/quizbuks/plans/Q87-fee-account.md, Q87b): finance makes a sponsor postpaid with a credit
 * (`QB_OPERATOR_BILLING_SET`), the sponsor's wallet shows how they are billed and that nothing is owed, and the
 * administration's fees page and the operator's billing page open. A fee needs the monthly job or another
 * administrator's approval, and an invoice the month's end: the backend tests (FeeIT) drive those. axe checks the
 * three pages.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('finance makes a sponsor postpaid and the sponsor sees it on the wallet', async ({ page, request }) => {
  test.setTimeout(120_000)
  const sponsor = await approvedSponsor(request)
  const me = await (await request.get('/api/auth/me', { headers: { Authorization: `Bearer ${sponsor.token}` } }))
    .json() as { userId: string }
  await run(request, await adminToken(request), 'QB_OPERATOR_BILLING_SET', { userId: me.userId,
    billingMode: 'POSTPAID', creditLimit: 30000, reason: 'End-to-end test' })

  await signIn(page, '/sponsor/', sponsor.email)
  await page.goto('/sponsor/wallet')
  const billing = page.getByTestId('wallet-billing')
  await expect(billing).toBeVisible()
  await expect(billing).toHaveAttribute('data-mode', 'POSTPAID')
  await expect(billing).toContainText('Credit limit')
  await expect(billing).toContainText('30,000 Kudos')
  await expect(page.getByTestId('wallet-unpaid')).toHaveCount(0)
  await expect(page.getByTestId('wallet-overdue')).toHaveCount(0)
  await expect(page.getByTestId('invoice-pay-early')).toHaveCount(0)
  await expectAccessible(page, 'sponsor wallet with platform fees')

  await signInAdmin(page)
  await page.goto('/admin/qb/finance/fees')
  await expect(page.getByTestId('fees-page')).toBeVisible()
  await expect(page.getByTestId('fee-new-manual')).toBeVisible()
  await page.getByTestId('tab-invoices').click()
  await expectAccessible(page, 'admin platform fees')

  // The operator's billing page lists the sponsors of the caller's operator: none for the administrator.
  await page.goto('/admin/qb/operator/billing')
  await expect(page.getByTestId('operator-billing-page')).toBeVisible()
  await expectAccessible(page, 'operator sponsors billing')
})
