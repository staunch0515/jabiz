import { signInAdmin } from './admin'
import { aiReady, quizForAi } from './ai'
import { query } from './content'
import { approvedSponsor } from './publishing'
import { expect, expectAccessible, prepareQuizbuks, signIn, test, unique, setAppearance } from './support'

/**
 * AI questions (docs/quizbuks/plans/Q8-ai.md, Q8a; M-26, M-20, M-10) with the provider answered by the local stub: an
 * approved sponsor accepts the AI notice on first use, asks for questions from the quiz's article, reviews them (one
 * rewritten), adds the reviewed one only; the quiz is then marked as AI generated, the dashboard shows the month's
 * usage, and the administration's AI page the request. Every page passes axe, light and dark.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
  await aiReady(request)
})

for (const appearance of ['light', 'dark'] as const) {
  const at = (name: string) => (appearance === 'light' ? name : `${name} (dark)`)

  test(`a sponsor reviews the AI's questions and adds them (${appearance})`, async ({ page, request }) => {
    test.setTimeout(180_000)
    await setAppearance(page, appearance)
    const sponsor = await approvedSponsor(request, 'Ada')
    const title = unique('AI quiz ')
    const quizId = await quizForAi(request, sponsor.token, title)
    await signIn(page, '/sponsor/', sponsor.email)
    await expect(page.getByTestId('dashboard-status')).toBeVisible()
    await page.goto(`/sponsor/quizzes/${quizId}`)
    await page.getByTestId('tab-questions').click()
    const panel = page.getByTestId('ai-panel')
    await expect(panel.getByTestId('ai-remaining')).toBeVisible()
    await expectAccessible(page, at('sponsor AI panel'))

    await panel.getByTestId('ai-generate').click()
    const dialog = page.getByTestId('ai-dialog')
    await expect(dialog.getByTestId('ai-material')).toHaveCount(1)
    // The first request asks for the AI notice.
    await expect(dialog.getByTestId('ai-notice')).toBeVisible()
    await expectAccessible(page, at('sponsor AI dialog'))
    await dialog.getByTestId('ai-count').fill('2')
    await dialog.getByTestId('ai-notice-agree').click()
    await dialog.getByTestId('ai-submit').click()
    await expect(dialog).toBeHidden()

    // The job runs at the stub; the page looks again every five seconds.
    const review = page.getByTestId('ai-review')
    await expect(review).toBeVisible({ timeout: 60_000 })
    const cards = review.getByTestId('ai-candidate')
    await expect(cards).toHaveCount(2)
    await expect(review.getByTestId('ai-accept')).toBeDisabled()
    await expectAccessible(page, at('sponsor AI review'))
    const first = cards.nth(0)
    await first.getByTestId('ai-candidate-stem').fill('Which city is the capital of France?')
    await first.getByTestId('ai-candidate-reviewed').click()
    await review.getByTestId('ai-accept').click()
    await expect(review).toBeHidden()
    await expect(page.getByTestId('questions-panel')).toContainText('Which city is the capital of France?')
    await expect(page.getByTestId('tab-questions')).toContainText('1')

    // Only the reviewed question was added, with its job; the quiz is AI generated (M-20).
    const quizzes = await query(request, sponsor.token, 'qb.sponsor.quizzes', {})
    expect(quizzes.find((row) => row.quizId === quizId)?.aiGenerated).toBe(true)
    await page.goto('/sponsor/quizzes')
    // Q34b: a card by default (the table's row carries the same test id and data).
    await expect(page.locator(`[data-testid="quiz-row"][data-quiz="${quizId}"]`)).toContainText('AI generated')

    // The dashboard counts the month's usage (M-10).
    await page.goto('/sponsor/')
    const metric = page.getByTestId('metric-ai')
    await expect(metric).toBeVisible({ timeout: 30_000 })
    await expect(page.getByTestId('metric-ai-detail')).not.toContainText(' 0 requests')
    await expectAccessible(page, at('sponsor dashboard with AI usage'))

    // The administration's AI page: the request, the usage, the switch on.
    await page.context().clearCookies()
    await signInAdmin(page)
    await page.goto('/admin/qb/ai')
    await expect(page.getByTestId('ai-switch-state')).toHaveText('On')
    await expect(page.getByTestId('ai-usage')).toBeVisible()
    await expect(page.getByTestId('ai-jobs')).toBeVisible()
    await expectAccessible(page, at('admin AI page'))
  })
}
