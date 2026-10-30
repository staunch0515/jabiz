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

type Method = 'SPONSOR_CASH' | 'OFFLINE_REDEEM'

/**
 * A sponsor allowed to settle rewards themselves (cash, or a prize at their place), with a live quiz paid that way;
 * the taker passed it.
 */
async function rewardOwed(request: Parameters<typeof approvedSponsor>[0], method: Method) {
  const sponsor = await approvedSponsor(request, method === 'SPONSOR_CASH' ? 'Cash' : 'Prize')
  const admin = await adminToken(request)
  const userId = (await rows(request, sponsor.token, 'urn:jabiz:dataset:sponsor:QbSponsor', []))[0].attributes.userId as string
  // Allowing the methods asks for a verified address, which the first administrator has not: a content administrator.
  const allower = await tokenOf(request, await contentAdmin(request, 'Rin'), PASSWORD, 'admin')
  await run(request, allower, 'QB_SPONSOR_REWARD_METHODS_SET', { userId, methods: ['PLATFORM', method],
    note: 'e2e' })

  const quiz = await completeQuiz(request, sponsor.token, { questions: 1 })
  const versionId = (await query(request, sponsor.token, 'qb.sponsor.quiz-versions', { quizId: quiz.quizId }))[0].versionId
  const now = Date.now()
  const name = `${method === 'SPONSOR_CASH' ? 'Cash' : 'Prize'} quiz ${now}`
  const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', { versionId, name, regions: ['GLOBAL'],
    startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
    timeLimitSec: 300, passScore: 1, rewardMethod: method,
    redeemDescription: method === 'OFFLINE_REDEEM' ? 'A coffee at our counter' : null,
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
  expect(result.rewardStatus).toBe(method === 'SPONSOR_CASH' ? 'CASH' : 'REDEEM')
  // The submission names the way it is paid; the record itself (code, state) comes with the result (Q20's process).
  const read = await run(request, taker.token, 'QB_ATTEMPT_RESULT', { attemptId: started.attemptId })
  const record = (method === 'SPONSOR_CASH' ? read.cash : read.redeem) as { code: string; status: string }
  expect(record.status).toBe('DUE')
  return { sponsor, taker, name, publicationId, attemptId: started.attemptId as string, code: record.code }
}

const cashRewardOwed = (request: Parameters<typeof approvedSponsor>[0]) => rewardOwed(request, 'SPONSOR_CASH')

test('the sponsor marks a cash reward paid and the taker confirms it', async ({ page, request }) => {
  test.setTimeout(240_000)
  const owed = await cashRewardOwed(request)

  // The console: the reward owed, marked paid with a note.
  await signIn(page, '/sponsor/', owed.sponsor.email)
  await expect(page.getByTestId('dashboard-status')).toBeVisible()
  await page.getByRole('link', { name: 'Cash rewards' }).click()
  await expect(page).toHaveURL(/\/sponsor\/cash-rewards$/)
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
  await page.evaluate(() => {
    window.localStorage.clear()
    window.sessionStorage.clear()
  })
  await signIn(page, '/taker/', owed.taker.email)
  await expect(page.getByTestId('home')).toBeVisible()
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
  await expect(page.getByTestId('history-row').filter({ hasText: owed.name }).getByTestId('history-cash'))
    .toHaveAttribute('data-status', 'CONFIRMED')
})

test('a taker collects a prize by scanning the code on the sponsor\'s screen', async ({ page, request }) => {
  test.setTimeout(240_000)
  const owed = await rewardOwed(request, 'OFFLINE_REDEEM')

  // The console's redeem page: the rotating code, as a link the phone's camera would open.
  await signIn(page, '/sponsor/', owed.sponsor.email)
  await expect(page.getByTestId('dashboard-status')).toBeVisible()
  await page.getByRole('link', { name: 'Prizes', exact: true }).click()
  await expect(page).toHaveURL(/\/sponsor\/redeem$/)
  await expect(page.getByTestId('redeem-rotating-image')).toBeVisible()
  const link = (await page.getByTestId('redeem-rotating-link').textContent())!.trim()
  expect(link).toMatch(/\/redeem\/[A-Za-z0-9_-]{30,}$/)
  await page.getByTestId('redeem-printed-show').click()
  await expect(page.getByTestId('redeem-printed-image')).toBeVisible()
  await expectAccessible(page, 'sponsor redeem page')

  // The taker, signed out, opens the scanned link: signing in leads back to it; one tap collects the prize.
  await page.context().clearCookies()
  await page.evaluate(() => {
    window.localStorage.clear()
    window.sessionStorage.clear()
  })
  const path = new URL(link).pathname
  await page.goto(path)
  await expect(page).toHaveURL(/\/login\?next=/)
  await page.getByTestId('login-user-name').fill(owed.taker.email)
  await page.getByPlaceholder('Password', { exact: true }).fill(PASSWORD)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  const redeem = page.getByTestId('redeem-page')
  await expect(redeem).toBeVisible()
  await expect(redeem).toHaveAttribute('data-kind', 'ROTATING')
  const prize = page.getByTestId('redeem-prize')
  await expect(prize).toContainText('A coffee at our counter')
  await expect(prize).toContainText(owed.code)
  await expectAccessible(page, 'app redeem page')
  await prize.getByTestId('redeem-collect').click()
  const done = page.getByTestId('redeem-done')
  await expect(done).toHaveAttribute('data-status', 'REDEEMED')
  await expect(done.getByTestId('redeem-code')).toHaveText(owed.code)
  await expectAccessible(page, 'app redeem page: collected')

  // The result and the history say so; the sponsor's page lists it among today's.
  await done.getByTestId('redeem-result-link').click()
  await expect(page.getByTestId('result-reward')).toHaveAttribute('data-redeem-status', 'REDEEMED')
  await page.context().clearCookies()
  await page.evaluate(() => {
    window.localStorage.clear()
    window.sessionStorage.clear()
  })
  await signIn(page, '/sponsor/', owed.sponsor.email)
  await expect(page.getByTestId('dashboard-status')).toBeVisible()
  await page.goto('/sponsor/redeem')
  await expect(page.getByTestId('redeem-today-item').filter({ hasText: owed.code })).toBeVisible()
  await expect(page.getByTestId('redemption-row').filter({ hasText: owed.code })).toHaveAttribute('data-status',
    'REDEEMED')
})

test('a taker collects a prize by scanning the sponsor\'s screen with the app\'s own scanner (Q52)',
  async ({ page, request }) => {
    test.setTimeout(240_000)
    const owed = await rewardOwed(request, 'OFFLINE_REDEEM')

    // The QR code the sponsor's screen shows, as a picture.
    await signIn(page, '/sponsor/', owed.sponsor.email)
    await expect(page.getByTestId('dashboard-status')).toBeVisible()
    await page.goto('/sponsor/redeem')
    const code = page.getByTestId('redeem-rotating-image')
    await expect(code).toBeVisible()
    const picture = `data:image/png;base64,${(await code.screenshot()).toString('base64')}`

    // The taker's phone: its camera sees that picture (a canvas drawing it, streamed), so the app decodes it itself.
    await page.context().clearCookies()
    await page.evaluate(() => {
      window.localStorage.clear()
      window.sessionStorage.clear()
    })
    await page.addInitScript((source: string) => {
      const image = new Image()
      image.src = source
      const canvas = document.createElement('canvas')
      canvas.width = 640
      canvas.height = 640
      const draw = () => {
        const context = canvas.getContext('2d')!
        context.fillStyle = '#ffffff'
        context.fillRect(0, 0, canvas.width, canvas.height)
        // The code only once the test has seen the scanner: it opens the prize as soon as it reads one.
        const shown = (window as unknown as { qbShowCode?: boolean }).qbShowCode === true
        if (shown && image.complete && image.naturalWidth) context.drawImage(image, 120, 120, 400, 400)
      }
      navigator.mediaDevices.getUserMedia = async () => {
        draw()
        window.setInterval(draw, 100)
        return canvas.captureStream(10)
      }
    }, picture)
    await signIn(page, '/taker/', owed.taker.email)
    const open = page.getByTestId('scan-open')
    await expect(open).toBeVisible()
    await open.click()
    await expect(page.getByTestId('scan-page')).toBeVisible()
    await expectAccessible(page, 'app scanner')
    await page.evaluate(() => {
      (window as unknown as { qbShowCode: boolean }).qbShowCode = true
    })

    // The scanned code opens its page in the signed-in app; one tap collects the prize.
    const redeem = page.getByTestId('redeem-page')
    await expect(redeem).toBeVisible({ timeout: 20_000 })
    await expect(page).toHaveURL(/\/taker\/redeem\/[A-Za-z0-9_-]{30,}$/)
    await expect(redeem).toHaveAttribute('data-kind', 'ROTATING')
    const prize = page.getByTestId('redeem-prize')
    await expect(prize).toContainText(owed.code)
    await prize.getByTestId('redeem-collect').click()
    await expect(page.getByTestId('redeem-done')).toHaveAttribute('data-status', 'REDEEMED')
  })

test('a sponsor names a taker to the staff by their ID, who hands out a prize at the counter (Q39)',
  async ({ page, request }) => {
    test.setTimeout(240_000)
    const owed = await rewardOwed(request, 'OFFLINE_REDEEM')
    const kai = await takerInJapan(request, 'Kai')
    const signOut = async () => {
      await page.context().clearCookies()
      await page.evaluate(() => {
        window.localStorage.clear()
        window.sessionStorage.clear()
      })
    }

    // Kai reads their ID on the app's account page (once signed in: the home is shown).
    await signIn(page, '/taker/', kai.email)
    await expect(page.getByTestId('home')).toBeVisible()
    await page.goto('/taker/account')
    const kaiId = (await page.getByTestId('my-id').textContent())!.trim()
    expect(kaiId).toMatch(/^[0-9a-f-]{36}$/)
    await expect(page.getByTestId('account-staff')).toHaveCount(0)
    await expectAccessible(page, 'app account page: my ID')

    // The sponsor looks the ID up, sees Kai's name and adds them.
    await signOut()
    await signIn(page, '/sponsor/', owed.sponsor.email)
    await expect(page.getByTestId('dashboard-status')).toBeVisible()
    await page.getByRole('link', { name: 'Staff', exact: true }).click()
    await expect(page).toHaveURL(/\/sponsor\/staff$/)
    await page.getByTestId('staff-id').fill(kaiId)
    await page.getByTestId('staff-find').click()
    await expect(page.getByTestId('staff-candidate')).toContainText('Kai')
    await page.getByTestId('staff-add-confirm').click()
    await expect(page.getByTestId('staff-row').filter({ hasText: kaiId })).toBeVisible()
    await expectAccessible(page, 'sponsor staff page')

    // Kai opens the counter from the account page: the sponsor's code, in Kai's name.
    await signOut()
    await signIn(page, '/taker/', kai.email)
    await expect(page.getByTestId('home')).toBeVisible()
    await page.goto('/taker/account')
    await page.getByTestId('staff-desk-link').click()
    await expect(page).toHaveURL(/\/taker\/staff$/)
    await expect(page.getByTestId('staff-code-image')).toBeVisible()
    const link = (await page.getByTestId('staff-code-link').textContent())!.trim()
    expect(link).toMatch(/\/taker\/redeem\/[A-Za-z0-9_-]{30,}$/)
    await expectAccessible(page, 'app staff counter')

    // Mei scans it (through the processes): collected, handed over by Kai, as both pages show.
    const scannedKey = decodeURIComponent(link.slice(link.lastIndexOf('/') + 1))
    const checked = await run(request, owed.taker.token, 'QB_REDEEM_TOKEN_CHECK', { scannedKey })
    const redemptionId = (checked.prizes as { redemptionId: string }[])[0].redemptionId
    const claimed = await run(request, owed.taker.token, 'QB_REDEMPTION_CLAIM', { scannedKey, redemptionId })
    expect(claimed.status).toBe('REDEEMED')
    // The counter lists what Kai handed over today.
    await expect(page.getByTestId('staff-today-item').filter({ hasText: owed.code })).toBeVisible()
    await signOut()
    await signIn(page, '/sponsor/', owed.sponsor.email)
    await expect(page.getByTestId('dashboard-status')).toBeVisible()
    await page.goto('/sponsor/redeem')
    await expect(page.getByTestId('redemption-row').filter({ hasText: owed.code }).getByTestId('redemption-staff'))
      .toContainText('Kai')
  })
