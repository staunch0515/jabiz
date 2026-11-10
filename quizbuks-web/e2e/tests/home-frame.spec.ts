import type { APIRequestContext, Page } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { PNG, POLICY, upload } from './content'
import { fund, taker, untilLive } from './play'
import { approvedSponsor, livePublication } from './publishing'
import { expect, prepareQuizbuks, run, setAppearance, signIn, test } from './support'

/*
 * F-F4 (docs/quizbuks/plans/impl/F-F4.md section 6): the taker app's home stays as it is, pixel for pixel, while the
 * frame around it changes (the bottom menu of five, the left column on a wide screen, no menu at the top right).
 *
 * The baselines were taken from the app before F-F4 changed anything (F-F4a), on CI's pinned image (ubuntu-24.04: the
 * same fonts and rendering every run), and a later change may not update them: a change to the home itself updates
 * them in its own pull request and says why (backend/quizbuks/CLAUDE.md section 5). How they are made is in the plan's
 * section 15.
 *
 * Compared: the home's content (its section, with the cards' bleed to the screen's edges) at a phone's widths and a
 * desktop's, in both appearances, for a list of this test's own cards with a shelf of short videos and for no match;
 * and the header's left part (back, the wordmark, the test system's banner) on a phone. Not compared: the bottom menu
 * and the left column (what F-F4 changes), and the header's buttons at the right, which move one place when the menu
 * goes; their set is checked instead.
 *
 * The data are this test's alone: the home is searched by a token in the quizzes' titles (the cards do not show
 * titles), so other tests' publications in the shared database never show; names, the Sponsor, the rewards (`livePublication`'s) and covers
 * are the same every run; the searched words are kept out by home-frame.css. Videos never play (less motion asked for:
 * the home then plays none), and YouTube's player is a blank page here.
 *
 * Runs on CI, or locally with E2E_VISUAL=1 (a local machine renders differently from CI's image, so its own run would
 * only ever fail).
 */

/** Phone widths (the app is designed for up to 430 px; 360 px is the smallest the spec names) and a desktop's. */
const WIDTHS = [360, 390, 1280] as const
/** Where the header is compared: phones only (on a wide screen F-F4 adds the left column beside it). */
const HEADER_WIDTHS = [360, 390] as const
/** Tall enough for the whole home of this test without scrolling: a full-page capture draws fixed bars over the page. */
const VIEWPORT_HEIGHT = 2400
/** The main column's side padding (`px-4`): the cards bleed into it on a phone (`-mx-4`), and the clip takes it. */
const MAIN_PADDING = 16
/** Room after the wordmark in the header's clip, short of the first button at the right on a 360 px phone. */
const WORDMARK_ROOM = 8
/** "Pixel for pixel" (the spec's acceptance): not one pixel may differ. */
const MAX_DIFF_PIXELS = 0
/** The length of the run's token in the quizzes' titles: one length every run, so that nothing shifts by it. */
const TOKEN_LENGTH = 14

const STYLE = fileURLToPath(new URL('./home-frame.css', import.meta.url))
const APPEARANCES = ['light', 'dark'] as const

test.skip(!process.env.CI && !process.env.E2E_VISUAL,
  'the visual comparison runs on CI\'s pinned image (or locally with E2E_VISUAL=1)')

test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** A token of {@link TOKEN_LENGTH} characters no earlier run used. */
function token(): string {
  const stamp = `${Date.now().toString(36)}${Math.floor(Math.random() * 36 ** 4).toString(36)}`
  return `hf${stamp}`.padEnd(TOKEN_LENGTH, '0').slice(0, TOKEN_LENGTH)
}

/** A quiz with the spec's cover and one question, its title carrying the run's token; optionally a video to watch. */
async function quiz(request: APIRequestContext, sponsorToken: string, title: string, video?: string) {
  const cover = await upload(request, sponsorToken, POLICY.image, 'cover.png', 'image/png', PNG)
  const saved = await run(request, sponsorToken, 'QB_QUIZ_SAVE', { title, intro: 'Frame check.', cover,
    timeLimitSec: 300, shuffleQuestions: false })
  const quizId = saved.quizId as string
  if (video) await run(request, sponsorToken, 'QB_MATERIAL_SAVE', { quizId, kind: 'VIDEO_LINK', title: 'Short', url: video })
  await run(request, sponsorToken, 'QB_QUESTION_SAVE', { quizId, stem: 'Which one?', points: 1,
    options: [{ text: 'This one', correct: true }, { text: 'That one', correct: false }] })
  await run(request, sponsorToken, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  return quizId
}

/** The home searched for the run's token, once every picture in it has loaded. */
async function searched(page: Page, words: string, cards: number) {
  await page.goto(`/taker/?q=${encodeURIComponent(words)}`)
  const home = page.getByTestId('home')
  await expect(home).toBeVisible()
  await expect(page.getByTestId('feed-searched')).toBeVisible()
  if (cards > 0) await expect(home.getByTestId('feed-card')).toHaveCount(cards)
  else await expect(home.getByTestId('home-empty')).toBeVisible()
  await expect.poll(() => home.evaluate((section) => [...section.querySelectorAll('img')]
    .every((img) => img.complete && img.naturalWidth > 0)), { message: 'the pictures load' }).toBe(true)
  await page.evaluate(() => document.fonts.ready)
  return home
}

/** The home's content as the screen shows it: its section, widened by the main column's padding. */
async function homeClip(page: Page) {
  const box = (await page.getByTestId('home').boundingBox())!
  return { x: Math.max(0, box.x - MAIN_PADDING), y: box.y, width: box.width + 2 * MAIN_PADDING, height: box.height }
}

test('the home stays pixel for pixel as it was before F-F4', async ({ page, request }) => {
  test.setTimeout(300_000)
  await page.route('https://www.youtube-nocookie.com/**', (route) => route.fulfill({ contentType: 'text/html',
    body: '<!doctype html><html lang="en"><title>Player</title></html>' }))
  await page.emulateMedia({ reducedMotion: 'reduce' })

  // The data: one Sponsor of a fixed name, two cards with a cover and two short videos (the shelf), newest first.
  const sponsor = await approvedSponsor(request, 'Fern')
  await fund(request, sponsor.token)
  const words = token()
  const made = [
    { name: 'Frame check: tea', video: undefined },
    { name: 'Frame check: kites', video: undefined },
    { name: 'Frame check: a short song', video: 'https://www.youtube.com/shorts/dQw4w9WgXcQ' },
    { name: 'Frame check: a short dance', video: 'https://youtube.com/shorts/aaaaaaaaaaa' },
  ]
  const me = await taker(request, 'Hana')
  for (const card of made) {
    const quizId = await quiz(request, sponsor.token, `${card.name} ${words}`, card.video)
    const publicationId = await livePublication(request, sponsor.token, quizId, card.name, 1)
    await untilLive(request, me.token, publicationId)
  }

  await page.setViewportSize({ width: WIDTHS[0], height: VIEWPORT_HEIGHT })
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()

  for (const appearance of APPEARANCES) {
    await setAppearance(page, appearance)
    for (const width of WIDTHS) {
      await page.setViewportSize({ width, height: VIEWPORT_HEIGHT })
      const home = await searched(page, words, made.length)
      await expect(home.getByTestId('shorts-shelf')).toHaveCount(1)
      // The same content in the same order (the DOM's side of the comparison, read first when a picture differs).
      await expect(home.getByTestId('feed-card-name')).toHaveText(made.map((card) => card.name).reverse())
      await expect(page).toHaveScreenshot(`home-${width}-${appearance}.png`, { clip: await homeClip(page),
        stylePath: STYLE, animations: 'disabled', caret: 'hide', maxDiffPixels: MAX_DIFF_PIXELS })

      if (width === WIDTHS[1]) {
        await searched(page, `${words}x`, 0)
        await expect(page).toHaveScreenshot(`home-empty-${width}-${appearance}.png`, { clip: await homeClip(page),
          stylePath: STYLE, animations: 'disabled', caret: 'hide', maxDiffPixels: MAX_DIFF_PIXELS })
      }

      if ((HEADER_WIDTHS as readonly number[]).includes(width)) {
        await page.goto('/taker/')
        await expect(page.getByTestId('home')).toBeVisible()
        const header = page.locator('header').first()
        const wordmark = header.getByTestId('wordmark')
        await expect(wordmark).toBeVisible()
        // The buttons at the right: the filters and the search stay (the menu goes with F-F4b, which checks it).
        await expect(header.getByTestId('feed-filters-open')).toBeVisible()
        await expect(header.getByTestId('search-open')).toBeVisible()
        await expect(header.getByTestId('header-my-sponsor')).toHaveCount(0)
        const box = (await header.boundingBox())!
        const mark = (await wordmark.boundingBox())!
        await expect(page).toHaveScreenshot(`header-${width}-${appearance}.png`, {
          clip: { x: box.x, y: box.y, width: mark.x + mark.width + WORDMARK_ROOM - box.x, height: box.height },
          animations: 'disabled', caret: 'hide', maxDiffPixels: MAX_DIFF_PIXELS })
      }
    }
  }
})
