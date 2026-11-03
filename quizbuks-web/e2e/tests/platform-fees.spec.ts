import { signInAdmin } from './admin'
import { adminToken, expect, expectAccessible, prepareQuizbuks, run, signIn, test } from './support'
import { approvedSponsor } from './wallet'

/**
 * The platform fees (docs/quizbuks/plans/Q87-fee-account.md, Q87b): finance makes a sponsor postpaid with a credit,
 * the sponsor's wallet shows how they pay and what they owe, and finance's fees page lists its forms and invoices.
 * Charges by hand need another administrator's approval and the invoices a monthly job: the backend tests (FeeIT)
 * drive those. axe checks both pages.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('finance makes a sponsor postpaid and the sponsor sees it on the wallet', async ({ page, request }) => {
  test.setTimeout(120_000)
  const sponsor = await approvedSponsor(request)
  const me = await (await request.get('/api/auth/me', { headers: { Authorization: `Bearer ${sponsor.token}` } }))
    .json() as { userId: string }
  await run(request, await adminToken(request), 'QB_SPONSOR_BILLING_SET', { sponsorId: me.userId,
    billingMode: 'POSTPAID', creditLimit: 30000, reason: 'End-to-end test' })

  await signIn(page, '/sponsor/', sponsor.email)
  await page.goto('/sponsor/wallet')
  const fees = page.getByTestId('wallet-fees')
  await expect(fees).toBeVisible()
  await expect(fees.getByTestId('fees-mode')).toHaveAttribute('data-mode', 'POSTPAID')
  await expect(fees.getByTestId('fees-unpaid')).toHaveText('0 Kudos')
  await expectAccessible(page, 'sponsor wallet with platform fees')

  await signInAdmin(page)
  await page.goto('/admin/qb/finance/fees')
  await expect(page.getByTestId('fees-invoices')).toBeVisible()
  await expect(page.getByTestId('charge-form')).toBeVisible()
  await expect(page.getByTestId('billing-form')).toBeVisible()
  await expectAccessible(page, 'admin platform fees')
})
