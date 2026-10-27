import { decideReview, publicationOf } from './publishing'
import { fundedSponsor, taker } from './play'
import { expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique } from './support'

/**
 * Customer matching (docs/quizbuks/plans/Q39-audience.md): the sponsor aims a draft at takers who like pets in the
 * wizard's step "Customer matching" and counts them (a rounded number); a taker who has told nothing sees the quiz
 * but is asked for their profile, tells the app they like pets, and can then start it.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('a sponsor aims a quiz at a profile; a taker who matches it can start it', async ({ page, request }) => {
  test.setTimeout(240_000)
  const sponsor = await fundedSponsor(request)
  const quiz = await run(request, sponsor.token, 'QB_QUIZ_SAVE', { title: 'Pet care basics', intro: null,
    timeLimitSec: 300 })
  const quizId = quiz.quizId as string
  await run(request, sponsor.token, 'QB_QUESTION_SAVE', { quizId, stem: 'How often should a dog drink water?',
    points: 1, options: [{ text: 'Whenever it wants', correct: true }, { text: 'Once a week' }] })
  const version = await run(request, sponsor.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  const name = unique('Pets ')
  const now = Date.now()
  const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', { versionId: version.versionId, name,
    regions: ['GLOBAL'], startAt: new Date(now - 60_000).toISOString(),
    endAt: new Date(now + 7 * 86_400_000).toISOString(), timeLimitSec: 300, passScore: 1,
    tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 1, reward: 10 }] })
  const publicationId = saved.publicationId as string

  // The sponsor: the step "Customer matching", a condition, its count, and the summary.
  await signIn(page, '/sponsor/', sponsor.email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(`/sponsor/publications/${publicationId}?step=2`)
  await expect(page.getByTestId('publication-step-title')).toHaveText('Customer matching')
  await expect(page.getByTestId('publication-regions')).toBeVisible()
  await page.getByTestId('audience-interest-on').click()
  await page.getByTestId('audience-interest-PETS').click()
  await expectAccessible(page, 'sponsor customer matching')
  await page.getByTestId('audience-count-button').click()
  // Rounded, never a small exact number: other runs may have added takers who like pets.
  await expect(page.getByTestId('audience-count-result')).toHaveText(/Fewer than 20 takers|About \d[\d,]* takers/,
    { timeout: 30_000 })
  await page.getByTestId('publication-next').click()
  await expect(page.getByTestId('summary-conditions')).toHaveText('Interests: Pets')
  await expect(page.getByTestId('publication-ready')).toBeVisible()

  const submitted = await run(request, sponsor.token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  if (submitted.approval === 'PENDING') await decideReview(request, sponsor.token, publicationId, 'APPROVE')
  await expect.poll(async () => (await publicationOf(request, sponsor.token, publicationId)).status,
    { message: 'the publication is live', timeout: 60_000 }).toBe('LIVE')

  // A taker who told nothing: the page, but no start; the feed leaves it out.
  const me = await taker(request, 'Pip')
  await signIn(page, '/taker/', me.email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(`/taker/p/${publicationId}`)
  await expect(page.getByTestId('detail-not-matched')).toBeVisible()
  await expect(page.getByTestId('detail-start')).toHaveCount(0)
  await expectAccessible(page, 'app quiz not matched')

  // Telling the app they like pets.
  await page.getByTestId('detail-complete-profile').click()
  await expect(page).toHaveURL(/\/taker\/account\?section=details/)
  const details = page.getByTestId('profile-details')
  await details.getByTestId('details-interest-PETS').click()
  await details.getByTestId('details-save').click()
  await expect(details.getByTestId('details-saved')).toBeVisible()
  await expectAccessible(page, 'app profile details')

  await page.goto(`/taker/p/${publicationId}`)
  await page.getByTestId('detail-start').click()
  await expect(page).toHaveURL(/\/play\/[^/]+$/)
})
