import type { APIRequestContext } from '@playwright/test'
import {
  adminToken,
  expect,
  expectAccessible,
  prepareQuizbuks,
  run,
  setAppearance,
  signIn,
  test,
  unique,
} from './support'
import { fundedSponsor, taker, untilLive } from './play'

/**
 * Surveys (docs/quizbuks/plans/Q12-survey.md): a sponsor makes one in the console (a quiz of the kind "survey"), its
 * questions and publication are prepared through the API as the console sends them (single and multiple choice, one
 * reward for a complete response), and a taker answers it in the app: radio buttons for the single choice, no
 * submitting before every question is answered, and a result without a score or right and wrong. axe checks the pages
 * in both appearances.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** A live survey of two questions whose reward is the sponsor's whole balance. */
async function seedSurvey(request: APIRequestContext, token: string, quizId: string, reward: number) {
  await run(request, token, 'QB_QUESTION_SAVE', { quizId, stem: 'How often do you drink tea?', selection: 'SINGLE',
    options: [{ text: 'Daily' }, { text: 'Weekly' }] })
  await run(request, token, 'QB_QUESTION_SAVE', { quizId, stem: 'Which teas do you like?', selection: 'MULTIPLE',
    options: [{ text: 'Green' }, { text: 'Black' }, { text: 'Herbal' }] })
  const version = await run(request, token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  expect(version.fullScore).toBe(2)
  const name = unique('Tea survey ')
  const now = Date.now()
  const saved = await run(request, token, 'QB_PUBLICATION_SAVE', {
    versionId: version.versionId, name, regions: ['GLOBAL'],
    startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
    timeLimitSec: 300, passScore: 2,
    tiers: [{ fromScore: 0, toScore: 1, reward: 0 }, { fromScore: 2, toScore: 2, reward }],
  })
  expect(saved.problems).toEqual([])
  const publicationId = saved.publicationId as string
  const submitted = await run(request, token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  if (submitted.approval === 'PENDING') {
    await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId,
      decision: 'APPROVE' })
  }
  return { publicationId, name }
}

for (const appearance of ['light', 'dark'] as const) {
  const at = (name: string) => (appearance === 'light' ? name : `${name} (dark)`)

  test(`a sponsor makes a survey and a taker completes it for the reward (${appearance})`, async ({
    page,
    request,
  }) => {
    test.setTimeout(180_000)
    await setAppearance(page, appearance)
    const sponsor = await fundedSponsor(request)
    const title = unique('Tea habits ')
    await signIn(page, '/sponsor/', sponsor.email)
    await page.getByRole('link', { name: 'Quizzes' }).click()
    await page.getByTestId('new-quiz').click()
    await page.getByTestId('new-quiz-title').fill(title)
    await page.getByTestId('new-quiz-kind-SURVEY').click()
    await expectAccessible(page, at('sponsor new survey'))
    await page.getByTestId('new-quiz-create').click()
    await expect(page).toHaveURL(/\/sponsor\/quizzes\/[0-9a-f-]{36}$/)
    const quizId = page.url().split('/').pop()!
    await expect(page.getByTestId('editor-survey')).toBeVisible()
    await page.getByTestId('tab-questions').click()
    await page.getByTestId('question-add').click()
    // A survey's question: single or multiple choice, no points and no correct options.
    await expect(page.getByTestId('question-selection')).toBeVisible()
    await expect(page.getByTestId('question-points')).toHaveCount(0)
    await expect(page.getByTestId('option-correct')).toHaveCount(0)
    await expectAccessible(page, at('sponsor survey question'))
    await page.keyboard.press('Escape')

    const seeded = await seedSurvey(request, sponsor.token, quizId, sponsor.balance)
    const me = await taker(request, 'Cho')
    await untilLive(request, me.token, seeded.publicationId)

    await page.setViewportSize({ width: 390, height: 844 })
    await signIn(page, '/', me.email)
    await expect(page.getByTestId('home')).toBeVisible()
    await page.getByTestId('feed-search').fill(seeded.name)
    await page.getByTestId('feed-search-submit').click()
    const card = page.getByTestId('feed-card').filter({ hasText: seeded.name })
    await expect(card.getByTestId('feed-card-survey')).toBeVisible()
    await card.click()
    await expect(page.getByTestId('detail-survey')).toBeVisible()
    await expect(page.getByTestId('detail-tiers')).toHaveCount(0)
    await expectAccessible(page, at('app survey'))

    await page.getByTestId('detail-start').click()
    await expect(page).toHaveURL(/\/play\/[^/]+$/)
    await expect(page.getByTestId('play-question')).toContainText('How often do you drink tea?')
    await page.getByRole('radio', { name: 'Weekly' }).click()
    await expect(page.getByRole('radio', { name: 'Weekly' })).toBeChecked()
    await expectAccessible(page, at('app survey single choice'))
    await page.getByTestId('play-next').click()
    // The last question: nothing chosen yet, so submitting waits.
    await expect(page.getByTestId('play-submit')).toHaveAttribute('aria-disabled', 'true')
    await expect(page.getByTestId('play-incomplete')).toBeVisible()
    await page.getByRole('checkbox', { name: 'Green' }).click()
    await page.getByRole('checkbox', { name: 'Herbal' }).click()
    await expect(page.getByTestId('play-submit')).not.toHaveAttribute('aria-disabled')
    await page.getByTestId('play-submit').click()
    await page.getByRole('alertdialog').getByRole('button', { name: 'Submit answers' }).click()

    await expect(page).toHaveURL(/\/result\/[^/]+$/)
    await expect(page.getByTestId('result-survey')).toHaveAttribute('data-complete', 'true')
    await expect(page.getByTestId('result-score')).toHaveCount(0)
    await expect(page.getByTestId('result-reward')).toHaveAttribute('data-status', 'PAID')
    await expect(page.getByTestId('result-reward')).toContainText(`${sponsor.balance.toLocaleString('en-US')} Kudos`)
    await expect(page.getByTestId('result-question')).toHaveCount(2)
    await expect(page.getByTestId('result-question').first()).toContainText('Weekly')
    await expectAccessible(page, at('app survey result'))
  })
}
