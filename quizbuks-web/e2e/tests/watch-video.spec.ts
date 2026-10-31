import { adminToken, expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique } from './support'
import { fundedSponsor, searchHome, taker, untilLive } from './play'

/*
 * The video to watch (docs/quizbuks/plans/Q19-embedded-video.md): a sponsor ticks "Watching this video is enough to
 * answer" on a YouTube video in the console; once the publication is live, a taker's home shows the video's own player
 * (YouTube without cookies, not playing by itself) with what answering earns and a Start button that starts the quiz.
 * The player is a stub page here: the test does not reach YouTube.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

const PLAYER = 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'

test('a video to watch is shown on the home with its reward and starts the quiz', async ({ page, request }) => {
  test.setTimeout(180_000)
  await page.route('https://www.youtube-nocookie.com/**', (route) => route.fulfill({ contentType: 'text/html',
    body: '<!doctype html><html lang="en"><title>Player</title><button>Play</button></html>' }))
  const sponsor = await fundedSponsor(request)
  const quiz = await run(request, sponsor.token, 'QB_QUIZ_SAVE', { title: unique('Watch quiz '), timeLimitSec: 300 })
  const quizId = quiz.quizId as string

  await signIn(page, '/sponsor/', sponsor.email)
  // Signed in once the console has left the sign-in page (going elsewhere before would load it signed out).
  await expect(page).not.toHaveURL(/\/login/)
  await expect(page.getByRole('link', { name: 'Quizzes' })).toBeVisible()
  await page.goto(`/sponsor/quizzes/${quizId}`)
  await page.getByTestId('tab-materials').click()
  await page.getByTestId('material-add').click()
  const dialog = page.getByTestId('material-dialog')
  await dialog.getByRole('radio', { name: /video/i }).click()
  await dialog.getByTestId('material-title').fill('Watch this first')
  await dialog.getByTestId('material-url').fill('https://www.youtube.com/watch?v=dQw4w9WgXcQ')
  await dialog.getByTestId('material-watch').click()
  await expectAccessible(page, 'sponsor video to watch')
  await dialog.getByTestId('material-save').click()
  await expect(page.getByTestId('material-watch-badge')).toBeVisible()

  await run(request, sponsor.token, 'QB_QUESTION_SAVE', { quizId, stem: 'What did the video show?', points: 1,
    options: [{ text: 'A song', correct: true }, { text: 'A map' }] })
  const version = await run(request, sponsor.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  const name = unique('Watch run ')
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
  const me = await taker(request, 'Vic')
  await untilLive(request, me.token, publicationId)

  await page.context().clearCookies()
  await page.setViewportSize({ width: 390, height: 844 })
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()
  await searchHome(page, name)
  const card = page.getByTestId('feed-card').filter({ hasText: name })
  await expect(card).toHaveAttribute('data-video', 'true')
  // Q63: in the middle of the screen the card plays its video, muted.
  await card.evaluate((element) => element.scrollIntoView({ block: 'center' }))
  const frame = card.locator('iframe')
  await expect(frame).toHaveAttribute('src', `${PLAYER}?autoplay=1&mute=1&playsinline=1`)
  await expect(card).toContainText(`Up to ${sponsor.balance.toLocaleString('en-US')} Kudos`)
  // The App's policy lets this player in (the stub answers for it).
  await expect(card.frameLocator('iframe').getByRole('button', { name: 'Play' })).toBeVisible()
  await expectAccessible(page, 'app home video to watch')
  // The start opens the start page first: the video leads it, playing as it opens; then the quiz starts.
  await card.getByTestId('feed-card-start').click()
  await expect(page).toHaveURL(/\/p\/[^/]+$/)
  await expect(page.getByTestId('detail-hero').locator('iframe'))
    .toHaveAttribute('src', `${PLAYER}?autoplay=1&mute=1&playsinline=1`)
  await expectAccessible(page, 'app start page with its video')
  await page.getByTestId('detail-start').click()
  await expect(page).toHaveURL(/\/play\/[^/]+$/)
  await expect(page.getByTestId('play-question')).toContainText('What did the video show?')
})
