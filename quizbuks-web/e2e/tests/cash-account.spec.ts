import { expect, expectAccessible, prepareQuizbuks, signIn, test } from './support'
import { approvedSponsor, sponsorBalance } from './wallet'

/**
 * The sponsor's cash account (docs/quizbuks/plans/Q87-fee-account.md, Q87a): a card top-up into the cash account
 * through the Stripe stub, then part of it turned into Kudos. The switches are the set-up's (both targets offered,
 * Kudos by default, buying on). axe checks the wallet with its cash account.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('a sponsor tops up the cash account and turns part of it into Kudos', async ({ page, request }) => {
  test.setTimeout(120_000)
  const sponsor = await approvedSponsor(request)
  await signIn(page, '/sponsor/', sponsor.email)
  // Signed in once the console has left the sign-in page (going elsewhere before would load it signed out).
  await expect(page).toHaveURL(/\/sponsor\/dashboard$/)
  await page.goto('/sponsor/wallet')
  await expect(page.getByTestId('wallet-cash')).toBeVisible()
  await expect(page.getByTestId('wallet-cash-balance')).toHaveText('0 Kudos')

  await page.getByTestId('topup-open').click()
  await page.getByTestId('topup-target-CASH').click()
  await page.getByTestId('topup-amount').fill('1500')
  await expectAccessible(page, 'sponsor top-up dialog with targets')
  const started = page.waitForResponse((r) => r.url().includes('/api/processes/QB_TOPUP_START/'))
  await page.getByTestId('topup-submit').click()
  const response = await started
  expect(response.status(), await response.text()).toBe(200)
  const topUpId = (await response.json()).output.topUpId as string
  await page.waitForURL(/\/pay\/cs_test_[A-Za-z0-9]+$/, { timeout: 30_000 })
  await page.getByRole('button', { name: 'Pay', exact: true }).click()
  await page.waitForURL(new RegExp(`/sponsor/wallet/topups/${topUpId}\\?checkout=paid$`))
  await expect(page.getByTestId('topup-state')).toHaveAttribute('data-state', 'paid', { timeout: 30_000 })

  await page.goto('/sponsor/wallet')
  await expect(page.getByTestId('wallet-cash-balance')).toHaveText('1,500 Kudos')
  // The Kudos wallet got nothing from it.
  await expect(page.getByTestId('wallet-available')).toContainText('0 Kudos')
  const row = page.getByTestId('topup-row').filter({ has: page.locator(`[href$="/topups/${topUpId}"]`) })
  await expect(row.getByTestId('topup-row-cash')).toBeVisible()
  await expectAccessible(page, 'sponsor wallet with a cash account')

  const buy = page.getByTestId('cash-buy')
  await buy.getByTestId('cash-buy-amount').fill('1000')
  await buy.getByTestId('cash-buy-submit').click()
  await expect(page.getByTestId('wallet-cash-balance')).toHaveText('500 Kudos')
  await expect(page.getByTestId('wallet-available')).toContainText('1,000 Kudos')
  expect(await sponsorBalance(request, sponsor.token)).toBe(1000)
})
