import type { APIRequestContext } from '@playwright/test'
import { adminToken, expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique } from './support'
import { fundedSponsor, searchHome, taker, untilLive } from './play'
import { VIDEO_FILE, videoServer } from './video-server'

/*
 * F-S10 (docs/quizbuks/plans/impl/F-S10.md): publications with a YouTube Shorts video appear in the home's shelf of
 * short videos; a tap opens the full-screen vertical mode, which moves one by one (a swipe, the buttons, the keys) and
 * leads to the start page. The search narrows the home to this test's publications, so the shelf (its first place, 3,
 * beyond the two cards) closes the list.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/**
 * A live publication of the sponsor's, of a quiz whose video is `video` (a YouTube Shorts link; none: no video),
 * marked vertical by the sponsor when `vertical`.
 */
async function shortsPublication(request: APIRequestContext, sponsor: { token: string; balance: number }, name: string,
  video: string | null, vertical?: boolean) {
  const quiz = await run(request, sponsor.token, 'QB_QUIZ_SAVE', { title: `${name} quiz`, timeLimitSec: 300 })
  const quizId = quiz.quizId as string
  if (video) {
    await run(request, sponsor.token, 'QB_MATERIAL_SAVE', { quizId, kind: 'VIDEO_LINK', title: 'Short', url: video,
      ...(vertical ? { vertical: true } : {}) })
  }
  await run(request, sponsor.token, 'QB_QUESTION_SAVE', { quizId, stem: 'What did the short show?', points: 1,
    options: [{ text: 'A song', correct: true }, { text: 'A map' }] })
  const version = await run(request, sponsor.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
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
  return publicationId
}

test('a Shorts publication is in the shelf; the vertical mode swipes on and leads to answering', async ({ page, request }) => {
  test.setTimeout(180_000)
  // No YouTube here: the player is a blank page.
  await page.route('https://www.youtube-nocookie.com/**', (route) => route.fulfill({ contentType: 'text/html',
    body: '<!doctype html><html lang="en"><title>Player</title></html>' }))
  const sponsor = await fundedSponsor(request)
  const base = unique('Shorts run ')
  const first = await shortsPublication(request, sponsor, `${base} one`, 'https://www.youtube.com/shorts/dQw4w9WgXcQ')
  const second = await shortsPublication(request, sponsor, `${base} two`, 'https://youtube.com/shorts/aaaaaaaaaaa')
  const me = await taker(request, 'Sora')
  await untilLive(request, me.token, first)
  await untilLive(request, me.token, second)

  await page.setViewportSize({ width: 390, height: 844 })
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()
  await searchHome(page, base)
  const shelf = page.getByTestId('shorts-shelf')
  await expect(shelf).toHaveCount(1)
  await expect(shelf.getByTestId('shorts-card')).toHaveCount(2)
  await expectAccessible(page, 'app home with a shelf of short videos')

  // The newest first, as the home: the second one, then the first.
  await shelf.getByTestId('shorts-card-open').first().click()
  const viewer = page.getByTestId('shorts-viewer')
  await expect(viewer).toBeVisible()
  await expect(viewer.getByTestId('shorts-position')).toHaveText(/1/)
  await expect(page.locator('iframe')).toHaveCount(1)
  await expectAccessible(page, 'app vertical mode')
  // A swipe up shows the next one; the keys and the buttons move as well.
  const screens = viewer.getByTestId('shorts-screens')
  // The pointer over the screen's name, off the player (which takes the rest of a vertical video's screen: a wheel
  // there goes to the frame's page, and Playwright refuses to hover a point the frame takes).
  await expect(screens).toBeVisible()
  await viewer.locator('[data-current]').getByTestId('shorts-name').hover()
  await page.mouse.wheel(0, 900)
  await expect(viewer.locator('[data-testid="shorts-screen"][data-current]')).toHaveAttribute('data-publication', first)
  const current = viewer.locator('[data-testid="shorts-screen"][data-current]')
  await page.keyboard.press('ArrowUp')
  await expect(current).toHaveAttribute('data-publication', second)
  // "Next" tapped while the scroll up is still travelling (the screen shown changes at once, its smooth scroll
  // follows): the tap reaches the button and the next one is shown, and scrolled to. The app's player follows the
  // screen coming down from above; it must stay within the screens, not cover the header's buttons on its way (a tap
  // on them then went to the player and was lost).
  await viewer.getByTestId('shorts-next').click()
  await expect(current).toHaveAttribute('data-publication', first)
  await expect(current).toBeInViewport({ ratio: 0.9 })
  await expect(current).toHaveAttribute('data-publication', first)
  await expect(viewer.getByTestId('shorts-position')).toHaveText('2 of 2')
  // Again with nothing in between, as fast as a person taps twice.
  await page.keyboard.press('ArrowUp')
  await viewer.getByTestId('shorts-next').click()
  await expect(current).toHaveAttribute('data-publication', first)
  await expect(current).toBeInViewport({ ratio: 0.9 })
  // While a screen moves in from above, every frame: what is under the header's buttons is the buttons, not the player.
  const covered = await page.evaluate(async () => {
    const under: string[] = []
    const buttons = ['shorts-close', 'shorts-previous', 'shorts-next']
      .map((id) => document.querySelector<HTMLElement>(`[data-testid="${id}"]`)!)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp' }))
    const end = performance.now() + 1500
    while (performance.now() < end) {
      await new Promise((resolve) => requestAnimationFrame(resolve))
      // A disabled button takes no pointer (the first screen's "previous").
      for (const button of buttons.filter((b) => !(b as HTMLButtonElement).disabled)) {
        const box = button.getBoundingClientRect()
        const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
        if (!hit || !button.contains(hit)) under.push(`${button.dataset.testid}: ${hit?.tagName ?? 'nothing'}`)
      }
    }
    return under
  })
  expect(covered).toEqual([])
  await expect(current).toHaveAttribute('data-publication', second)
  await viewer.getByTestId('shorts-next').click()
  await expect(current).toHaveAttribute('data-publication', first)
  await expect(current).toBeInViewport({ ratio: 0.9 })
  await expect(current).toHaveAttribute('data-publication', first)
  await expect(viewer.getByTestId('shorts-position')).toHaveText('2 of 2')
  await expect(page.locator('iframe')).toHaveCount(1)

  // Back to the home, where it was; then again, and on to answering.
  await page.keyboard.press('Escape')
  await expect(viewer).toHaveCount(0)
  await expect(shelf).toBeVisible()
  await shelf.getByTestId('shorts-card-open').last().click()
  await page.getByTestId('shorts-viewer').locator('[data-current]').getByTestId('shorts-details').click()
  await expect(page).toHaveURL(new RegExp(`/taker/p/${first}$`))
  await expect(page.getByTestId('detail-start')).toBeVisible()
})

test('a post\'s video opens the video mode: black, landscape across, the posts without a video skipped, and starts at once (Q113c)',
  async ({ page, request }) => {
  test.setTimeout(180_000)
  await page.route('https://www.youtube-nocookie.com/**', (route) => route.fulfill({ contentType: 'text/html',
    body: '<!doctype html><html lang="en"><title>Player</title></html>' }))
  // Nothing plays by itself: the post's picture is the one to tap (a playing one has the stage's button instead).
  await page.emulateMedia({ reducedMotion: 'reduce' })
  const sponsor = await fundedSponsor(request)
  const base = unique('Videos run ')
  // Newest first on the home: the landscape one, the one without a video, then the other landscape one.
  const last = await shortsPublication(request, sponsor, `${base} three`, 'https://www.youtube.com/watch?v=bbbbbbbbbbb')
  const none = await shortsPublication(request, sponsor, `${base} two`, null)
  const first = await shortsPublication(request, sponsor, `${base} one`, 'https://www.youtube.com/watch?v=dQw4w9WgXcQ')
  const me = await taker(request, 'Rin')
  for (const id of [first, none, last]) await untilLive(request, me.token, id)

  await page.setViewportSize({ width: 390, height: 844 })
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()
  await searchHome(page, base)
  await expect(page.getByTestId('feed-card')).toHaveCount(3)
  const post = page.getByTestId('feed-card').filter({ hasText: `${base} one` })
  await post.getByTestId('feed-card-picture').click()
  await expect(page).toHaveURL(new RegExp(`[?&]video=${first}`))
  const viewer = page.getByTestId('shorts-viewer')
  await expect(viewer).toBeVisible()
  expect(await page.getByTestId('shorts-backdrop').evaluate((element) => getComputedStyle(element).backgroundColor))
    .toBe('rgb(0, 0, 0)')
  // The two with a video, in the home's order; the one without is not among them.
  await expect(viewer.getByTestId('shorts-screen')).toHaveCount(2)
  await expect(viewer.locator(`[data-publication="${none}"]`)).toHaveCount(0)
  const current = viewer.locator('[data-current]')
  await expect(current).toHaveAttribute('data-publication', first)
  await expect(current.getByTestId('shorts-stage')).toHaveAttribute('data-orientation', 'landscape')
  const box = (await current.getByTestId('shorts-stage').locator(':scope > div').boundingBox())!
  expect(box.width, 'a landscape video across the screen').toBeCloseTo(390, 0)
  expect(box.height).toBeCloseTo(390 * 9 / 16, 0)
  await expectAccessible(page, 'app video mode')
  await viewer.getByTestId('shorts-next').click()
  await expect(current).toHaveAttribute('data-publication', last)
  await viewer.getByTestId('shorts-previous').click()
  await expect(current).toHaveAttribute('data-publication', first)

  // "Start the quiz" starts it there and then.
  const started = page.waitForResponse((r) => r.url().includes('/api/processes/QB_ATTEMPT_START/'))
  await current.getByTestId('shorts-start').click()
  expect((await started).status()).toBe(200)
  await expect(page).toHaveURL(/\/taker\/play\//)
})

test('a vertical video fills the screen of the vertical mode and plays at once, a file and a Shorts link (F-S10)',
  async ({ page, request }) => {
  test.setTimeout(180_000)
  await videoServer(page)
  await page.route('https://www.youtube-nocookie.com/**', (route) => route.fulfill({ contentType: 'text/html',
    body: '<!doctype html><html lang="en"><title>Player</title></html>' }))
  const sponsor = await fundedSponsor(request)
  const base = unique('Tall run ')
  // Newest first on the home: the file the sponsor marked vertical, then the Shorts link.
  const short = await shortsPublication(request, sponsor, `${base} short`, 'https://www.youtube.com/shorts/cccccccccc1')
  const file = await shortsPublication(request, sponsor, `${base} file`, VIDEO_FILE, true)
  const me = await taker(request, 'Tall')
  await untilLive(request, me.token, short)
  await untilLive(request, me.token, file)

  await page.setViewportSize({ width: 390, height: 844 })
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()
  await searchHome(page, base)
  await expect(page.getByTestId('feed-card')).toHaveCount(2)
  // The file's post, in the middle of the screen, plays in the app's player. On the home a vertical video keeps its
  // shape (9:16, at most 287 px across, at the left), and the player is the cover's size: the same box.
  const post = page.getByTestId('feed-card').filter({ hasText: `${base} file` })
  await post.evaluate((element) => element.scrollIntoView({ block: 'center' }))
  await expect(page.getByTestId('video-open')).toBeVisible()
  const onHome = (await post.getByTestId('feed-card-playing').boundingBox())!
  expect(onHome.width, 'a vertical video on the home is at most 287 px across').toBeCloseTo(287, 0)
  expect(onHome.height / onHome.width).toBeCloseTo(16 / 9, 1)
  await expect.poll(async () => {
    const player = await page.getByTestId('video-stage').boundingBox()
    // Within the box's 1 px border.
    return !!player && [player.x - onHome.x - 1, player.y - onHome.y - 1, player.width - onHome.width + 2,
      player.height - onHome.height + 2].every((off) => Math.abs(off) <= 1)
  }, { message: 'the player on the home is the cover\'s box' }).toBe(true)
  // No cover and no play mark: the video's own first frame until the player's, then the video (never black).
  expect(await post.locator('img').count()).toBe(0)
  await expect(post.getByTestId('video-poster-frame')).toHaveAttribute('src', `${VIDEO_FILE}#t=0.1`)
  await expect.poll(() => page.getByTestId('video-stage-content').evaluate((element) => getComputedStyle(element).opacity))
    .toBe('1')
  // A tap maximizes this video: the full-screen video mode on it.
  await page.getByTestId('video-open').click()
  await expect(page).toHaveURL(new RegExp(`[?&]video=${file}`))
  const viewer = page.getByTestId('shorts-viewer')
  const current = viewer.locator('[data-current]')
  await expect(current).toHaveAttribute('data-publication', file)
  await expect(current.getByTestId('shorts-stage')).toHaveAttribute('data-orientation', 'vertical')

  // The player over all of the phone's screen, the picture cut rather than letterboxed.
  const stage = page.getByTestId('video-stage')
  const filled = async (width: number, height: number, left = 0) => {
    await expect.poll(async () => {
      const box = await stage.boundingBox()
      return !!box && [box.x - left, box.y, box.width - width, box.height - height].every((off) => Math.abs(off) <= 1)
    }, { message: `the player covers ${width} by ${height} from ${left}` }).toBe(true)
  }
  await filled(390, 844)
  const video = page.getByTestId('video-stage-file')
  expect(await video.evaluate((element) => getComputedStyle(element).objectFit)).toBe('cover')
  // It plays, never on the browser's controls (paused, waiting for a tap): the screen takes the taps on the video, one
  // pauses it, the next plays it on.
  await expect.poll(() => video.evaluate((element: HTMLVideoElement) => !element.paused && element.currentTime > 0.5),
    { message: 'the file plays by itself' }).toBe(true)
  expect(await video.evaluate((element: HTMLVideoElement) => element.controls)).toBe(false)
  const toggle = current.getByTestId('shorts-toggle')
  await toggle.click()
  await expect.poll(() => video.evaluate((element: HTMLVideoElement) => element.paused)).toBe(true)
  await toggle.click()
  await expect.poll(() => video.evaluate((element: HTMLVideoElement) => !element.paused)).toBe(true)
  // The name and the buttons lie over the video, and take their taps.
  const details = current.getByTestId('shorts-details')
  const box = (await details.boundingBox())!
  expect(await page.evaluate(([x, y]) => document.elementFromPoint(x, y)?.closest('[data-testid="shorts-details"]') != null,
    [box.x + box.width / 2, box.y + box.height / 2])).toBe(true)
  await expectAccessible(page, 'app vertical mode, a vertical file over the screen')

  // The wide layout: the full height, in a column of the video's shape in the middle.
  await page.setViewportSize({ width: 1280, height: 800 })
  await filled(450, 800, 415)
  await expect.poll(() => video.evaluate((element: HTMLVideoElement) => !element.paused)).toBe(true)

  // The Shorts link: YouTube's frame given the vertical shape over all of the screen (cut at the sides on a phone).
  await page.setViewportSize({ width: 390, height: 844 })
  // The switch is never black: the next screen shows YouTube's picture of the video, and so does the stage under its
  // player, which stays transparent until it has its first frame (YouTube's player is a blank page here: it never does).
  await expect(current.getByTestId('video-poster')).toHaveCount(0)
  await viewer.getByTestId('shorts-next').click()
  await expect(current).toHaveAttribute('data-publication', short)
  await expect(current).toBeInViewport({ ratio: 0.9 })
  await expect(stage.getByTestId('video-stage-cover').getByTestId('video-poster'))
    .toHaveAttribute('src', 'https://i.ytimg.com/vi/cccccccccc1/hqdefault.jpg')
  expect(await stage.getByTestId('video-stage-content').evaluate((element) => getComputedStyle(element).opacity)).toBe('0')
  await filled(390, 844)
  const frame = stage.locator('iframe')
  await expect(frame).toHaveAttribute('src', /autoplay=1&mute=1/)
  await expect.poll(async () => {
    const box = await frame.boundingBox()
    return !!box && Math.abs(box.width - 844 * 9 / 16) <= 1 && Math.abs(box.height - 844) <= 1
  }, { message: 'the frame is 9:16 over all of the screen' }).toBe(true)
})
