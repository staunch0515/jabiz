import type { APIRequestContext } from '@playwright/test'
import { completeQuiz, query } from './content'
import { fundedSponsor, searchHome, taker, untilLive } from './play'
import { adminToken, expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique } from './support'

/*
 * A publication's prerequisites (docs/quizbuks/plans/Q76-prerequisites.md): the sponsor's advanced quiz requires
 * passing its basic one. A taker's home lists the basic one only; the advanced one's page says what to complete
 * first, linking to it, and its start is refused; once the taker has passed the basic one, the home lists the
 * advanced one and it starts. Everything but the taker's pages is prepared through the API.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** A one-question quiz's publication of the sponsor's, live now; submitted after `before` (its prerequisites). */
async function publish(request: APIRequestContext, sponsor: { token: string; balance: number }, name: string,
  before?: (publicationId: string, revision: number) => Promise<void>) {
  const quiz = await completeQuiz(request, sponsor.token, { questions: 1 })
  const versionId = (await query(request, sponsor.token, 'qb.sponsor.quiz-versions', { quizId: quiz.quizId }))[0]
    .versionId
  const now = Date.now()
  const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', { versionId, name,
    regions: ['GLOBAL'], startAt: new Date(now - 60_000).toISOString(),
    endAt: new Date(now + 7 * 86_400_000).toISOString(), timeLimitSec: 300, passScore: 1, maxWinners: 100,
    tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 1, reward: 1 }] })
  const publicationId = saved.publicationId as string
  if (before) await before(publicationId, saved.revision as number)
  const submitted = await run(request, sponsor.token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  if (submitted.approval === 'PENDING') {
    await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId,
      decision: 'APPROVE' })
  }
  return publicationId
}

test('an advanced quiz waits until its basic one is passed', async ({ page, request }) => {
  test.setTimeout(180_000)
  const sponsor = await fundedSponsor(request)
  const basicsName = unique('Basics ')
  const advancedName = unique('Advanced ')
  const basics = await publish(request, sponsor, basicsName)
  const advanced = await publish(request, sponsor, advancedName, async (publicationId, revision) => {
    await run(request, sponsor.token, 'QB_PUBLICATION_PREREQUISITES_SAVE', { publicationId, baseRevision: revision,
      prerequisites: [{ publicationId: basics, requirement: 'PASSED' }] })
  })
  const me = await taker(request, 'Kai')
  await untilLive(request, me.token, basics)

  // Its start without the prerequisite is refused.
  const refused = await request.post('/api/processes/QB_ATTEMPT_START/latest', {
    headers: { Authorization: `Bearer ${me.token}`, 'Idempotency-Key': crypto.randomUUID() },
    data: { publicationId: advanced } })
  expect(refused.status()).toBe(422)
  expect(await refused.text()).toContain('QB_PREREQUISITE_NOT_MET')

  await page.setViewportSize({ width: 390, height: 844 })
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()
  await searchHome(page, advancedName)
  await expect(page.getByTestId('feed-card').filter({ hasText: advancedName })).toHaveCount(0)

  // The advanced one's page says what to complete first and links to it.
  await page.goto(`/taker/p/${advanced}`)
  const notice = page.getByTestId('detail-prerequisites')
  await expect(notice).toContainText(basicsName)
  await expect(page.getByTestId('detail-start')).toHaveCount(0)
  await expectAccessible(page, 'app start page with prerequisites')
  await notice.getByTestId('detail-prerequisite-link').click()
  await expect(page).toHaveURL(new RegExp(`/p/${basics}$`))

  // Passed through the API (the answering pages have their own tests): the advanced one is on the home and starts.
  const started = await run(request, me.token, 'QB_ATTEMPT_START', { publicationId: basics })
  const questions = started.questions as { no: number; options: { no: number; text: string }[] }[]
  await run(request, me.token, 'QB_ATTEMPT_SUBMIT', { attemptId: started.attemptId, publicationId: basics,
    answers: questions.map((q) => ({ questionNo: q.no,
      selected: q.options.filter((o) => o.text.startsWith('Right')).map((o) => o.no) })) })
  await page.goto('/taker/')
  await searchHome(page, advancedName)
  await expect(page.getByTestId('feed-card').filter({ hasText: advancedName })).toHaveCount(1)
  await page.goto(`/taker/p/${advanced}`)
  await expect(page.getByTestId('detail-start')).toBeEnabled()
  await expect(page.getByTestId('detail-prerequisites')).toHaveCount(0)
})
