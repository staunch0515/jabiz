import type { APIRequestContext, Page } from '@playwright/test'
import { completeQuiz, query } from './content'
import { sharedVideos, VIDEO_HOST } from './exposure-videos'
import { fundedSponsor, searchHome, taker, untilLive } from './play'
import { decideReview, publicationOf } from './publishing'
import {
  adminToken,
  expect,
  expectAccessible,
  prepareQuizbuks,
  run,
  setAppearance,
  signIn,
  test,
  unique,
} from './support'

/*
 * F-S13 (docs/quizbuks/plans/impl/F-S13.md): a provider shares a vertical and a horizontal video; another Sponsor uses
 * the vertical one on two publications (one with a single exposure bought, through the tests' own purchase, which runs
 * F-M8's sub-process) and the horizontal one on a third, and has a short of its own. The home's preview never counts;
 * in the vertical mode the pushes follow the own short, opening one and watching it counts; the publication with one
 * exposure bought ends within a minute; the taker who opened the pushes does not see them again; the start page counts
 * the horizontal video. The players are pages of another site (timed on the screen), answered by a blank page here.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** A live publication of a new quiz of the Sponsor's: with the shared video, or a Shorts link of its own. */
async function live(request: APIRequestContext, token: string, name: string,
  video: { shared: string } | { own: string }) {
  let versionId: string
  if ('shared' in video) {
    const quiz = await completeQuiz(request, token, { title: `${name} quiz` })
    versionId = String((await query(request, token, 'qb.sponsor.quiz-versions', { quizId: quiz.quizId }))[0].versionId)
  } else {
    const quiz = await run(request, token, 'QB_QUIZ_SAVE', { title: `${name} quiz`, timeLimitSec: 300 })
    await run(request, token, 'QB_MATERIAL_SAVE', { quizId: quiz.quizId, kind: 'VIDEO_LINK', title: 'Short',
      url: video.own })
    await run(request, token, 'QB_QUESTION_SAVE', { quizId: quiz.quizId, stem: 'What did the short show?', points: 1,
      options: [{ text: 'A song', correct: true }, { text: 'A map' }] })
    versionId = String((await run(request, token, 'QB_QUIZ_PUBLISH_VERSION', { quizId: quiz.quizId })).versionId)
  }
  const now = Date.now()
  const draft = await run(request, token, 'QB_PUBLICATION_SAVE', { versionId, name, regions: ['GLOBAL'],
    startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
    passScore: 1, budgetCap: 500, maxWinners: 5,
    tiers: [{ fromScore: 0, toScore: 0, reward: 0 },
      { fromScore: 1, toScore: 'shared' in video ? 2 : 1, reward: 20 }] })
  const publicationId = String(draft.publicationId)
  if ('shared' in video) {
    await run(request, token, 'QB_PUBLICATION_SHARED_VIDEO_SET', { publicationId, sharedVideoId: video.shared })
  }
  const submitted = await run(request, token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  if (submitted.approval === 'PENDING') {
    await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId,
      decision: 'APPROVE' })
  } else if (submitted.status !== 'LIVE') {
    await decideReview(request, token, publicationId, 'APPROVE')
  }
  await expect.poll(async () => (await publicationOf(request, token, publicationId)).status,
    { message: `${name} is live`, timeout: 30_000 }).toBe('LIVE')
  return publicationId
}

/** The Sponsor's figures of a publication (`qb.sponsor.exposure`). */
async function figures(request: APIRequestContext, token: string, publicationId: string) {
  const row = (await query(request, token, 'qb.sponsor.exposure', { publicationId }))[0] ?? {}
  return { successes: Number(row.successes ?? 0), opens: Number(row.opens ?? 0), purchased: row.purchased ?? null }
}

/** The reports of exposures the page sends, as they go. */
function watchReports(page: Page) {
  const sent: { publicationId: string; kind: string; surface: string }[] = []
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().includes('/api/processes/QB_EXPOSURE_REPORT/')) {
      // A call sent as the page goes may carry no readable body here: it is counted all the same.
      const body = request.postDataJSON() as { reports?: typeof sent } | null
      sent.push(...(body?.reports ?? [{ publicationId: '?', kind: '?', surface: '?' }]))
    }
  })
  return sent
}

test('the home does not count; the vertical mode and the start page do; used up ends; opened is not pushed again',
  async ({ browser, page, request }) => {
    test.setTimeout(480_000)
    const players = async (target: Page) => {
      await target.route('https://www.youtube-nocookie.com/**', (route) => route.fulfill({ contentType: 'text/html',
        body: '<!doctype html><html lang="en"><title>Player</title></html>' }))
      await target.route(`${VIDEO_HOST}/**`, (route) => route.fulfill({ contentType: 'text/html',
        body: '<!doctype html><html lang="en"><title>Video</title><body style="background:#000"></body></html>' }))
      await target.setViewportSize({ width: 390, height: 844 })
    }
    await players(page)
    const mark = unique('')
    const videos = await sharedVideos(request, mark)
    const sponsor = await fundedSponsor(request)
    const base = `Exposure ${mark}`
    const own = await live(request, sponsor.token, `${base} own`, { own: 'https://www.youtube.com/shorts/dQw4w9WgXcQ' })
    const limited = await live(request, sponsor.token, `${base} limited`, { shared: videos.vertical })
    const unlimited = await live(request, sponsor.token, `${base} unlimited`, { shared: videos.vertical })
    const wide = await live(request, sponsor.token, `${base} wide`, { shared: videos.horizontal })
    // One exposure bought for the first (F-M8's purchase, through the tests' own root of its sub-process).
    await run(request, videos.hq, 'QB_E2E_EXPOSURE_PURCHASE', { publicationId: limited, purchased: 1, unit: 1 })
    expect((await figures(request, sponsor.token, limited)).purchased).toBe(1)

    // 1. The home's preview never counts: another taker (a browser of their own) stays on the home, then leaves it
    // (what waits is sent as the page goes).
    const onlooker = await taker(request, 'Kai')
    for (const id of [own, limited, unlimited, wide]) await untilLive(request, onlooker.token, id)
    const elsewhere = await browser.newContext({ baseURL: test.info().project.use.baseURL })
    const home = await elsewhere.newPage()
    await players(home)
    const homeReports = watchReports(home)
    await signIn(home, '/taker/', onlooker.email)
    await expect(home.getByTestId('home')).toBeVisible()
    await searchHome(home, base)
    await expect(home.getByTestId('feed-card')).toHaveCount(4)
    await expect(home.getByTestId('shorts-shelf')).toHaveCount(1)
    await home.waitForTimeout(12_000)
    await home.goto('/taker/account/preferences')
    await home.waitForTimeout(1_000)
    await elsewhere.close()
    expect(homeReports).toEqual([])
    for (const id of [limited, unlimited, wide]) {
      expect((await figures(request, sponsor.token, id)).successes, `the home counted ${id}`).toBe(0)
    }

    // 2. The vertical mode: opened on the own short, the pushes follow; one watched counts, both are opened.
    const me = await taker(request, 'Mio')
    const sent = watchReports(page)
    await signIn(page, '/taker/', me.email)
    await expect(page.getByTestId('home')).toBeVisible()
    await searchHome(page, base)
    const shelf = page.getByTestId('shorts-shelf')
    await shelf.locator(`[data-testid="shorts-card"][data-publication="${own}"]`).getByTestId('shorts-card-open')
      .click()
    const viewer = page.getByTestId('shorts-viewer')
    await expect(viewer).toBeVisible()
    const screens = viewer.getByTestId('shorts-screen')
    await expect(screens).toHaveCount(3)
    await expect(screens.first()).toHaveAttribute('data-publication', own)
    const order = await screens.evaluateAll((all) => all.map((s) => (s as HTMLElement).dataset.publication))
    expect(order.slice(1).sort()).toEqual([limited, unlimited].sort())
    const current = viewer.locator('[data-testid="shorts-screen"][data-current]')
    await viewer.getByTestId('shorts-next').click()
    await expect(current).toHaveAttribute('data-publication', order[1]!)
    await expectAccessible(page, 'app vertical mode on a push')
    // Watch the limited one for the seconds (whichever place it has), the other only opened.
    if (order[1] !== limited) {
      await viewer.getByTestId('shorts-next').click()
      await expect(current).toHaveAttribute('data-publication', limited)
    }
    await page.waitForTimeout(12_000)
    if (order[2] !== limited) {
      await viewer.getByTestId('shorts-next').click()
      await expect(current).toHaveAttribute('data-publication', order[2]!)
    }
    // The reports go within the interval (30 s), or as soon as the page goes.
    await expect.poll(async () => (await figures(request, sponsor.token, limited)).successes,
      { message: 'the vertical mode counted the exposure', timeout: 60_000 }).toBe(1)
    await expect.poll(async () => (await figures(request, sponsor.token, unlimited)).opens,
      { message: 'the other push was opened', timeout: 60_000 }).toBe(1)
    expect((await figures(request, sponsor.token, unlimited)).successes).toBe(0)
    expect(sent.filter((r) => r.kind === 'EXPOSURE' && r.publicationId === limited)).toHaveLength(1)

    // 3. Used up: the minute's job ends the publication; the Sponsor is told why.
    await expect.poll(async () => (await publicationOf(request, sponsor.token, limited)).status,
      { message: 'the used-up publication ends', timeout: 150_000, intervals: [5_000] }).toBe('ENDED')
    expect((await publicationOf(request, sponsor.token, limited)).endReason).toBe('EXPOSURE')
    // F-M8d: the publication's fee figures (what the page shows) agree: one bought, the one counted, none settled yet.
    const fee = (await query(request, sponsor.token, 'qb.sponsor.publication-exposure', { publicationId: limited }))[0]
    expect(Number(fee?.purchased)).toBe(1)
    expect(Number(fee?.counted)).toBe(1)

    // 4. Opened once, never pushed again: the vertical mode shows the own short alone now.
    await page.keyboard.press('Escape')
    await expect(viewer).toHaveCount(0)
    await page.reload()
    await searchHome(page, base)
    await page.getByTestId('shorts-shelf').locator(`[data-testid="shorts-card"][data-publication="${own}"]`)
      .getByTestId('shorts-card-open').click()
    await expect(page.getByTestId('shorts-viewer')).toBeVisible()
    await expect(page.getByTestId('shorts-screen')).toHaveCount(1)
    await page.keyboard.press('Escape')

    // 5. The start page counts the horizontal video on the screen (light, then dark).
    await page.goto(`/taker/p/${wide}`)
    await expect(page.getByTestId('exposure-start')).toBeVisible()
    await expectAccessible(page, 'app start page of a shared video')
    await page.waitForTimeout(12_000)
    await page.goto('/taker/account/preferences')
    await expect.poll(async () => (await figures(request, sponsor.token, wide)).successes,
      { message: 'the start page counted the exposure', timeout: 60_000 }).toBe(1)
    await setAppearance(page, 'dark')
    await page.goto(`/taker/p/${wide}`)
    await expect(page.getByTestId('exposure-start')).toBeVisible()
    await expectAccessible(page, 'app start page of a shared video (dark)')
  })
