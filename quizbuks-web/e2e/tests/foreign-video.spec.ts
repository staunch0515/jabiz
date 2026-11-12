import type { APIRequestContext } from '@playwright/test'
import { adminToken, expect, expectAccessible, prepareQuizbuks, REGION_UNKNOWN_IP_COUNTRY, run, signIn, test, unique }
  from './support'
import { fund, fundedSponsor, searchHome, taker, untilLive } from './play'
import { operatorWithLead, reviewerFor, signInOperatorDesk, sponsorOfOperator } from './operators'

/*
 * F-S11 (docs/quizbuks/plans/impl/F-S11.md): a publication whose quiz has a video mainland China cannot play (YouTube)
 * is not answered from mainland China. The application here has no address database: the browser's address (this
 * machine) is unknown and counts as the parameter qb.region.unknown-ip-country says, Japan for the other tests
 * (prepareQuizbuks). This test sets mainland China for itself and back, so the server refuses the start; the page's
 * question "where am I" (GET /api/client/region) is answered by the test, as the platform would for a mainland address.
 * The address database itself (a CN and a foreign address behind a trusted proxy) is the backend's ForeignVideoIT.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/**
 * A funded Sponsor's live publication whose quiz has a YouTube video (one question, the reward of its balance);
 * submitted and approved by a member of the Sponsor's operator.
 */
async function liveVideoPublication(request: APIRequestContext, token: string, label: string) {
  const balance = await fund(request, token)
  const quiz = await run(request, token, 'QB_QUIZ_SAVE', { title: unique(`Exception video quiz ${label} `),
    timeLimitSec: 300 })
  const quizId = quiz.quizId as string
  await run(request, token, 'QB_MATERIAL_SAVE', { quizId, kind: 'VIDEO_LINK', title: 'Film',
    url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ' })
  await run(request, token, 'QB_QUESTION_SAVE', { quizId, stem: 'What did the film show?', points: 1,
    options: [{ text: 'A song', correct: true }, { text: 'A map' }] })
  const version = await run(request, token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  const now = Date.now()
  const saved = await run(request, token, 'QB_PUBLICATION_SAVE', { versionId: version.versionId,
    name: unique(`Exception video run ${label} `), regions: ['GLOBAL'], startAt: new Date(now - 60_000).toISOString(),
    endAt: new Date(now + 7 * 86_400_000).toISOString(), timeLimitSec: 300, passScore: 1,
    tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 1, reward: balance }] })
  const publicationId = saved.publicationId as string
  const submitted = await run(request, token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  if (submitted.approval === 'PENDING') {
    await run(request, await reviewerFor(request, token), 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId,
      decision: 'APPROVE' })
  }
  return { publicationId }
}

/*
 * Both tests set the parameter that says which country an unknown address counts as, which is global: they are in a
 * serial describe (the file's one worker reads nothing meanwhile) and put it back to Japan.
 */
test.describe.serial('mainland China', () => {
  test('mainland China does not see or answer a publication with a YouTube video', async ({ page, request }) => {
    test.setTimeout(180_000)
    // No YouTube here: the player is a blank page.
    await page.route('https://www.youtube-nocookie.com/**', (route) => route.fulfill({ contentType: 'text/html',
      body: '<!doctype html><html lang="en"><title>Player</title></html>' }))
    const sponsor = await fundedSponsor(request)
    const quiz = await run(request, sponsor.token, 'QB_QUIZ_SAVE', { title: unique('Foreign video quiz '),
      timeLimitSec: 300 })
    const quizId = quiz.quizId as string
    await run(request, sponsor.token, 'QB_MATERIAL_SAVE', { quizId, kind: 'VIDEO_LINK', title: 'Film',
      url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ' })
    await run(request, sponsor.token, 'QB_QUESTION_SAVE', { quizId, stem: 'What did the film show?', points: 1,
      options: [{ text: 'A song', correct: true }, { text: 'A map' }] })
    const version = await run(request, sponsor.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
    const name = unique('Foreign video run ')
    const now = Date.now()
    const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', {
      versionId: version.versionId, name, regions: ['GLOBAL'],
      startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
      timeLimitSec: 300, passScore: 1, tiers: [{ fromScore: 0, toScore: 0, reward: 0 },
        { fromScore: 1, toScore: 1, reward: sponsor.balance }],
    })
    const publicationId = saved.publicationId as string
    const submitted = await run(request, sponsor.token, 'QB_PUBLICATION_SUBMIT', { publicationId })
    if (submitted.approval === 'PENDING') {
      await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId,
        decision: 'APPROVE' })
    }
    const me = await taker(request, 'Lin')
    await untilLive(request, me.token, publicationId)

    // The page asks where it is: mainland China.
    await page.route('**/api/client/region', (route) => route.fulfill({ contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify({ country: 'CN', mainlandChina: true, known: true }) }))
    await page.setViewportSize({ width: 390, height: 844 })
    await signIn(page, '/taker/', me.email)
    await expect(page.getByTestId('home')).toBeVisible()
    await searchHome(page, name)
    await expect(page.getByTestId('feed-card').filter({ hasText: name })).toHaveCount(0)
    // Opened from a notice or a link: why it cannot be opened, nothing of it shown.
    await page.goto(`/taker/p/${publicationId}`)
    await expect(page.getByTestId('detail-region-unavailable')).toContainText('not available in your region')
    await expect(page.getByTestId('detail-start')).toHaveCount(0)
    await expectAccessible(page, 'app publication not available in the region')

    // The server's own check: an unknown address counting as mainland China is refused, with the reason.
    const admin = await adminToken(request)
    await run(request, admin, 'PARAM_SET', { key: REGION_UNKNOWN_IP_COUNTRY, value: 'CN' })
    try {
      const refused = await request.post('/api/processes/QB_ATTEMPT_START/latest', {
        headers: { Authorization: `Bearer ${me.token}`, 'Idempotency-Key': crypto.randomUUID() },
        data: { publicationId } })
      expect(refused.status()).toBe(422)
      expect(await refused.text()).toContain('QB_PUBLICATION_REGION_VIDEO')
    } finally {
      await run(request, admin, 'PARAM_SET', { key: REGION_UNKNOWN_IP_COUNTRY, value: 'JP' })
    }

    // Elsewhere (the platform's answer for Japan), it is listed and starts.
    await page.unroute('**/api/client/region')
    await page.route('**/api/client/region', (route) => route.fulfill({ contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify({ country: 'JP', mainlandChina: false, known: true }) }))
    await page.goto('/taker/')
    await searchHome(page, name)
    await expect(page.getByTestId('feed-card').filter({ hasText: name })).toHaveCount(1)
    const started = await request.post('/api/processes/QB_ATTEMPT_START/latest', {
      headers: { Authorization: `Bearer ${me.token}`, 'Idempotency-Key': crypto.randomUUID() }, data: { publicationId } })
    expect(started.status()).toBe(200)
  })

  /*
   * F-F11c (docs/quizbuks/plans/impl/F-F11.md section 6.3): the exception a sample Sponsor needs. The operator's lead
   * sets it from the Sponsor's row in the operator's Sponsor list; the Sponsor's publications then start from mainland
   * China while the publication of another Sponsor, with the same kind of video, does not.
   */
  test('an operator sets a mainland China exception on its Sponsor, whose publication then starts from mainland China',
    async ({ page, request, browser }) => {
      test.setTimeout(300_000)
      await page.route('https://www.youtube-nocookie.com/**', (route) => route.fulfill({ contentType: 'text/html',
        body: '<!doctype html><html lang="en"><title>Player</title></html>' }))
      const operator = await operatorWithLead(request, 'Reg')
      const tag = unique('Exception ')
      const excepted = await sponsorOfOperator(request, operator, 'Eli', `${tag} Eli Co`)
      const plain = await sponsorOfOperator(request, operator, 'Pat', `${tag} Pat Co`)
      const withVideo = { excepted: await liveVideoPublication(request, excepted.token, 'excepted'),
        plain: await liveVideoPublication(request, plain.token, 'plain') }
      const me = await taker(request, 'Lin')
      await untilLive(request, me.token, withVideo.excepted.publicationId)
      await untilLive(request, me.token, withVideo.plain.publicationId)

      // The lead sets the exception on the first Sponsor from its row, with a reason (the dialog requires one).
      const deskContext = await browser.newContext()
      try {
        const lead = await deskContext.newPage()
        await signInOperatorDesk(lead, operator)
        await lead.goto(`/operator/qb/operator/sponsors?q=${encodeURIComponent(tag)}`)
        const row = (s: { userId: string }) => lead.locator(`[data-testid="sponsor-row"][data-id="${s.userId}"]`)
        await expect(row(plain).getByTestId('region-add-for-sponsor')).toBeVisible()
        await row(excepted).getByTestId('region-add-for-sponsor').click()
        const dialog = lead.getByTestId('region-exception-dialog')
        await dialog.getByTestId('region-exception-reason').fill('Sample: test mainland China access')
        await dialog.getByTestId('region-exception-save').click()
        await expect(row(excepted).getByTestId('region-in-force')).toBeVisible()
        // Only that Sponsor: the other row still offers the exception.
        await expect(row(plain).getByTestId('region-add-for-sponsor')).toBeVisible()
      } finally {
        await deskContext.close()
      }

      // From mainland China (the page asks where it is, and an address nobody knows counts as the parameter says).
      await page.route('**/api/client/region', (route) => route.fulfill({ contentType: 'application/json',
        headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify({ country: 'CN', mainlandChina: true, known: true }) }))
      await page.setViewportSize({ width: 390, height: 844 })
      const admin = await adminToken(request)
      await run(request, admin, 'PARAM_SET', { key: REGION_UNKNOWN_IP_COUNTRY, value: 'CN' })
      try {
        await signIn(page, '/taker/', me.email)
        await expect(page.getByTestId('home')).toBeVisible()
        // Without the exception: why it cannot be opened, and the server refuses a start.
        await page.goto(`/taker/p/${withVideo.plain.publicationId}`)
        await expect(page.getByTestId('detail-region-unavailable')).toContainText('not available in your region')
        await expect(page.getByTestId('detail-start')).toHaveCount(0)
        const refused = await request.post('/api/processes/QB_ATTEMPT_START/latest', {
          headers: { Authorization: `Bearer ${me.token}`, 'Idempotency-Key': crypto.randomUUID() },
          data: { publicationId: withVideo.plain.publicationId } })
        expect(refused.status()).toBe(422)
        expect(await refused.text()).toContain('QB_PUBLICATION_REGION_VIDEO')
        // With it: the same kind of publication is opened and started from the page.
        await page.goto(`/taker/p/${withVideo.excepted.publicationId}`)
        await expect(page.getByTestId('detail-region-unavailable')).toHaveCount(0)
        await page.getByTestId('detail-start').click()
        await expect(page.getByTestId('play-progress')).toHaveText('Question 1 of 1')
      } finally {
        await run(request, admin, 'PARAM_SET', { key: REGION_UNKNOWN_IP_COUNTRY, value: 'JP' })
      }
    })
})
