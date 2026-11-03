import type { APIRequestContext } from '@playwright/test'
import { expect, expectAccessible, prepareQuizbuks, rows, run, signIn, test } from './support'
import { approvedSponsor, sponsorBalance } from './wallet'

/*
 * The sponsor's cash account (docs/quizbuks/plans/Q87-fee-account.md): a top-up into the cash account (the target the
 * administrators allow by default, next to the Kudos wallet) paid at the Stripe stub's checkout through the API; the
 * wallet page shows the cash, and buying Kudos from it (`QB_KUDOS_BUY`, one for one) moves both balances as the server
 * records them. The platform's fees and invoices are covered by the backend's `FeeIT`; the monthly fee is 0 here.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

const SPONSOR_WALLETS = 'urn:jabiz:dataset:sponsor:QbWallet'
const SPONSOR_TOPUPS = 'urn:jabiz:dataset:sponsor:QbTopUp'

/** The sponsor's cash account balance as the wallet records it (no row before the first top-up into it). */
async function cashBalance(request: APIRequestContext, token: string): Promise<number> {
  const wallets = await rows(request, token, SPONSOR_WALLETS, [{ field: 'kind', op: 'eq', value: 'CASH' }])
  return Number(wallets[0]?.attributes.balance ?? 0)
}

/** An approved sponsor with `amount` in the cash account: a top-up aimed at it, paid at the Stripe stub's checkout. */
async function sponsorWithCash(request: APIRequestContext, amount: number) {
  const sponsor = await approvedSponsor(request, 'Sora', `Cash Co ${Date.now()}`)
  const topUp = await run(request, sponsor.token, 'QB_TOPUP_START', { amount, target: 'CASH' })
  let checkoutUrl: string | undefined
  await expect.poll(async () => {
    const found = (await rows(request, sponsor.token, SPONSOR_TOPUPS, [])).find((r) => r.id === topUp.topUpId)
    checkoutUrl = found?.attributes.checkoutUrl as string | undefined
    return !!checkoutUrl
  }, { message: 'the checkout opens', timeout: 30_000 }).toBe(true)
  const paid = await request.post(`${checkoutUrl}/paid`, { maxRedirects: 0 })
  expect(paid.status()).toBeLessThan(400)
  await expect.poll(() => cashBalance(request, sponsor.token), { message: 'the cash account is credited',
    timeout: 30_000 }).toBe(amount)
  return { ...sponsor, topUpId: topUp.topUpId as string }
}

test('a top-up into the cash account pays no reward; Kudos are bought from it one for one', async ({ page, request }) => {
  test.setTimeout(120_000)
  const sponsor = await sponsorWithCash(request, 5000)
  // Nothing became Kudos by itself.
  expect(await sponsorBalance(request, sponsor.token)).toBe(0)

  await signIn(page, '/sponsor/', sponsor.email)
  await page.getByRole('link', { name: 'Wallet' }).click()
  await expect(page).toHaveURL(/\/sponsor\/wallet$/)
  await expect(page.getByTestId('wallet-available')).toContainText('0 Kudos')
  const cash = page.getByTestId('wallet-cash')
  await expect(cash.getByTestId('wallet-cash-balance')).toHaveText('5,000 Kudos')
  await expect(cash.getByTestId('wallet-billing')).toHaveAttribute('data-mode', 'PREPAID')
  // The top-up's row says where it went.
  const row = page.getByTestId('topup-row').filter({ has: page.locator(`[href$="/topups/${sponsor.topUpId}"]`) })
  await expect(row.getByTestId('topup-row-status')).toHaveAttribute('data-status', 'PAID')
  await expect(row.getByTestId('topup-row-target')).toHaveText('Cash account')
  await expect(page.getByTestId('wallet')).not.toContainText('JPY')
  await expectAccessible(page, 'sponsor wallet with a cash account')

  // Buying 3,000 Kudos: the server answers both balances, and the page reads them again.
  await page.getByTestId('kudos-buy').click()
  const dialog = page.getByTestId('exchange-dialog')
  await expect(dialog).toHaveAttribute('data-direction', 'BUY')
  await expectAccessible(page, 'sponsor buy Kudos dialog')
  await page.getByTestId('exchange-amount').fill('3000')
  const bought = page.waitForResponse((r) => r.url().includes('/api/processes/QB_KUDOS_BUY/'))
  await page.getByTestId('exchange-submit').click()
  expect((await bought).status()).toBe(200)
  await expect(page.getByTestId('exchange-done')).toContainText('Bought 3,000 Kudos. Kudos: 3,000 Kudos, cash: 2,000 Kudos.')
  await expect(page.getByTestId('wallet-available')).toContainText('3,000 Kudos')
  await expect(cash.getByTestId('wallet-cash-balance')).toHaveText('2,000 Kudos')
  const exchange = page.getByTestId('exchange-row')
  await expect(exchange).toHaveCount(1)
  await expect(exchange.getByTestId('exchange-row-direction')).toHaveAttribute('data-direction', 'BUY')
  await expect(exchange.getByTestId('exchange-row-amount')).toHaveText('3,000 Kudos')
  await expectAccessible(page, 'sponsor wallet after buying Kudos')
  expect(await sponsorBalance(request, sponsor.token)).toBe(3000)
  expect(await cashBalance(request, sponsor.token)).toBe(2000)

  // More than the cash holds: the server's refusal, in the dialog, nothing moved.
  await page.getByTestId('kudos-buy').click()
  await page.getByTestId('exchange-amount').fill('6000')
  await page.getByTestId('exchange-submit').click()
  await expect(page.getByTestId('exchange-amount')).toHaveAttribute('aria-invalid', 'true')
  await expectAccessible(page, 'sponsor buy Kudos refused')
  await page.getByRole('button', { name: 'Cancel' }).click()
  expect(await sponsorBalance(request, sponsor.token)).toBe(3000)

  // Selling is off by default: no button.
  await expect(page.getByTestId('kudos-sell')).toHaveCount(0)
  // No invoices: the monthly fee is 0 in this deployment.
  await expect(page.getByTestId('wallet-invoices')).toContainText('No invoices.')
})

test('the top-up dialog lets the sponsor choose the cash account', async ({ page, request }) => {
  const sponsor = await approvedSponsor(request, 'Sora', `Cash Co ${Date.now()}`)
  await signIn(page, '/sponsor/', sponsor.email)
  await page.goto('/sponsor/wallet')
  await page.getByTestId('topup-open').click()
  const target = page.getByTestId('topup-target')
  await expect(target.getByTestId('topup-target-KUDOS')).toHaveAttribute('data-state', 'checked')
  await target.getByTestId('topup-target-CASH').click()
  await expectAccessible(page, 'sponsor top-up dialog with a target')
  await page.getByTestId('topup-amount').fill('1500')
  const started = page.waitForResponse((r) => r.url().includes('/api/processes/QB_TOPUP_START/'))
  await page.getByTestId('topup-submit').click()
  const response = await started
  expect(response.status(), await response.text()).toBe(200)
  expect(response.request().postDataJSON()).toMatchObject({ amount: 1500, target: 'CASH' })
  const topUpId = (await response.json()).output.topUpId as string
  await page.waitForURL(/\/pay\/cs_test_[A-Za-z0-9]+$/, { timeout: 30_000 })
  await page.getByRole('button', { name: 'Pay', exact: true }).click()
  await page.waitForURL(new RegExp(`/sponsor/wallet/topups/${topUpId}\\?checkout=paid$`))
  await expect(page.getByTestId('topup-status')).toHaveAttribute('data-status', 'PAID', { timeout: 30_000 })
  await expect.poll(() => cashBalance(request, sponsor.token), { timeout: 30_000 }).toBe(1500)
  expect(await sponsorBalance(request, sponsor.token)).toBe(0)
})
