import { adminToken, expect, expectAccessible, prepareQuizbuks, REGION_UNKNOWN_IP_COUNTRY, run, signIn, test, unique }
  from './support'
import { fundedSponsor, searchHome, taker, untilLive } from './play'

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
