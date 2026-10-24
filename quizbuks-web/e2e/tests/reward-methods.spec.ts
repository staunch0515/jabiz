import { completeQuiz, query } from './content'
import { contentAdmin } from './legal'
import { adminToken, expect, expectAccessible, PASSWORD, prepareQuizbuks, rows, run, signIn, test, tokenOf } from './support'
import { approvedSponsor, takerInJapan } from './wallet'

/**
 * The ways a reward is paid (docs/quizbuks/plans/Q22-reward-methods.md, Q22a): an administrator allows a sponsor to
 * pay cash, the sponsor publishes a quiz paid that way, the taker passes it through the processes, then the sponsor
 * marks the reward paid in the console and the taker confirms it in the app. axe checks the new pages.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** A sponsor allowed to pay cash themselves, with a live quiz paid that way; the taker passed it. */
async function cashRewardOwed(request: Parameters<typeof approvedSponsor>[0]) {
  const sponsor = await approvedSponsor(request, 'Cash')
  const admin = await adminToken(request)
  const userId = (await rows(request, sponsor.token, 'urn:jabiz:dataset:sponsor:QbSponsor', []))[0].attributes.userId as string
  // Allowing the methods asks for a verified address, which the first administrator has not: a content administrator.
  const allower = await tokenOf(request, await contentAdmin(request, 'Rin'), PASSWORD, 'admin')
  await run(request, allower, 'QB_SPONSOR_REWARD_METHODS_SET', { userId, methods: ['PLATFORM', 'SPONSOR_CASH'],
    note: 'e2e' })

  const quiz = await completeQuiz(request, sponsor.token, { questions: 1 })
  const versionId = (await query(request, sponsor.token, 'qb.sponsor.quiz-versions', { quizId: quiz.quizId }))[0].versionId
  const now = Date.now()
  const name = `Cash quiz ${now}`
  const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', { versionId, name, regions: ['GLOBAL'],
    startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
    timeLimitSec: 300, passScore: 1, rewardMethod: 'SPONSOR_CASH',
    tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 1, reward: 300 }] })
  const publicationId = saved.publicationId as string
  const submitted = await run(request, sponsor.token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  if (submitted.approval === 'PENDING') {
    await run(request, admin, 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId, decision: 'APPROVE' })
  }
  const taker = await takerInJapan(request, 'Mei')
  await expect.poll(async () => {
    const response = await request.post('/api/queries/qb.app.publication', {
      headers: { Authorization: `Bearer ${taker.token}` },
      data: { params: { publicationId, at: new Date().toISOString(), country: 'JP' } },
    })
    if (!response.ok()) return false
    return ((await response.json()).items ?? [])[0]?.canStart === true
  }, { message: 'the publication goes live', timeout: 30_000 }).toBe(true)
  const started = await run(request, taker.token, 'QB_ATTEMPT_START', { publicationId })
  const questions = started.questions as { no: number; options: { no: number; text: string }[] }[]
  const answers = questions.map((q) => ({ questionNo: q.no,
    selected: q.options.filter((o) => o.text.startsWith('Right')).map((o) => o.no) }))
  const result = await run(request, taker.token, 'QB_ATTEMPT_SUBMIT', { attemptId: started.attemptId, publicationId,
    answers })
  expect(result.rewardStatus).toBe('CASH')
  // The submission names the way it is paid; the record itself (code, state) comes with the result (Q20's process).
  const read = await run(request, taker.token, 'QB_ATTEMPT_RESULT', { attemptId: started.attemptId })
  const cash = read.cash as { code: string; status: string }
  expect(cash.status).toBe('DUE')
  return { sponsor, taker, name, publicationId, attemptId: started.attemptId as string, code: cash.code }
}

test('the sponsor marks a cash reward paid and the taker confirms it', async ({ page, request }) => {
  test.setTimeout(240_000)
  const owed = await cashRewardOwed(request)

  // The console: the reward owed, marked paid with a note.
  await signIn(page, '/sponsor/', owed.sponsor.email)
  await page.goto('/sponsor/cash-rewards')
  const row = page.getByTestId('cash-row').filter({ hasText: owed.code })
  await expect(row).toHaveAttribute('data-status', 'DUE')
  await expect(row).toContainText('Mei')
  await expectAccessible(page, 'sponsor cash rewards')
  await row.getByTestId('cash-mark').click()
  await page.getByTestId('cash-mark-note').fill('Handed over at the counter')
  await expectAccessible(page, 'sponsor cash rewards: mark paid')
  await page.getByTestId('cash-mark-confirm').click()
  await expect(row).toHaveAttribute('data-status', 'MARKED_PAID')

  // The app: the home points at the reward; the result shows the code and takes the confirmation.
  await page.context().clearCookies()
  await page.evaluate(() => window.localStorage.clear())
  await signIn(page, '/', owed.taker.email)
  const notice = page.getByTestId('home-cash')
  await expect(notice).toBeVisible()
  await expect(notice.getByTestId('home-cash-item').filter({ hasText: owed.code })).toHaveAttribute('data-status',
    'MARKED_PAID')
  await expectAccessible(page, 'app home with a cash reward')
  await notice.getByRole('link').first().click()
  await expect(page).toHaveURL(new RegExp(`/result/${owed.attemptId}$`))
  const reward = page.getByTestId('result-reward')
  await expect(reward).toHaveAttribute('data-cash-status', 'MARKED_PAID')
  await expect(reward.getByTestId('cash-code')).toHaveText(owed.code)
  await expectAccessible(page, 'app result with a cash reward')
  await reward.getByTestId('cash-confirm').click()
  await expect(reward).toHaveAttribute('data-cash-status', 'CONFIRMED')
  await expect(reward.getByTestId('cash-confirm')).toHaveCount(0)

  // The history and the home agree.
  await page.getByTestId('nav-history').click()
  await expect(page.getByTestId('history-cash').filter({ hasText: owed.code }).first()).toHaveAttribute('data-status',
    'CONFIRMED')
})
