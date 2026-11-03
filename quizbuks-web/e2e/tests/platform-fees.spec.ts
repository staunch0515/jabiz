import { signInAdmin } from './admin'
import { expect, expectAccessible, prepareQuizbuks, signIn, test } from './support'
import { approvedSponsor } from './wallet'

/**
 * The platform fees (docs/quizbuks/plans/Q87-fee-account.md, Q87b): a new sponsor's wallet shows how the platform
 * bills them (prepaid, the platform's default) and that nothing is owed, and the administration's fees page and the
 * operator's billing page open. Setting a sponsor postpaid, a fee, an invoice and paying early need an administrator
 * with a verified address, the monthly job or another administrator's approval: the backend tests (FeeIT) drive
 * those. axe checks the three pages.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('a sponsor sees how the platform bills them; finance and the operator open their fee pages', async ({ page, request }) => {
  test.setTimeout(120_000)
  const sponsor = await approvedSponsor(request)
  await signIn(page, '/sponsor/', sponsor.email)
  // Signed in once the console has left the sign-in page (going elsewhere before would load it signed out).
  await expect(page).toHaveURL(/\/sponsor\/dashboard$/)
  await page.goto('/sponsor/wallet')
  const billing = page.getByTestId('wallet-billing')
  await expect(billing).toBeVisible()
  await expect(billing).toHaveAttribute('data-mode', 'PREPAID')
  await expect(billing).toContainText('Taken from the cash account when charged')
  await expect(page.getByTestId('wallet-unpaid')).toHaveCount(0)
  await expect(page.getByTestId('wallet-overdue')).toHaveCount(0)
  await expect(page.getByTestId('invoice-pay-early')).toHaveCount(0)
  await expect(page.getByTestId('wallet-invoices')).toContainText('No invoices')
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
