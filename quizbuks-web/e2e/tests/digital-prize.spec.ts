import { signInAdmin } from './admin'
import { completeQuiz, query } from './content'
import { contentAdmin } from './legal'
import { adminToken, expect, expectAccessible, PASSWORD, prepareQuizbuks, rows, run, signIn, test, tokenOf } from './support'
import { approvedSponsor, takerInJapan } from './wallet'

/**
 * Digital prizes (docs/quizbuks/plans/Q33-digital-prize.md, Q33a): a sponsor allowed the method publishes a quiz whose
 * prize is one shared link, or codes from a pool loaded in the console; the taker passes it, sees the prize only after
 * tapping "Show prize", copies it and confirms it; the sponsor's and the administrator's lists show where it stands,
 * never the content. axe checks the new pages.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

type Delivery = 'SHARED' | 'POOL'

const LINK = 'https://capitals.example/premium?code=WELCOME-2026'

/** A sponsor allowed digital prizes, with a saved (not yet submitted) publication delivering them as `delivery`. */
async function digitalDraft(request: Parameters<typeof approvedSponsor>[0], delivery: Delivery) {
  const sponsor = await approvedSponsor(request, 'Digi')
  const userId = (await rows(request, sponsor.token, 'urn:jabiz:dataset:sponsor:QbSponsor', []))[0].attributes.userId as string
  // Allowing the methods asks for a verified address, which the first administrator has not: a content administrator.
  const allower = await tokenOf(request, await contentAdmin(request, 'Rin'), PASSWORD, 'admin')
  await run(request, allower, 'QB_SPONSOR_REWARD_METHODS_SET', { userId, methods: ['PLATFORM', 'DIGITAL_PRIZE'],
    note: 'e2e' })
  const quiz = await completeQuiz(request, sponsor.token, { questions: 1 })
  const versionId = (await query(request, sponsor.token, 'qb.sponsor.quiz-versions', { quizId: quiz.quizId }))[0].versionId
  const now = Date.now()
  const name = `${delivery === 'SHARED' ? 'Link' : 'Code'} quiz ${now}`
  const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', { versionId, name, regions: ['GLOBAL'],
    startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
    timeLimitSec: 300, passScore: 1, maxWinners: 2, rewardMethod: 'DIGITAL_PRIZE', digitalDelivery: delivery,
    digitalDescription: delivery === 'SHARED' ? 'A month of Premium' : 'A 1000 yen gift code',
    digitalContent: delivery === 'SHARED' ? LINK : null,
    tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 1, reward: 300 }] })
  return { sponsor, name, publicationId: saved.publicationId as string }
}

/** Submits the draft (approved when the review is on) and waits until a taker can start it. */
async function goLive(request: Parameters<typeof approvedSponsor>[0], sponsorToken: string, publicationId: string,
  takerToken: string) {
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
}

/** The taker passes the one-question quiz; the reward is a digital prize. */
async function passes(request: Parameters<typeof approvedSponsor>[0], takerToken: string, publicationId: string) {
  const started = await run(request, takerToken, 'QB_ATTEMPT_START', { publicationId })
  const questions = started.questions as { no: number; options: { no: number; text: string }[] }[]
  const answers = questions.map((q) => ({ questionNo: q.no,
    selected: q.options.filter((o) => o.text.startsWith('Right')).map((o) => o.no) }))
  const result = await run(request, takerToken, 'QB_ATTEMPT_SUBMIT', { attemptId: started.attemptId, publicationId,
    answers })
  expect(result.rewardStatus).toBe('DIGITAL')
  return started.attemptId as string
}

async function signOut(page: Parameters<typeof signIn>[0]) {
  await page.context().clearCookies()
  await page.evaluate(() => {
    window.localStorage.clear()
    window.sessionStorage.clear()
  })
}

test('a shared link is handed to the winner, shown on request, copied and confirmed', async ({ page, request,
  context }) => {
  test.setTimeout(240_000)
  const draft = await digitalDraft(request, 'SHARED')
  const taker = await takerInJapan(request, 'Mei')
  await goLive(request, draft.sponsor.token, draft.publicationId, taker.token)
  const attemptId = await passes(request, taker.token, draft.publicationId)
  // The result never carries the content; only the open does.
  const read = await run(request, taker.token, 'QB_ATTEMPT_RESULT', { attemptId })
  expect((read.digital as { status: string }).status).toBe('DELIVERED')
  expect(JSON.stringify(read)).not.toContain('WELCOME')

  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await signIn(page, '/taker/', taker.email)
  await expect(page.getByTestId('home')).toBeVisible()
  await page.goto(`/taker/result/${attemptId}`)
  const reward = page.getByTestId('result-reward')
  await expect(reward).toHaveAttribute('data-status', 'DIGITAL')
  await expect(reward).toHaveAttribute('data-digital-status', 'DELIVERED')
  await expect(reward).toContainText('A month of Premium')
  await expect(reward.getByTestId('digital-content')).toHaveCount(0)
  await expectAccessible(page, 'app result with a digital prize')
  await reward.getByTestId('digital-show').click()
  await expect(reward.getByTestId('digital-content')).toHaveText(LINK)
  await expect(reward.getByTestId('digital-link')).toHaveAttribute('href', LINK)
  await reward.getByTestId('digital-copy').click()
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(LINK)
  await expectAccessible(page, 'app result with a digital prize shown')
  await reward.getByTestId('digital-confirm').click()
  await expect(reward).toHaveAttribute('data-digital-status', 'CONFIRMED')
  await expect(reward.getByTestId('digital-confirm')).toHaveCount(0)
  // The history agrees.
  await page.getByTestId('nav-history').click()
  await expect(page.getByTestId('history-row').filter({ hasText: draft.name }).getByTestId('history-digital'))
    .toHaveAttribute('data-status', 'CONFIRMED')

  // The console lists the prize as confirmed, with the taker and without the content.
  await signOut(page)
  await signIn(page, '/sponsor/', draft.sponsor.email)
  await expect(page.getByTestId('dashboard-status')).toBeVisible()
  await page.getByRole('link', { name: 'Digital prizes' }).click()
  await expect(page).toHaveURL(/\/sponsor\/digital-prizes$/)
  const row = page.getByTestId('digital-row').filter({ hasText: 'Mei' })
  await expect(row).toHaveAttribute('data-status', 'CONFIRMED')
  await expect(row).not.toContainText('WELCOME')
  await expectAccessible(page, 'sponsor digital prizes')
})

test('codes are loaded into the pool in the console and handed out one each', async ({ page, request }) => {
  test.setTimeout(240_000)
  const draft = await digitalDraft(request, 'POOL')
  // Submitting an empty pool is refused with how short it is.
  const refused = await request.post('/api/processes/QB_PUBLICATION_SUBMIT/latest', {
    headers: { Authorization: `Bearer ${draft.sponsor.token}` }, data: { input: { publicationId: draft.publicationId } } })
  expect(refused.status()).toBe(422)
  expect(JSON.stringify(await refused.json())).toContain('QB_DIGITAL_POOL_SHORT')

  // The console's pool panel: two codes pasted, counted; the codes themselves are not listed.
  await signIn(page, '/sponsor/', draft.sponsor.email)
  await expect(page.getByTestId('dashboard-status')).toBeVisible()
  await page.goto(`/sponsor/publications/${draft.publicationId}`)
  const pool = page.getByTestId('digital-pool')
  await expect(pool).toBeVisible()
  await pool.getByTestId('digital-pool-lines').fill('GIFT-0001-AAAA\nGIFT-0002-BBBB\n')
  await expectAccessible(page, 'sponsor publication with a code pool')
  await pool.getByTestId('digital-pool-load').click()
  await expect(pool.getByTestId('digital-pool-available')).toHaveText(/2/)
  await expect(pool.getByTestId('digital-code-row')).toHaveCount(2)
  await expect(pool).toContainText('…AAAA')
  await expect(pool).not.toContainText('GIFT-0001')

  // Two takers pass: the codes go out in the order loaded.
  const first = await takerInJapan(request, 'Aki')
  const second = await takerInJapan(request, 'Yui')
  await goLive(request, draft.sponsor.token, draft.publicationId, first.token)
  const firstAttempt = await passes(request, first.token, draft.publicationId)
  const secondAttempt = await passes(request, second.token, draft.publicationId)
  const opened = await run(request, second.token, 'QB_DIGITAL_PRIZE_OPEN', {
    digitalPrizeId: ((await run(request, second.token, 'QB_ATTEMPT_RESULT', { attemptId: secondAttempt }))
      .digital as { digitalPrizeId: string }).digitalPrizeId })
  expect(opened.prizeContent).toBe('GIFT-0002-BBBB')

  // The first taker sees their code in the app.
  await signOut(page)
  await signIn(page, '/taker/', first.email)
  await expect(page.getByTestId('home')).toBeVisible()
  await page.goto(`/taker/result/${firstAttempt}`)
  const reward = page.getByTestId('result-reward')
  await expect(reward).toHaveAttribute('data-digital-status', 'DELIVERED')
  await reward.getByTestId('digital-show').click()
  await expect(reward.getByTestId('digital-content')).toHaveText('GIFT-0001-AAAA')
  await expect(reward.getByTestId('digital-link')).toHaveCount(0)

  // The pool is used up; the administrator's list has both prizes with their hints.
  await signOut(page)
  await signIn(page, '/sponsor/', draft.sponsor.email)
  await expect(page.getByTestId('dashboard-status')).toBeVisible()
  await page.goto(`/sponsor/publications/${draft.publicationId}`)
  await expect(page.getByTestId('digital-pool-available')).toHaveText(/0/)
  await expect(page.getByTestId('digital-pool-issued')).toHaveText(/2/)
  await signOut(page)
  await signInAdmin(page)
  await page.goto('/admin/qb/digital-prizes')
  const admin = page.getByTestId('digital-row').filter({ hasText: draft.name })
  await expect(admin).toHaveCount(2)
  await expect(page.getByTestId('digital-prizes')).toContainText('…BBBB')
  await expect(page.getByTestId('digital-prizes')).not.toContainText('GIFT-0002')
  await expectAccessible(page, 'admin digital prizes')
})
