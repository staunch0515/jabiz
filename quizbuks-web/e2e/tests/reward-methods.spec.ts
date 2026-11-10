import { completeQuiz, query } from './content'
import { allowRewardMethods } from './publishing'
import {
  expect,
  expectAccessible,
  openArea,
  PASSWORD,
  prepareQuizbuks,
  run,
  signIn,
  takerArea,
  test,
} from './support'
import { approvedSponsor, takerInJapan } from './wallet'
import { reviewerFor } from './operators'

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
  await allowRewardMethods(request, sponsor.token, ['PLATFORM', method])

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
    await run(request, await reviewerFor(request, sponsor.token), 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId, decision: 'APPROVE' })
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

test('the Sponsor marks a cash reward paid and the Taker confirms it', async ({ page, request }) => {
  test.setTimeout(240_000)
  const owed = await cashRewardOwed(request)

  // The console: the reward owed, marked paid with a note.
  await signIn(page, '/sponsor/', owed.sponsor.email)
  await expect(page.getByTestId('dashboard-status')).toBeVisible()
  await openArea(page, 'prizes', 'cash')
  await expect(page).toHaveURL(/\/sponsor\/prizes\/cash$/)
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
  await takerArea(page, 'history').click()
  await expect(page.getByTestId('history-row').filter({ hasText: owed.name }).getByTestId('history-cash'))
    .toHaveAttribute('data-status', 'CONFIRMED')
})

test('a Taker collects a prize by scanning the code on the Sponsor\'s screen', async ({ page, request }) => {
  test.setTimeout(240_000)
  const owed = await rewardOwed(request, 'OFFLINE_REDEEM')

  // The console's redeem page: the rotating code, as a link the phone's camera would open.
  await signIn(page, '/sponsor/', owed.sponsor.email)
  await expect(page.getByTestId('dashboard-status')).toBeVisible()
  await openArea(page, 'prizes', 'redeem')
  await expect(page).toHaveURL(/\/sponsor\/prizes\/redeem$/)
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
  await page.goto('/sponsor/prizes/redeem')
  await expect(page.getByTestId('redeem-today-item').filter({ hasText: owed.code })).toBeVisible()
  await expect(page.getByTestId('redemption-row').filter({ hasText: owed.code })).toHaveAttribute('data-status',
    'REDEEMED')
})

test('a Taker collects a prize by scanning the Sponsor\'s screen with the app\'s own scanner (Q52)',
  async ({ page, request }) => {
    test.setTimeout(240_000)
    const owed = await rewardOwed(request, 'OFFLINE_REDEEM')

    // The QR code the sponsor's screen shows, as a picture.
    await signIn(page, '/sponsor/', owed.sponsor.email)
    await expect(page.getByTestId('dashboard-status')).toBeVisible()
    await page.goto('/sponsor/prizes/redeem')
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
    // On a 360 px phone the page is no wider than the screen (a wider one is zoomed out, and taps miss what they aim
    // at). F-F4: the scanner is the bottom menu's middle entry, raised, and its tab is the camera.
    await page.setViewportSize({ width: 360, height: 780 })
    const open = page.getByTestId('bottom-bar-scan')
    await expect(open).toBeVisible()
    await expect(open).toHaveAccessibleName('Scan')
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth))
      .toBeLessThanOrEqual(0)
    const box = (await open.boundingBox())!
    expect(Math.abs(box.x + box.width / 2 - 180), 'scanning is in the middle').toBeLessThanOrEqual(1)
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

test('a Taker finds the prize under "To collect" and shows the pass from the scanner in one step (F-F4)',
  async ({ page, request }) => {
    test.setTimeout(240_000)
    const owed = await rewardOwed(request, 'OFFLINE_REDEEM')
    await page.setViewportSize({ width: 390, height: 844 })
    await signIn(page, '/taker/', owed.taker.email)
    await expect(page.getByTestId('home')).toBeVisible()

    // The bottom menu's "Answered" says something waits, and so does the tab (one query, one number).
    await expect(takerArea(page, 'history')).toHaveAccessibleName(/to collect/)
    await takerArea(page, 'history').click()
    await page.getByTestId('page-tab-prizes').click()
    await expect(page).toHaveURL(/\/taker\/history\/prizes$/)
    const prize = page.getByTestId('prize-row').filter({ hasText: owed.name })
    await expect(prize).toHaveAttribute('data-status', 'DUE')
    await expect(prize).toContainText('A coffee at our counter')
    await expectAccessible(page, 'app to collect')

    // From the scanner: one prize to collect, so one tap on its button shows the pass.
    await takerArea(page, 'scan').click()
    const show = page.getByTestId('pass-show')
    await expect(show).toContainText('Show my pass (1)')
    await show.click()
    await expect(page.getByTestId('pass-dialog')).toBeVisible()
    await expect(page.getByTestId('pass-digits')).toHaveText(/^\d{3} \d{3}$/)
    await expectAccessible(page, 'app pass from the scanner')
    await page.keyboard.press('Escape')

    // The tab beside the camera lists the same pass, and needs no camera.
    await page.getByTestId('page-tab-pass').click()
    await expect(page).toHaveURL(/\/taker\/scan\/pass$/)
    await expect(page.getByTestId('my-pass')).toHaveCount(1)
    await expectAccessible(page, 'app show my pass')
  })

test('a Sponsor names a Taker to the staff by their ID, who hands out a prize at the counter (Q39)',
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

    // Kai reads their ID in "Me" (once signed in: the home is shown): Settings, Staff counter (F-F4).
    await signIn(page, '/taker/', kai.email)
    await expect(page.getByTestId('home')).toBeVisible()
    await takerArea(page, 'me').click()
    await expectAccessible(page, 'app account menu')
    await page.getByTestId('account-menu-id').click()
    await expect(page).toHaveURL(/\/taker\/account\/id$/)
    const kaiId = (await page.getByTestId('my-id').textContent())!.trim()
    expect(kaiId).toMatch(/^[0-9a-f-]{36}$/)
    await expect(page.getByTestId('account-staff')).toHaveCount(0)
    await expectAccessible(page, 'app account page: my ID')

    // The sponsor looks the ID up, sees Kai's name and adds them.
    await signOut()
    await signIn(page, '/sponsor/', owed.sponsor.email)
    await expect(page.getByTestId('dashboard-status')).toBeVisible()
    // Q79: a section of the sponsor hub.
    await openArea(page, 'hub', 'staff')
    await expect(page).toHaveURL(/\/sponsor\/profile\/staff$/)
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
    // F-F4: "Me", Settings, Staff counter, then the desk.
    await takerArea(page, 'me').click()
    await page.getByTestId('account-menu-id').click()
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
    await page.goto('/sponsor/prizes/redeem')
    await expect(page.getByTestId('redemption-row').filter({ hasText: owed.code }).getByTestId('redemption-staff'))
      .toContainText('Kai')
  })
