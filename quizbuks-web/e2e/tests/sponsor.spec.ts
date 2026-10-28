import type { Page } from '@playwright/test'
import { completeQuiz } from './content'
import { approvedSponsor, decideReview, publicationIdIn, publicationOf, typeTime } from './publishing'
import { expect, prepareQuizbuks, signIn, test, unique } from './support'

/**
 * Publishing in the sponsor console (docs/quizbuks/plans/QP-pages.md, QP3b): an approved sponsor's quiz version through
 * the three-step wizard, every problem listed before submitting, the review sent back with a reason and submitted
 * again, withdrawn and submitted again, approved by the administrator and live; then paused, resumed, copied and the
 * copy deleted. Amounts are Kudos throughout.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** Waits until the page shows the publication in a state, reloading as the server moves it (approval events). */
async function untilStatus(page: Page, status: string) {
  await expect.poll(async () => {
    await page.reload()
    const shown = page.getByTestId('publication-view').or(page.getByTestId('publication-wizard'))
    await expect(shown).toBeVisible()
    return page.getByTestId('publication-status').first().getAttribute('data-status')
  }, { message: `the publication is ${status}`, timeout: 30_000 }).toBe(status)
}

/** From a draft's first step to its last: saving each step. */
async function toLastStep(page: Page) {
  await expect(page.getByTestId('publication-wizard')).toHaveAttribute('data-step', '0')
  await page.getByTestId('publication-next').click()
  await expect(page.getByTestId('publication-wizard')).toHaveAttribute('data-step', '1')
  await page.getByTestId('publication-next').click()
  await expect(page.getByTestId('publication-wizard')).toHaveAttribute('data-step', '2')
  await page.getByTestId('publication-next').click()
  await expect(page.getByTestId('publication-wizard')).toHaveAttribute('data-step', '3')
}

/** Submits the draft on the wizard's last step, confirming. */
async function submit(page: Page) {
  await expect(page.getByTestId('publication-ready')).toBeVisible()
  await page.getByTestId('publication-submit').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Submit' }).click()
  await expect(page.getByTestId('publication-view')).toHaveAttribute('data-status', 'PENDING_REVIEW')
}

test('a sponsor publishes a quiz version through review and runs the publication', async ({ page, request }) => {
  const { email, token } = await approvedSponsor(request, 'Penny')
  const quiz = await completeQuiz(request, token, { title: unique('Publish me '), questions: 2 })
  const name = unique('Spring run ')

  await signIn(page, '/sponsor/', email)
  await expect(page.getByTestId('dashboard-status')).toBeVisible()
  await page.getByRole('link', { name: 'Publications' }).click()
  await expect(page.getByTestId('publication-list')).toBeVisible()
  await page.getByTestId('new-publication').click()

  // 1. The quiz version: the newest is chosen with the quiz.
  await expect(page.getByTestId('publication-step-title')).toHaveText('Quiz')
  await page.getByTestId('publication-name').fill(name)
  await page.getByTestId('publication-quiz').click()
  await page.getByPlaceholder('Search your quizzes').fill(quiz.title)
  await page.getByRole('option', { name: new RegExp(quiz.title) }).click()
  await expect(page.getByTestId('publication-version')).toContainText(`${quiz.label}: 2 questions, full score 2`)
  await page.getByTestId('publication-next').click()

  // The first save created the draft and opened it at its own address, on the second step.
  await expect(page).toHaveURL(/\/sponsor\/publications\/[^/]+\?step=1$/)
  const publicationId = publicationIdIn(page)
  await expect(page.getByTestId('publication-step-title')).toHaveText('Time and rewards')

  // 2. From a minute ago for a week, a pass mark of 2, and tiers with a gap at score 1.
  await typeTime(page, 'publication-start', new Date(Date.now() - 60_000))
  await typeTime(page, 'publication-end', new Date(Date.now() + 7 * 86_400_000))
  await page.getByTestId('publication-pass-score').fill('2')
  await page.getByTestId('tier-add').click()
  const tiers = page.getByTestId('tier')
  await tiers.nth(0).getByTestId('tier-to').fill('0')
  await tiers.nth(0).getByTestId('tier-reward').fill('0')
  await page.getByTestId('tier-add').click()
  await expect(tiers.nth(1).getByTestId('tier-from')).toHaveValue('1')
  await tiers.nth(1).getByTestId('tier-from').fill('2')
  await tiers.nth(1).getByTestId('tier-reward').fill('50')
  await page.getByTestId('publication-budget').fill('1000')
  await page.getByTestId('publication-max-winners').fill('10')
  await page.getByTestId('publication-next').click()

  // 3. Everyone, with no matching conditions (Q47).
  await expect(page.getByTestId('publication-step-title')).toHaveText('Customer matching')
  await page.getByTestId('publication-regions').getByRole('checkbox', { name: 'Global' }).click()
  await page.getByTestId('publication-next').click()

  // 4. Every problem at once (here: the gap), and no submitting until it is gone.
  await expect(page.getByTestId('publication-step-title')).toHaveText('Check and submit')
  await expect(page.getByTestId('publication-problems')).toContainText('No tier covers the scores 1 to 1')
  await expect(page.getByTestId('publication-submit')).toBeDisabled()
  await page.getByTestId('publication-back').click()
  await page.getByTestId('publication-back').click()
  await tiers.nth(0).getByTestId('tier-to').fill('1')
  await page.getByTestId('publication-next').click()
  await page.getByTestId('publication-next').click()
  await expect(page.getByTestId('publication-ready')).toBeVisible()
  const summary = page.getByTestId('publication-summary')
  // At most 10 winners of 50 Kudos, within the budget of 1,000.
  await expect(summary.getByTestId('summary-max-spend')).toHaveText('500 Kudos')
  await expect(summary).toContainText('1,000 Kudos')
  await expect(summary.getByTestId('summary-tier')).toHaveCount(2)
  await expect(summary).not.toContainText('JPY')
  await submit(page)

  // Sent back with a reason: a draft again, the reason shown in the list and on the draft.
  await decideReview(request, token, publicationId, 'REJECT', 'Please name the brand in the title.')
  await untilStatus(page, 'DRAFT')
  await expect(page.getByTestId('publication-sent-back-reason')).toContainText('Please name the brand in the title.')
  await page.getByRole('link', { name: 'Publications' }).first().click()
  const row = page.getByTestId('publication-row').filter({ hasText: name })
  await expect(row.getByTestId('publication-sent-back')).toBeVisible()
  await row.getByTestId('publication-link').click()
  await page.getByTestId('publication-name').fill(`${name} by Penny Co`)
  await toLastStep(page)
  await submit(page)

  // Withdrawn from review (asked first): a draft again, then submitted once more.
  await page.getByTestId('publication-withdraw').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Withdraw' }).click()
  await expect(page.getByTestId('publication-wizard')).toBeVisible()
  await toLastStep(page)
  await submit(page)

  // Approved by the administrator: it started a minute ago, so it is live.
  await decideReview(request, token, publicationId, 'APPROVE')
  await untilStatus(page, 'LIVE')
  await expect(page.getByTestId('publication-view')).toContainText('A submitted publication cannot be changed')
  expect((await publicationOf(request, token, publicationId)).status).toBe('LIVE')

  // Paused and resumed, each asked first.
  await page.getByTestId('publication-pause').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Pause' }).click()
  await expect(page.getByTestId('publication-view')).toHaveAttribute('data-status', 'PAUSED')
  await page.getByTestId('publication-resume').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Resume' }).click()
  await expect(page.getByTestId('publication-view')).toHaveAttribute('data-status', 'LIVE')

  // The list: live, nobody has taken it yet, nothing paid of the budget.
  await page.getByRole('link', { name: 'Publications' }).first().click()
  const live = page.getByTestId('publication-row').filter({ hasText: name })
  await expect(live).toHaveAttribute('data-status', 'LIVE')
  await expect(live).toContainText(quiz.title)
  await expect(live).toContainText('0 of 10')
  await expect(live).toContainText('0 Kudos of 1,000 Kudos')

  // A copy is a new draft with the same policy; deleting it (asked first) leaves the original.
  await live.getByTestId('publication-link').click()
  await page.getByTestId('publication-copy').click()
  await page.getByTestId('publication-copy-name').fill(`${name} again`)
  await page.getByTestId('publication-copy-confirm').click()
  await expect(page.getByTestId('publication-wizard')).toBeVisible()
  await expect(page.getByTestId('publication-name')).toHaveValue(`${name} again`)
  const copyId = publicationIdIn(page)
  expect(copyId).not.toBe(publicationId)
  await page.getByTestId('publication-delete').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Delete' }).click()
  await expect(page.getByTestId('publication-list')).toBeVisible()
  await page.getByTestId('publication-search').fill(name)
  await expect(page.getByTestId('publication-row')).toHaveCount(1)
  await expect(page.getByTestId('publication-row')).toHaveAttribute('data-publication', publicationId)
})
