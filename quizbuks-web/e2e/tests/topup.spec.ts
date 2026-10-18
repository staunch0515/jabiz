import type { Page } from '@playwright/test'
import { expect, expectAccessible, linkIn, mailTo, prepareQuizbuks, signIn, test, useAppearance } from './support'
import { approvedSponsor, sponsorBalance } from './wallet'

/**
 * The sponsor console's wallet (docs/quizbuks/plans/QP-pages.md, QP4): a top-up from the dialog through the Stripe
 * stub's checkout back to its page, which shows it paid only once the server recorded Stripe's signed notice; a return
 * to the page that says "paid" credits nothing by itself; a cancelled checkout that expires. axe checks every page in
 * both appearances.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** Opens the dialog and starts a top-up of `amount`; returns its id once the browser is at the stub's checkout. */
async function startTopUp(page: Page, amount: string, check?: () => Promise<void>): Promise<string> {
  await page.getByTestId('topup-open').click()
  const dialog = page.getByTestId('topup-dialog')
  await expect(dialog).toBeVisible()
  await page.getByTestId('topup-amount').fill(amount)
  await check?.()
  const started = page.waitForResponse((r) => r.url().includes('/api/processes/QB_TOPUP_START/'))
  await page.getByTestId('topup-submit').click()
  const response = await started
  expect(response.status(), await response.text()).toBe(200)
  const topUpId = (await response.json()).output.topUpId as string
  // The page waits until the checkout is open and leaves for it by itself.
  await page.waitForURL(/\/pay\/cs_test_[A-Za-z0-9]+$/, { timeout: 30_000 })
  return topUpId
}

for (const appearance of ['light', 'dark'] as const) {
  const at = (name: string) => (appearance === 'light' ? name : `${name} (dark)`)

  test(`a sponsor tops up through Stripe's checkout and sees it paid (${appearance})`, async ({ page, request }) => {
    test.setTimeout(120_000)
    await useAppearance(page, appearance)
    const sponsor = await approvedSponsor(request)
    await signIn(page, '/sponsor/', sponsor.email)
    await page.getByRole('link', { name: 'Wallet' }).click()
    await expect(page).toHaveURL(/\/sponsor\/wallet$/)
    await expect(page.getByTestId('wallet-available')).toContainText('0 Kudos')
    await expect(page.getByTestId('topup-row')).toHaveCount(0)
    await expectAccessible(page, at('sponsor wallet'))

    const topUpId = await startTopUp(page, '1500', () => expectAccessible(page, at('sponsor top-up dialog')))
    // The stub's "Pay": the checkout completes, its signed event goes to the server, the browser comes back.
    await page.getByRole('button', { name: 'Pay', exact: true }).click()
    await page.waitForURL(new RegExp(`/sponsor/wallet/topups/${topUpId}\\?checkout=paid$`))
    const state = page.getByTestId('topup-state')
    await expect(state).toHaveAttribute('data-state', 'paid', { timeout: 30_000 })
    await expect(state).toContainText('1,500 Kudos')
    await expect(page.getByTestId('topup-status')).toHaveAttribute('data-status', 'PAID')
    await expect(page.getByTestId('topup-pay')).toHaveCount(0)
    await expectAccessible(page, at('sponsor top-up paid'))

    await page.getByTestId('topup-back').click()
    await expect(page.getByTestId('wallet-available')).toContainText('1,500 Kudos')
    const row = page.getByTestId('topup-row').filter({ has: page.locator(`[href$="/topups/${topUpId}"]`) })
    await expect(row.getByTestId('topup-row-status')).toHaveAttribute('data-status', 'PAID')
    await expect(row.getByTestId('topup-row-amount')).toHaveText('1,500 Kudos')
    await expect(page.getByTestId('wallet')).not.toContainText('JPY')
    await expectAccessible(page, at('sponsor wallet with a top-up'))
    expect(await sponsorBalance(request, sponsor.token)).toBe(1500)

    // Mailed (Q7a, transactional), with a link to the console's wallet.
    const mail = await mailTo(request, sponsor.email, /top-up of 1,500 Kudos has arrived/)
    expect(linkIn(mail.text, '/wallet').pathname).toBe('/sponsor/wallet')

    // The console's account page lists its two notifications, which the sponsor may turn off (D49).
    await page.goto('/sponsor/account')
    const preferences = page.getByTestId('account-mail')
    await expect(preferences.getByTestId('mail-preference')).toHaveCount(2)
    await expect(preferences).toContainText('Publications short of funds')
    await expect(preferences).toContainText('Publication ended')
    await expectAccessible(page, at('sponsor account with mail preferences'))
  })
}

test('the browser\'s return credits nothing; a cancelled checkout expires', async ({ page, request }) => {
  test.setTimeout(150_000)
  const sponsor = await approvedSponsor(request)
  await signIn(page, '/sponsor/', sponsor.email)
  await page.getByRole('link', { name: 'Wallet' }).click()

  // An amount outside the range: the server's own words, next to the field.
  await page.getByTestId('topup-open').click()
  await page.getByTestId('topup-amount').fill('10')
  await page.getByTestId('topup-submit').click()
  await expect(page.getByTestId('topup-dialog')).toContainText('1000')
  await expectAccessible(page, 'sponsor top-up refused')
  await page.getByRole('button', { name: 'Cancel' }).click()
  await expect(page.getByTestId('topup-dialog')).toBeHidden()

  const topUpId = await startTopUp(page, '2000')
  const checkout = page.url()
  // Coming back with "paid" without paying: the page waits for Stripe and the wallet stays as it was.
  await page.goto(`/sponsor/wallet/topups/${topUpId}?checkout=paid`)
  const state = page.getByTestId('topup-state')
  await expect(state).toHaveAttribute('data-state', 'confirming')
  await expect(page.getByTestId('topup-status')).toHaveAttribute('data-status', 'OPEN')
  await expect(page.getByTestId('topup-pay')).toHaveCount(0)
  await expectAccessible(page, 'sponsor top-up confirming')
  expect(await sponsorBalance(request, sponsor.token)).toBe(0)

  // Opened again from the list, the page offers the checkout instead of leaving by itself.
  await page.goto('/sponsor/wallet')
  await page.getByTestId('topup-row').filter({ has: page.locator(`[href$="/topups/${topUpId}"]`) })
    .getByTestId('topup-row-open').click()
  await expect(state).toHaveAttribute('data-state', 'open')
  await expectAccessible(page, 'sponsor top-up open')
  await page.getByTestId('topup-pay').click()
  await page.waitForURL(checkout)

  // The stub's "Expire": the checkout ends unpaid and the browser comes back to the cancel address.
  await page.getByRole('button', { name: 'Expire', exact: true }).click()
  await page.waitForURL(new RegExp(`/sponsor/wallet/topups/${topUpId}\\?checkout=cancelled$`))
  await expect(page.getByTestId('topup-status')).toHaveAttribute('data-status', 'EXPIRED', { timeout: 30_000 })
  await expect(state).toHaveAttribute('data-state', 'expired')
  await expectAccessible(page, 'sponsor top-up expired')
  expect(await sponsorBalance(request, sponsor.token)).toBe(0)
})
