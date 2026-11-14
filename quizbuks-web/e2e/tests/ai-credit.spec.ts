import { aiReady, quizForAi } from './ai'
import { approvedSponsor } from './publishing'
import { expect, expectAccessible, prepareQuizbuks, setAppearance, signIn, test, unique } from './support'
import { fund } from './wallet'

/*
 * The AI credit's page (F-M9 b1, docs/quizbuks/plans/impl/F-M9.md section 7): an approved sponsor with cash in the
 * platform fee account buys AI credit in the dialog, sees the lots and the purchase among the movements, sees the AI
 * dialog's cost before sending, and gives back all that may be given back. No figure is asserted: the amounts are the
 * server's (the page computes none), so the checks are on what is shown and on the kinds of the rows. Every state
 * passes axe, light and dark.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
  await aiReady(request)
})

for (const appearance of ['light', 'dark'] as const) {
  const at = (name: string) => (appearance === 'light' ? name : `${name} (dark)`)

  test(`a Sponsor buys AI credit, sees the cost of a request and gives the credit back (${appearance})`,
    async ({ page, request }) => {
      test.setTimeout(180_000)
      await setAppearance(page, appearance)
      const sponsor = await approvedSponsor(request, 'Ada')
      await fund(request, sponsor.token, 20_000)
      const quizId = await quizForAi(request, sponsor.token, unique('AI credit quiz '))
      await signIn(page, '/sponsor/', sponsor.email)
      await page.goto('/sponsor/money/ai')

      const glance = page.getByTestId('ai-credit-glance')
      await expect(glance).toBeVisible()
      await expect(page.getByTestId('ai-credit-available')).toBeVisible()
      await expect(page.getByTestId('ai-credit-remaining')).toBeVisible()
      await expect(page.getByTestId('ai-credit-reserved')).toBeVisible()
      await expect(page.getByTestId('ai-credit-unbilled')).toBeVisible()
      await expect(page.getByTestId('ai-credit-lot')).toHaveCount(0)
      await expectAccessible(page, at('sponsor AI credit'))

      // Buy: the dialog tells where the credit is paid from; the server says how it split.
      await page.getByTestId('ai-credit-buy').click()
      const buy = page.getByTestId('ai-credit-buy-dialog')
      await expect(buy.getByTestId('ai-credit-buy-funds')).toBeVisible()
      await expectAccessible(page, at('sponsor AI credit buy'))
      await buy.getByTestId('ai-credit-buy-amount').fill('5000')
      await buy.getByTestId('ai-credit-buy-submit').click()
      await expect(buy.getByTestId('ai-credit-bought')).toBeVisible()
      await expectAccessible(page, at('sponsor AI credit bought'))
      await buy.getByTestId('ai-credit-buy-close').click()
      await expect(buy).toBeHidden()

      // The tables follow: a cash lot, and the purchase among the movements.
      await expect(page.locator('[data-testid="ai-credit-lot"][data-sub-account="CASH"]')).not.toHaveCount(0)
      await expect(page.locator('[data-testid="ai-credit-movement"][data-kind="BUY"]')).toHaveCount(1)
      await expectAccessible(page, at('sponsor AI credit tables'))

      // The AI dialog of a quiz says what the request may cost before it is sent.
      await page.goto(`/sponsor/quizzes/${quizId}`)
      await page.getByTestId('tab-questions').click()
      await page.getByTestId('ai-generate').click()
      const dialog = page.getByTestId('ai-dialog')
      await expect(dialog.getByTestId('ai-quote')).toBeVisible()
      await expect(dialog.getByTestId('ai-quote-short')).toHaveCount(0)
      await expectAccessible(page, at('sponsor AI dialog with the cost'))
      await page.keyboard.press('Escape')
      await expect(dialog).toBeHidden()

      // Give back all that may be given back.
      await page.goto('/sponsor/money/ai')
      await expect(page.getByTestId('ai-credit-glance')).toBeVisible()
      await page.getByTestId('ai-credit-return').click()
      const giveBack = page.getByTestId('ai-credit-return-dialog')
      await giveBack.getByTestId('ai-credit-return-all').click()
      await expect(giveBack.getByTestId('ai-credit-return-amount')).not.toHaveValue('')
      await expectAccessible(page, at('sponsor AI credit give back'))
      await giveBack.getByTestId('ai-credit-return-submit').click()
      await expect(giveBack.getByTestId('ai-credit-returned')).toBeVisible()
      await giveBack.getByTestId('ai-credit-return-close').click()
      await expect(giveBack).toBeHidden()
      await expect(page.locator('[data-testid="ai-credit-movement"][data-kind="RETURN"]')).toHaveCount(1)
      await expectAccessible(page, at('sponsor AI credit after giving back'))
    })
}
