import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import type { APIRequestContext, Page } from '@playwright/test'
import { adminToken, expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique } from './support'
import { fundedSponsor, searchHome, taker, untilLive } from './play'

/*
 * F-S15 (docs/quizbuks/plans/impl/F-S15.md): a video file on any https address. A sponsor enters the file's address;
 * the console's preview loads it and reads its length (only then it can be the video to watch); once the publication
 * is live, the taker's home plays it on the card in the app's one player, muted and by itself, and the start page waits
 * for the watching before the start. A file that does not open falls back to the cover and a link.
 *
 * The file is a real ten-second video (VP8 + Opus, `media/quizbuks-demo-10s.webm`): Playwright's open-source Chromium
 * has no H.264, so the test plays WebM; recognising `.mp4` is the unit tests' (VideoEmbedsTest, qb/video.test.ts). The
 * address is answered by the test (no video server is reached), the missing one with a 404.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

const FILE = 'https://videos.e2e.quizbuks.test/quizbuks-demo-10s.webm'
const MISSING = 'https://videos.e2e.quizbuks.test/missing.webm'
const VIDEO = readFileSync(fileURLToPath(new URL('./media/quizbuks-demo-10s.webm', import.meta.url)))

/** The video server: the file, with byte ranges as a real one answers them (browsers ask for ranges of media). */
async function videoServer(page: Page) {
  await page.route(FILE, (route) => {
    const range = /bytes=(\d+)-(\d*)/.exec(route.request().headers().range ?? '')
    if (!range) {
      return route.fulfill({ status: 200, contentType: 'video/webm', body: VIDEO,
        headers: { 'Accept-Ranges': 'bytes' } })
    }
    const from = Number(range[1])
    const to = range[2] ? Math.min(Number(range[2]), VIDEO.length - 1) : VIDEO.length - 1
    return route.fulfill({ status: 206, contentType: 'video/webm', body: VIDEO.subarray(from, to + 1),
      headers: { 'Accept-Ranges': 'bytes', 'Content-Range': `bytes ${from}-${to}/${VIDEO.length}` } })
  })
  await page.route(MISSING, (route) => route.fulfill({ status: 404, contentType: 'text/plain', body: 'Not found' }))
}

/** A live publication of the sponsor's whose quiz has a video link of `url` (to watch first when `watch`). */
async function livePublication(request: APIRequestContext, sponsor: { token: string; balance: number }, url: string,
  durationSec: number | null, watch: boolean) {
  const quiz = await run(request, sponsor.token, 'QB_QUIZ_SAVE', { title: unique('Video file quiz '), timeLimitSec: 300 })
  const quizId = quiz.quizId as string
  await run(request, sponsor.token, 'QB_MATERIAL_SAVE', { quizId, kind: 'VIDEO_LINK', title: 'Watch this first', url,
    watchToAnswer: watch, durationSec })
  return publish(request, sponsor, quizId)
}

async function publish(request: APIRequestContext, sponsor: { token: string; balance: number }, quizId: string) {
  await run(request, sponsor.token, 'QB_QUESTION_SAVE', { quizId, stem: 'What did the video show?', points: 1,
    options: [{ text: 'A demo', correct: true }, { text: 'A map' }] })
  const version = await run(request, sponsor.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  const name = unique('Video file run ')
  const now = Date.now()
  const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', {
    versionId: version.versionId, name, regions: ['GLOBAL'],
    startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
    timeLimitSec: 300, passScore: 1, tiers: [{ fromScore: 0, toScore: 0, reward: 0 },
      { fromScore: 1, toScore: 1, reward: Math.floor(sponsor.balance / 4) }],
  })
  const publicationId = saved.publicationId as string
  const submitted = await run(request, sponsor.token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  if (submitted.approval === 'PENDING') {
    await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId,
      decision: 'APPROVE' })
  }
  return { publicationId, name }
}

test('a video file plays muted on the home and is the video to watch before the start', async ({ page, request }) => {
  test.setTimeout(180_000)
  await videoServer(page)
  const sponsor = await fundedSponsor(request)
  const quiz = await run(request, sponsor.token, 'QB_QUIZ_SAVE', { title: unique('Video file quiz '), timeLimitSec: 300 })
  const quizId = quiz.quizId as string

  await signIn(page, '/sponsor/', sponsor.email)
  await expect(page).not.toHaveURL(/\/login/)
  await expect(page.getByTestId('menu-quizzes')).toBeVisible()
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

  const { publicationId, name } = await publish(request, sponsor, quizId)
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
  // It really plays: its time goes on.
  await expect.poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime)).toBeGreaterThan(0.5)
  // A tap opens the start page with the same player (its sound on); the start waits for the watching it reports.
  await page.getByTestId('video-open').click()
  await expect(page).toHaveURL(/\/p\/[^/]+$/)
  await expect(page.getByTestId('detail-hero').getByTestId('video-slot')).toHaveAttribute('data-mode', 'page')
  await expect(page.getByTestId('detail-watch')).toBeVisible()
  expect(await video.evaluate((element: HTMLVideoElement) => element.controls)).toBe(true)
  // The home's muted playing did not count: the taker watches it from the start with the player's own controls.
  await video.evaluate((element: HTMLVideoElement) => { element.currentTime = 0; void element.play() })
  await expect(page.getByTestId('detail-start')).toBeEnabled({ timeout: 30_000 })
  await expect(page.getByTestId('detail-watch')).toHaveText('Video watched: you can start.')
  await expectAccessible(page, 'app start page with its video file')
  await page.getByTestId('detail-start').click()
  await expect(page).toHaveURL(/\/play\/[^/]+$/)
  await expect(page.getByTestId('play-question')).toContainText('What did the video show?')
})

test('a video file that does not open falls back to its cover and a link', async ({ page, request }) => {
  test.setTimeout(120_000)
  await videoServer(page)
  const sponsor = await fundedSponsor(request)
  const { publicationId, name } = await livePublication(request, sponsor, MISSING, null, false)
  const me = await taker(request, 'Vix')
  await untilLive(request, me.token, publicationId)
  await page.setViewportSize({ width: 390, height: 844 })
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()
  await searchHome(page, name)
  const card = page.getByTestId('feed-card').filter({ hasText: name })
  await card.evaluate((element) => element.scrollIntoView({ block: 'center' }))
  // The player fails: the card shows its cover again, and a link to the file.
  const link = card.getByTestId('feed-card-open-video')
  await expect(link).toHaveAttribute('href', MISSING)
  await expect(link).toHaveAttribute('target', '_blank')
  await expect(card).not.toHaveAttribute('data-video', 'true')
  await expect(page.getByTestId('video-stage-file')).toHaveCount(0)
  await expectAccessible(page, 'app home video file that does not open')
})
