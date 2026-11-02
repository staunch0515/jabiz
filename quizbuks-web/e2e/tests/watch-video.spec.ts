import { adminToken, expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique } from './support'
import { fundedSponsor, searchHome, taker, untilLive } from './play'

/*
 * The video to watch (docs/quizbuks/plans/Q19-embedded-video.md): a sponsor ticks "Watching this video is enough to
 * answer" on a YouTube video in the console; once the publication is live, a taker's home shows the video's own player
 * (YouTube without cookies, not playing by itself) with what answering earns and a Start button that starts the quiz.
 * The player is a stub page here: the test does not reach YouTube. Q75 (docs/quizbuks/plans/Q75-video-playback.md):
 * the stub speaks YouTube's player protocol back — its duration (which fills the console's duration field), its time
 * every half second while it plays a ten-second video on a loop, and its end — so that the start page waits for the
 * watching and the start carries the report; a start without one is refused.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

const PLAYER = 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'

/** The stub player: once a page says it listens, it reports like YouTube's player does, playing ten seconds on a loop. */
const STUB = `<!doctype html><html lang="en"><title>Player</title><button>Play</button><script>
  let timer = null, t = 0
  const say = (message) => parent.postMessage(JSON.stringify(message), '*')
  const tick = () => {
    t += 0.5
    if (t >= 10) { say({ event: 'infoDelivery', info: { currentTime: 10, playerState: 0 } }); say({ event: 'onStateChange', info: 0 })
      clearInterval(timer); timer = null; setTimeout(start, 1000); return }
    say({ event: 'infoDelivery', info: { currentTime: t } })
  }
  const start = () => { t = 0; say({ event: 'infoDelivery', info: { playerState: 1, currentTime: 0, duration: 10, muted: true } })
    timer = setInterval(tick, 500) }
  addEventListener('message', (e) => {
    let m; try { m = JSON.parse(e.data) } catch { return }
    if (m.event === 'listening' && !timer) start()
    if (m.event === 'command' && m.func === 'unMute') say({ event: 'infoDelivery', info: { muted: false } })
  })
</script></html>`

test('a video to watch is shown on the home with its reward and starts the quiz', async ({ page, request }) => {
  test.setTimeout(180_000)
  await page.route('https://www.youtube-nocookie.com/**', (route) => route.fulfill({ contentType: 'text/html', body: STUB }))
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
  await expectAccessible(page, 'sponsor video to watch')
  await dialog.getByTestId('material-watch').click()
  // Q75: the preview's player says the video lasts ten seconds; the duration is filled in from it.
  await expect(dialog.getByTestId('material-video-probe')).toBeVisible()
  await expect(dialog.getByTestId('material-duration')).toHaveValue('0:10', { timeout: 15_000 })
  await dialog.getByTestId('material-save').click()
  await expect(page.getByTestId('material-watch-badge')).toBeVisible()
  await expect(page.getByTestId('material-duration-badge')).toHaveText('Duration 0:10')

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
  // Q75 (D-Q75-4): a start without the report of the watching is refused.
  const unwatched = await request.post('/api/processes/QB_ATTEMPT_START/latest', {
    headers: { Authorization: `Bearer ${me.token}`, 'Idempotency-Key': crypto.randomUUID() }, data: { publicationId } })
  expect(unwatched.status()).toBe(422)
  expect(await unwatched.text()).toContain('QB_ATTEMPT_WATCH_REQUIRED')

  await page.context().clearCookies()
  await page.setViewportSize({ width: 390, height: 844 })
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()
  await searchHome(page, name)
  const card = page.getByTestId('feed-card').filter({ hasText: name })
  await expect(card).toHaveAttribute('data-video', 'true')
  await expect(card.getByTestId('feed-card-watch')).toHaveText('Watch to answer')
  // Q63: in the middle of the screen the card plays its video, muted.
  await card.evaluate((element) => element.scrollIntoView({ block: 'center' }))
  // Q65: the app's one player, laid over the card; started muted, with its interface on.
  const stage = page.getByTestId('video-stage')
  const frame = stage.locator('iframe')
  await expect(frame).toHaveAttribute('src', new RegExp(`^${PLAYER}\\?autoplay=1&mute=1&playsinline=1&rel=0&enablejsapi=1`))
  await expect(card).toContainText(`Up to ${sponsor.balance.toLocaleString('en-US')} Kudos`)
  // The App's policy lets this player in (the stub answers for it).
  await expect(stage.frameLocator('iframe').getByRole('button', { name: 'Play' })).toBeVisible()
  await expectAccessible(page, 'app home video to watch')
  // A tap on the video (nothing in the player is touched) opens the start page; the same player plays on there.
  const before = await frame.elementHandle()
  await page.getByTestId('video-open').click()
  await expect(page).toHaveURL(/\/p\/[^/]+$/)
  await expect(page.getByTestId('detail-hero').getByTestId('video-slot')).toHaveAttribute('data-mode', 'page')
  await expect(stage).toHaveAttribute('data-mode', 'page')
  expect(await page.evaluate((element) => element?.isConnected, before)).toBe(true)
  await expect(page.locator('iframe')).toHaveCount(1)
  await expectAccessible(page, 'app start page with its video')
  // Q75: the start waits for the watching the player reports (the stub plays its ten seconds on a loop).
  await expect(page.getByTestId('detail-watch')).toBeVisible()
  await expect(page.getByTestId('detail-start')).toBeEnabled({ timeout: 30_000 })
  await expect(page.getByTestId('detail-watch')).toHaveText('Video watched: you can start.')
  await page.getByTestId('detail-start').click()
  await expect(page).toHaveURL(/\/play\/[^/]+$/)
  await expect(page.getByTestId('play-question')).toContainText('What did the video show?')
})
