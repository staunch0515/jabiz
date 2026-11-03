import type { APIRequestContext } from '@playwright/test'
import { completeQuiz, query } from './content'
import { adminToken, expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique } from './support'
import { fundedSponsor, takerInJapan } from './wallet'

/*
 * Conversations between takers and sponsors (docs/quizbuks/plans/Q82-conversations.md): a taker who completed a quiz
 * writes to its sponsor from the result page; the sponsor finds it waiting in the console's message centre (with the
 * menu's badge), answers; the taker's inbox tells them, and the conversation shows the reply. axe checks the new
 * pages.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** A live one-question quiz of the sponsor, a small reward on a pass (the server requires one), open to every region. */
async function liveQuiz(request: APIRequestContext, sponsorToken: string, takerToken: string) {
  const quiz = await completeQuiz(request, sponsorToken, { questions: 1 })
  const versionId = (await query(request, sponsorToken, 'qb.sponsor.quiz-versions', { quizId: quiz.quizId }))[0]
    .versionId
  const now = Date.now()
  const name = unique('Ask us ')
  const saved = await run(request, sponsorToken, 'QB_PUBLICATION_SAVE', { versionId, name, regions: ['GLOBAL'],
    startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
    timeLimitSec: 300, passScore: 1, budgetCap: 50, maxWinners: 5,
    tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 1, reward: 10 }] })
  const publicationId = saved.publicationId as string
  const submitted = await run(request, sponsorToken, 'QB_PUBLICATION_SUBMIT', { publicationId })
  if (submitted.approval === 'PENDING') {
    await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId,
      decision: 'APPROVE' })
  }
  await expect.poll(async () => {
    const response = await request.post('/api/queries/qb.app.publication', {
      headers: { Authorization: `Bearer ${takerToken}` },
      data: { params: { publicationId, at: new Date().toISOString(), country: 'JP' } },
    })
    if (!response.ok()) return false
    return ((await response.json()).items ?? [])[0]?.canStart === true
  }, { message: 'the publication goes live', timeout: 30_000 }).toBe(true)
  return { publicationId, name }
}

test('a taker writes to the sponsor from a result, the sponsor answers, the taker reads it', async ({ browser,
  baseURL, request }) => {
  test.setTimeout(240_000)
  const company = unique('Talk Co ')
  // The smallest top-up (qb.topup.min, 1000); the reward is 100.
  const sponsor = await fundedSponsor(request, 1000, 'Tal', company)
  const taker = await takerInJapan(request, 'Kai')
  const quiz = await liveQuiz(request, sponsor.token, taker.token)
  const started = await run(request, taker.token, 'QB_ATTEMPT_START', { publicationId: quiz.publicationId })
  await run(request, taker.token, 'QB_ATTEMPT_SUBMIT', { attemptId: started.attemptId,
    publicationId: quiz.publicationId, answers: [] })

  // The taker: the result page's "Contact the sponsor", a first message, the conversation.
  const takerContext = await browser.newContext({ baseURL })
  const app = await takerContext.newPage()
  await signIn(app, '/taker/', taker.email)
  await expect(app).not.toHaveURL(/\/login/)
  await app.goto(`/taker/result/${started.attemptId}`)
  const contact = app.getByTestId('result-contact-sponsor')
  await expect(contact).toBeVisible()
  await contact.click()
  const dialog = app.getByTestId('contact-sponsor-dialog')
  await expect(dialog).toContainText(company)
  await expectAccessible(app, 'app contact the sponsor')
  // Plain text: an address is refused with the server's reason.
  await dialog.getByTestId('contact-sponsor-body').fill('Write to me at kai@example.com')
  await dialog.getByTestId('contact-sponsor-send').click()
  await expect(dialog.getByTestId('contact-sponsor-error')).toBeVisible()
  await dialog.getByTestId('contact-sponsor-body').fill('Is there a prize next time?')
  await dialog.getByTestId('contact-sponsor-send').click()
  await expect(app).toHaveURL(/\/taker\/conversations\//)
  await expect(app.getByTestId('conversation-message')).toHaveCount(1)
  await expectAccessible(app, 'app conversation')

  // The sponsor: the badge, the waiting conversation, the reply.
  const sponsorContext = await browser.newContext({ baseURL })
  const desk = await sponsorContext.newPage()
  await signIn(desk, '/sponsor/', sponsor.email)
  await expect(desk).not.toHaveURL(/\/login/)
  await desk.goto('/sponsor/messages?tab=conversations')
  const row = desk.getByTestId('conversation-row').filter({ hasText: quiz.name })
  await expect(row).toBeVisible()
  await expect(row.getByTestId('conversation-waiting')).toBeVisible()
  await expectAccessible(desk, 'console conversations')
  await row.getByTestId('conversation-open').click()
  await expect(desk.getByTestId('conversation-messages')).toContainText('Is there a prize next time?')
  await expect(desk.getByTestId('conversation-rewards-none')).toBeVisible()
  await expectAccessible(desk, 'console conversation')
  await desk.getByTestId('conversation-body').fill('Yes, from next month.')
  await desk.getByTestId('conversation-send').click()
  await expect(desk.getByTestId('conversation-message')).toHaveCount(2)
  await sponsorContext.close()

  // The taker: the inbox tells them (its events are delivered in the background), the conversation shows it.
  await expect.poll(async () => {
    const response = await request.post('/api/queries/qb.app.conversations-unread', {
      headers: { Authorization: `Bearer ${taker.token}` }, data: { params: {} } })
    return response.ok() ? Number(((await response.json()).items ?? [])[0]?.unread ?? 0) : 0
  }, { message: 'the reply is unread', timeout: 30_000 }).toBe(1)
  await app.goto('/taker/inbox')
  await expect(app.getByTestId('inbox-conversations-unread')).toBeVisible()
  await app.getByTestId('inbox-conversations').click()
  await app.getByTestId('conversation-row').filter({ hasText: company }).click()
  await expect(app.getByTestId('conversation-message')).toHaveCount(2)
  await expect(app.getByTestId('conversation-messages')).toContainText('Yes, from next month.')
  await takerContext.close()
})
