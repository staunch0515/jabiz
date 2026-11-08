import { adminToken, expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique } from './support'
import { fundedSponsor, searchHome, taker, untilLive } from './play'

/*
 * F-S15 (docs/quizbuks/plans/impl/F-S15.md): a video file on any https address. A sponsor enters a .mp4 address; the
 * console's preview reads its length (and only then it can be the video to watch); once the publication is live, the
 * taker's home plays it on the card in the app's one player, muted and by itself, and the start page waits for the
 * watching before the start.
 *
 * The test reaches no video server and plays no real media (the open-source Chromium has no H.264): the file's
 * request is answered by the test, and an init script stands in for the browser's media playback — a `<video>` with
 * this address "loads" a ten-second video and, once played, reports its time like a browser does, to its end.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

const FILE = 'https://videos.e2e.quizbuks.test/quizbuks-demo-10s.mp4'

/** Stands in for media playback of the test's file only: metadata, then time updates while playing, then its end. */
function fakeMedia(file: string) {
  const proto = HTMLMediaElement.prototype
  const times = new WeakMap<HTMLMediaElement, number>()
  const timers = new WeakMap<HTMLMediaElement, ReturnType<typeof setInterval>>()
  const ours = (media: HTMLMediaElement) => media.currentSrc === file || media.getAttribute('src') === file
  const durationOf = Object.getOwnPropertyDescriptor(proto, 'duration')!
  const timeOf = Object.getOwnPropertyDescriptor(proto, 'currentTime')!
  const pausedOf = Object.getOwnPropertyDescriptor(proto, 'paused')!
  const readyOf = Object.getOwnPropertyDescriptor(proto, 'readyState')!
  const play = proto.play
  const pause = proto.pause
  const playing = new WeakSet<HTMLMediaElement>()
  Object.defineProperty(proto, 'duration', { configurable: true,
    get() { return ours(this) ? 10 : durationOf.get!.call(this) } })
  Object.defineProperty(proto, 'readyState', { configurable: true,
    get() { return ours(this) ? 4 : readyOf.get!.call(this) } })
  Object.defineProperty(proto, 'currentTime', { configurable: true,
    get() { return ours(this) ? times.get(this) ?? 0 : timeOf.get!.call(this) },
    set(value: number) { if (ours(this)) times.set(this, value); else timeOf.set!.call(this, value) } })
  Object.defineProperty(proto, 'paused', { configurable: true,
    get() { return ours(this) ? !playing.has(this) : pausedOf.get!.call(this) } })
  proto.play = function (this: HTMLMediaElement) {
    if (!ours(this)) return play.call(this)
    if (!playing.has(this)) {
      playing.add(this)
      this.dispatchEvent(new Event('play'))
      timers.set(this, setInterval(() => {
        const next = Math.min(10, (times.get(this) ?? 0) + 0.5)
        times.set(this, next)
        this.dispatchEvent(new Event('timeupdate'))
        if (next >= 10) {
          clearInterval(timers.get(this))
          playing.delete(this)
          this.dispatchEvent(new Event('ended'))
        }
      }, 500))
    }
    return Promise.resolve()
  }
  proto.pause = function (this: HTMLMediaElement) {
    if (!ours(this)) return pause.call(this)
    clearInterval(timers.get(this))
    if (playing.delete(this)) this.dispatchEvent(new Event('pause'))
  }
  // A `<video>` given the address: its metadata, then (autoplay) it plays.
  new MutationObserver((records) => {
    for (const record of records) {
      for (const node of [...record.addedNodes, record.target]) {
        if (!(node instanceof HTMLVideoElement) || !ours(node) || node.dataset.fake) continue
        node.dataset.fake = 'yes'
        setTimeout(() => {
          node.dispatchEvent(new Event('loadedmetadata'))
          node.dispatchEvent(new Event('durationchange'))
          if (node.autoplay) void node.play()
        }, 100)
      }
    }
  }).observe(document, { subtree: true, childList: true, attributes: true, attributeFilter: ['src'] })
}

test('a video file plays muted on the home and is the video to watch before the start', async ({ page, request }) => {
  test.setTimeout(180_000)
  await page.addInitScript(fakeMedia, FILE)
  await page.route(FILE, (route) => route.fulfill({ status: 200, contentType: 'video/mp4', body: Buffer.alloc(16) }))
  const sponsor = await fundedSponsor(request)
  const quiz = await run(request, sponsor.token, 'QB_QUIZ_SAVE', { title: unique('Video file quiz '), timeLimitSec: 300 })
  const quizId = quiz.quizId as string

  await signIn(page, '/sponsor/', sponsor.email)
  await expect(page).not.toHaveURL(/\/login/)
  await expect(page.getByRole('link', { name: 'Quizzes' })).toBeVisible()
  await page.goto(`/sponsor/quizzes/${quizId}`)
  await page.getByTestId('tab-materials').click()
  await page.getByTestId('material-add').click()
  const dialog = page.getByTestId('material-dialog')
  await dialog.getByRole('radio', { name: /video/i }).click()
  await dialog.getByTestId('material-title').fill('Watch this first')
  await dialog.getByTestId('material-url').fill(FILE)
  // The preview loads the file for real and reads its length; then it can be the video to watch.
  await expect(dialog.getByTestId('material-file-preview')).toBeVisible()
  await expect(dialog.getByTestId('material-duration')).toHaveValue('0:10', { timeout: 15_000 })
  await dialog.getByTestId('material-watch').click()
  await expectAccessible(page, 'sponsor video file to watch')
  await dialog.getByTestId('material-save').click()
  await expect(page.getByTestId('material-watch-badge')).toBeVisible()

  await run(request, sponsor.token, 'QB_QUESTION_SAVE', { quizId, stem: 'What did the video show?', points: 1,
    options: [{ text: 'A demo', correct: true }, { text: 'A map' }] })
  const version = await run(request, sponsor.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  const name = unique('Video file run ')
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
  const me = await taker(request, 'Vid')
  await untilLive(request, me.token, publicationId)

  await page.context().clearCookies()
  await page.setViewportSize({ width: 390, height: 844 })
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()
  await searchHome(page, name)
  const card = page.getByTestId('feed-card').filter({ hasText: name })
  await expect(card).toHaveAttribute('data-video', 'true')
  await expect(card.getByTestId('feed-card-watch')).toHaveText('Watch to answer')
  // In the middle of the screen the card plays the file in the app's one player: muted, by itself, inline.
  await card.evaluate((element) => element.scrollIntoView({ block: 'center' }))
  const video = page.getByTestId('video-stage-file')
  await expect(video).toHaveAttribute('src', FILE)
  expect(await video.evaluate((element: HTMLVideoElement) => element.muted && element.autoplay)).toBe(true)
  await expect(page.locator('iframe[src*="quizbuks-demo"]')).toHaveCount(0)
  await expectAccessible(page, 'app home video file')
  // A tap opens the start page with the same player; the start waits for the watching it reports.
  await page.getByTestId('video-open').click()
  await expect(page).toHaveURL(/\/p\/[^/]+$/)
  await expect(page.getByTestId('detail-hero').getByTestId('video-slot')).toHaveAttribute('data-mode', 'page')
  await expect(page.getByTestId('detail-watch')).toBeVisible()
  await expect(page.getByTestId('detail-start')).toBeEnabled({ timeout: 30_000 })
  await expect(page.getByTestId('detail-watch')).toHaveText('Video watched: you can start.')
  await expectAccessible(page, 'app start page with its video file')
  await page.getByTestId('detail-start').click()
  await expect(page).toHaveURL(/\/play\/[^/]+$/)
  await expect(page.getByTestId('play-question')).toContainText('What did the video show?')
})
