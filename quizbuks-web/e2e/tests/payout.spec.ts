import { expect, expectAccessible, prepareQuizbuks, rows, signIn, test, setAppearance } from './support'
import { earnReward, takerInJapan } from './wallet'

/**
 * The app's wallet (docs/quizbuks/plans/QP-pages.md, QP4): the Kudos earned and the transactions, then the payout
 * account opened through the Stripe stub's onboarding, enabled once the server recorded Stripe's signed report. axe
 * checks every page in both appearances, at a phone's width (and the wallet at a desktop's).
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

const MY_PAYOUT_ACCOUNT = 'urn:jabiz:dataset:taker:QbPayoutAccount'

for (const appearance of ['light', 'dark'] as const) {
  const at = (name: string) => (appearance === 'light' ? name : `${name} (dark)`)

  test(`a taker sees the reward in the wallet and sets up payouts with Stripe (${appearance})`, async ({
    page,
    request,
  }) => {
    test.setTimeout(180_000)
    await setAppearance(page, appearance)
    await page.setViewportSize({ width: 390, height: 844 })
    const me = await takerInJapan(request)
    const reward = 500
    const name = await earnReward(request, me.token, reward)

    await signIn(page, '/', me.email)
    await expect(page.getByTestId('home')).toBeVisible()
    await page.getByTestId('nav-wallet').click()
    await expect(page).toHaveURL(/\/wallet$/)
    // The reward less the commission is the taker's: some Kudos, never more than the reward.
    const available = page.getByTestId('wallet-available')
    await expect(available).toHaveText(/^[\d,]+\sKudos$/)
    const kudos = Number((await available.textContent())!.replace(/[^\d]/g, ''))
    expect(kudos).toBeGreaterThan(0)
    expect(kudos).toBeLessThanOrEqual(reward)
    const entry = page.getByTestId('ledger-row').filter({ hasText: name })
    await expect(entry).toHaveAttribute('data-status', 'COMPLETED')
    await expect(entry.getByTestId('ledger-amount')).toHaveText(`+${kudos.toLocaleString('en-US')} Kudos`)
    await expect(page.getByTestId('wallet')).not.toContainText('JPY')
    await expect(page.getByTestId('wallet-payout')).toHaveAttribute('data-status', 'NONE')
    await expectAccessible(page, at('app wallet'))
    await page.setViewportSize({ width: 1280, height: 844 })
    await expectAccessible(page, at('app wallet 1280px'))
    await page.setViewportSize({ width: 390, height: 844 })

    await page.getByTestId('wallet-payout-open').click()
    await expect(page).toHaveURL(/\/wallet\/payout$/)
    await expect(page.getByTestId('payout-status')).toHaveAttribute('data-status', 'NONE')
    await expectAccessible(page, at('app payout account'))

    // QB_PAYOUT_ONBOARD, then the page waits for Stripe's link and leaves for it.
    await page.getByTestId('payout-onboard').click()
    await page.waitForURL(/\/connect\/acct_[A-Za-z0-9]+$/, { timeout: 30_000 })
    // The stub's "Enable": account.updated is signed and posted to the Connect endpoint, the browser comes back.
    await page.getByRole('button', { name: 'Enable', exact: true }).click()
    await page.waitForURL(/\/wallet\/payout\?onboarding=done$/)
    await expect(page.getByTestId('payout-state')).toHaveAttribute('data-state', 'enabled', { timeout: 30_000 })
    await expect(page.getByTestId('payout-status')).toHaveAttribute('data-status', 'ENABLED')
    await expect(page.getByTestId('payout-onboard')).toHaveCount(0)
    await expectAccessible(page, at('app payout enabled'))
    expect((await rows(request, me.token, MY_PAYOUT_ACCOUNT, []))[0]?.attributes.status).toBe('ENABLED')

    await page.getByTestId('payout-back').click()
    await expect(page.getByTestId('wallet-payout')).toHaveAttribute('data-status', 'ENABLED')
  })
}

test('an expired onboarding link leads to a new one', async ({ page, request }) => {
  test.setTimeout(120_000)
  await page.setViewportSize({ width: 390, height: 844 })
  const me = await takerInJapan(request, 'Ben')
  await signIn(page, '/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()

  // Stripe sends the taker to the refresh address when a link expired or was used: the page offers a new one.
  await page.goto('/wallet/payout?onboarding=refresh')
  await expect(page.getByTestId('payout-state')).toHaveAttribute('data-state', 'refresh')
  await expectAccessible(page, 'app payout refresh')
  await page.getByTestId('payout-onboard').click()
  await page.waitForURL(/\/connect\/acct_[A-Za-z0-9]+$/, { timeout: 30_000 })
  const first = page.url()

  // Back without finishing: the account is being set up; continuing asks Stripe for a fresh link to the same account.
  await page.goto('/wallet/payout')
  await expect(page.getByTestId('payout-status')).toHaveAttribute('data-status', 'PENDING')
  await expect(page.getByTestId('payout-state')).toHaveAttribute('data-state', 'unfinished')
  await expectAccessible(page, 'app payout unfinished')
  await page.getByTestId('payout-onboard').click()
  await page.waitForURL(first, { timeout: 30_000 })
})
