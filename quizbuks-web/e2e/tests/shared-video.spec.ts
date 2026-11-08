import type { APIRequestContext, Page } from '@playwright/test'
import { signInAdmin } from './admin'
import { completeQuiz, query } from './content'
import { contentAdmin } from './legal'
import { reviewerFor } from './operators'
import { fundedSponsor, searchHome, taker, untilLive } from './play'
import { approvedSponsor, decideReview, publicationOf } from './publishing'
import {
  expect,
  expectAccessible,
  mailTo,
  PASSWORD,
  prepareQuizbuks,
  run,
  setAppearance,
  signIn,
  signOut,
  test,
  tokenOf,
  unique,
} from './support'

/*
 * F-S12 (docs/quizbuks/plans/impl/F-S12.md): a provider shares a video link of their library (three titles, a word of
 * the list, horizontal); their operator reviews it once (rule qb.shared-video.review, which QB_SETUP proposes and
 * prepareQuizbuks publishes) — headquarters sees it waiting in the administration; another Sponsor picks it from the
 * catalog in the publication wizard, the preview shows it at once as the card's video, and the publication goes live
 * with it: the App's home card plays it and the start page carries it on. The provider withdraws it (new picks stop,
 * the live publication goes on), then the operator takes it down: the minute's job ends the publication using it
 * (reason SHARED_VIDEO, not a takedown of the publication), both sides are mailed, and the App no longer lists it.
 * The player is a blank page here: the test does not reach YouTube.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

const PLAYER = 'https://www.youtube-nocookie.com/embed/'

/** A YouTube video id (11 characters) no earlier run used: the card's player names the shared video. */
function videoId(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2)}`.slice(-11).padStart(11, 'v')
}

/** A word list code no earlier run used (`QB_TOPIC_ADD`; the list only grows), as topics.spec makes them. */
function newCode(): string {
  const countdown = (36 ** 9 - Date.now()).toString(36).padStart(9, '0')
  return `0e2e-${countdown}${Math.floor(Math.random() * 36 ** 2).toString(36).padStart(2, '0')}`
}

/** One shared video as the administration's template has it (an operator's member reads its own sponsors'). */
async function adminSharedVideo(request: APIRequestContext, token: string, sharedVideoId: string) {
  const response = await request.post('/api/queries/qb.admin.shared-videos', {
    headers: { Authorization: `Bearer ${token}` },
    data: { filters: [{ field: 'sharedVideoId', op: 'eq', value: sharedVideoId }], limit: 1 },
  })
  expect(response.status(), await response.text()).toBe(200)
  const [row] = (await response.json()).items as Record<string, unknown>[]
  expect(row, 'the shared video in the administration').toBeTruthy()
  return row
}

/** The provider's own share (`qb.sponsor.my-shared-videos`) of the asset. */
async function myShare(request: APIRequestContext, token: string, assetId: string) {
  return (await query(request, token, 'qb.sponsor.my-shared-videos', {})).find((row) => row.assetId === assetId)
}

/** Signs a Sponsor in at the console, after whoever was signed in there. */
async function consoleAs(page: Page, email: string) {
  // Cleared where the page is: a console opened signed in first would keep its session.
  if (page.url().startsWith('http')) await signOut(page)
  await signIn(page, '/sponsor/', email)
  await expect(page.getByTestId('dashboard-status')).toBeVisible()
}

test('a provider shares a video, its operator reviews it once, another Sponsor uses it until it is taken down',
  async ({ page, request }) => {
    test.setTimeout(480_000)
    // No YouTube here: the player is a blank page.
    await page.route('https://www.youtube-nocookie.com/**', (route) => route.fulfill({ contentType: 'text/html',
      body: '<!doctype html><html lang="en"><title>Player</title></html>' }))
    const mark = unique('')
    const id = videoId()
    const player = `${PLAYER}${id}`
    const title = { en: `Harbour at dawn ${mark}`, ja: `夜明けの港 ${mark}`, zh: `黎明的港口 ${mark}` }

    // A word of the list for the share (headquarters keep the list; F-S9).
    const word = { code: newCode(), english: `Harbours ${mark}` }
    const hq = await tokenOf(request, await contentAdmin(request, 'Hana'), PASSWORD, 'admin')
    await run(request, hq, 'QB_TOPIC_ADD', { topicId: word.code, nameZh: `港口 ${mark}`, nameJa: `港 ${mark}`,
      nameEn: word.english })

    // 1. The provider: a video link with its duration in the library, shared from its row.
    const provider = await approvedSponsor(request, 'Pavo')
    const assetName = unique('Harbour clip ')
    const asset = await run(request, provider.token, 'QB_ASSET_SAVE', { kind: 'VIDEO_LINK', name: assetName,
      language: 'en', tags: [], confirmed: [], url: `https://www.youtube.com/watch?v=${id}`, durationSec: 95 })
    const assetId = String(asset.assetId)
    await consoleAs(page, provider.email)
    await page.goto('/sponsor/assets')
    const row = page.getByTestId('asset-row').filter({ hasText: assetName })
    await expect(row).toBeVisible()
    await row.getByTestId('asset-share').click()
    const dialog = page.getByTestId('share-video-dialog')
    await expect(dialog.getByTestId('share-video-url')).toHaveText(`https://www.youtube.com/watch?v=${id}`)
    // The titles start from the asset's name; each language gets its own.
    await expect(dialog.getByTestId('share-video-titleEn')).toHaveValue(assetName)
    await dialog.getByTestId('share-video-titleZh').fill(title.zh)
    await dialog.getByTestId('share-video-titleJa').fill(title.ja)
    await dialog.getByTestId('share-video-titleEn').fill(title.en)
    await dialog.getByTestId('share-video-topic').click()
    await page.getByPlaceholder('Search topics').fill(word.english)
    await page.getByRole('option', { name: word.english, exact: true }).click()
    await expect(dialog.getByTestId(`share-video-topic-${word.code}`)).toContainText(word.english)
    await expect(dialog.getByTestId('share-video-vertical')).not.toBeChecked()
    await expectAccessible(page, 'console share a video')
    await dialog.getByTestId('share-video-submit').click()
    await expect(dialog).toHaveCount(0)
    const share = await myShare(request, provider.token, assetId)
    expect(share?.status).toBe('PENDING')
    const sharedVideoId = String(share!.sharedVideoId)
    const mine = page.locator(`[data-testid="my-shared-video"][data-id="${sharedVideoId}"]`)
    await expect(mine.getByTestId('shared-video-status')).toHaveAttribute('data-status', 'PENDING')
    await expect(mine).toContainText(title.en)
    await expect(mine).toContainText(word.english)
    await expectAccessible(page, 'console my shared videos in review')

    // 2. Waiting for the review: not in the catalog yet; headquarters see it in the administration.
    const user = await fundedSponsor(request)
    expect(await query(request, user.token, 'qb.sponsor.shared-videos', { q: mark })).toHaveLength(0)
    await signInAdmin(page)
    await page.goto('/admin/qb/shared-videos')
    await expect(page.getByTestId('qb-shared-videos')).toBeVisible()
    await page.getByTestId('shared-videos-search').fill(mark)
    const adminRow = page.locator(`[data-testid="shared-video-row"][data-id="${sharedVideoId}"]`)
    await expect(adminRow.getByTestId('shared-video-status')).toHaveAttribute('data-status', 'PENDING')
    await expectAccessible(page, 'admin shared videos')
    await adminRow.getByTestId('shared-video-link').click()
    const detail = page.getByTestId('shared-video-page')
    await expect(detail).toHaveAttribute('data-status', 'PENDING')
    await expect(page.getByTestId('shared-video-content')).toContainText(word.code)
    await expect(page.getByTestId('shared-video-watch')).toHaveAttribute('href', `https://www.youtube.com/watch?v=${id}`)
    await expectAccessible(page, 'admin shared video in review')

    // 3. The provider's operator approves it, once (the operators' review: the request bound to the share).
    const reviewer = await reviewerFor(request, provider.token)
    const pending = await adminSharedVideo(request, reviewer, sharedVideoId)
    expect(pending.status).toBe('PENDING')
    await run(request, reviewer, 'APPROVAL_DECIDE', { requestId: pending.approvalRequestId, decision: 'APPROVE' })
    await expect.poll(async () => (await myShare(request, provider.token, assetId))?.status,
      { message: 'the share is approved', timeout: 30_000 }).toBe('APPROVED')

    // 4. Another Sponsor: a draft of a quiz without a video picks it from the catalog in the wizard's first step.
    const quiz = await completeQuiz(request, user.token, { title: unique('Harbour quiz ') })
    const versionId = (await query(request, user.token, 'qb.sponsor.quiz-versions', { quizId: quiz.quizId }))[0]
      .versionId
    const name = unique('Shared video run ')
    const now = Date.now()
    const draft = await run(request, user.token, 'QB_PUBLICATION_SAVE', { versionId, name, regions: ['GLOBAL'],
      startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
      passScore: 1, budgetCap: 500, maxWinners: 5,
      tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 2, reward: 20 }] })
    const publicationId = String(draft.publicationId)
    await consoleAs(page, user.email)
    await page.goto(`/sponsor/publications/${publicationId}`)
    const wizard = page.getByTestId('publication-wizard')
    await expect(wizard).toHaveAttribute('data-step', '0')
    const field = page.getByTestId('publication-shared-video')
    await expect(field.getByTestId('publication-shared-video-none')).toBeVisible()
    await field.getByTestId('publication-shared-video-choose').click()
    const picker = page.getByTestId('shared-video-picker')
    await picker.getByTestId('shared-video-search').fill(title.en)
    const option = picker.locator(`[data-testid="shared-video-option"][data-id="${sharedVideoId}"]`)
    await expect(option).toBeVisible()
    await expect(picker.getByTestId('shared-video-option')).toHaveCount(1)
    await expect(option).toContainText(word.english)
    await expectAccessible(page, 'console shared video catalog')
    await option.getByTestId('shared-video-pick').click()
    await expect(picker).toHaveCount(0)
    await expect(field.getByTestId('publication-shared-video-picked')).toHaveAttribute('data-id', sharedVideoId)
    await expectAccessible(page, 'console publication with a shared video')
    // Saved with the draft on the way through the steps.
    for (const step of ['1', '2', '3']) {
      await page.getByTestId('publication-next').click()
      await expect(wizard).toHaveAttribute('data-step', step)
    }
    await expect.poll(async () => (await publicationOf(request, user.token, publicationId)).sharedVideoId,
      { message: 'the draft keeps the pick' }).toBe(sharedVideoId)

    // The preview, before any review: the home card plays the shared video, and the start page leads with it.
    await page.goto(`/sponsor/publications/${publicationId}/preview`)
    const frame = page.getByTestId('preview-frame')
    await expect(frame).toHaveAttribute('data-screen', 'page')
    await expect(frame.getByTestId('detail-hero').locator('iframe')).toHaveAttribute('src', player)
    await page.getByTestId('preview-screens').getByRole('radio', { name: 'Home card' }).click()
    await expect(frame).toHaveAttribute('data-screen', 'home')
    await expect(frame.getByTestId('feed-card-video-mark')).toBeVisible()
    await expectAccessible(page, 'console preview with a shared video')

    // Submitted, reviewed as a publication (the video itself is not reviewed again) and live.
    await page.goto(`/sponsor/publications/${publicationId}`)
    for (const step of ['1', '2', '3']) {
      await page.getByTestId('publication-next').click()
      await expect(wizard).toHaveAttribute('data-step', step)
    }
    await expect(page.getByTestId('publication-ready')).toBeVisible()
    await page.getByTestId('publication-submit').click()
    await page.getByRole('alertdialog').getByRole('button', { name: 'Submit' }).click()
    await expect(page.getByTestId('publication-view')).toHaveAttribute('data-status', 'PENDING_REVIEW')
    await decideReview(request, user.token, publicationId, 'APPROVE')
    await expect.poll(async () => (await publicationOf(request, user.token, publicationId)).status,
      { message: 'the publication is live', timeout: 30_000 }).toBe('LIVE')
    await expect.poll(async () => (await myShare(request, provider.token, assetId))?.usesRunning,
      { message: 'the provider sees the use' }).toBe(1)

    // 5. The App: the home card plays the shared video; a tap opens the start page, where the same player plays on.
    const me = await taker(request, 'Mio')
    await untilLive(request, me.token, publicationId)
    await page.setViewportSize({ width: 390, height: 844 })
    await signIn(page, '/taker/', me.email)
    await expect(page.getByTestId('home')).toBeVisible()
    await searchHome(page, name)
    const card = page.getByTestId('feed-card').filter({ hasText: name })
    await expect(card).toHaveAttribute('data-video', 'true')
    await card.evaluate((element) => element.scrollIntoView({ block: 'center' }))
    const stage = page.getByTestId('video-stage')
    await expect(stage.locator('iframe')).toHaveAttribute('src', new RegExp(`^${player}\\?autoplay=1&mute=1`))
    await expectAccessible(page, 'app home with a shared video')
    await page.getByTestId('video-open').click()
    await expect(page).toHaveURL(new RegExp(`/taker/p/${publicationId}$`))
    await expect(page.getByTestId('detail-hero').getByTestId('video-slot')).toHaveAttribute('data-mode', 'page')
    await expect(stage).toHaveAttribute('data-mode', 'page')
    await expect(stage.locator('iframe')).toHaveAttribute('src', new RegExp(`^${player}\\?`))
    await expect(page.locator('iframe')).toHaveCount(1)
    await expect(page.getByTestId('detail-start')).toBeEnabled()
    await expectAccessible(page, 'app start page with a shared video')

    // 6. The provider withdraws it: new picks stop, the publication submitted with it goes on.
    await page.setViewportSize({ width: 1280, height: 720 })
    await consoleAs(page, provider.email)
    await page.goto('/sponsor/assets')
    await expect(mine.getByTestId('shared-video-uses')).toContainText('1')
    await mine.getByTestId('shared-video-withdraw').click()
    await page.getByRole('alertdialog').getByRole('button', { name: 'Withdraw' }).click()
    await expect(mine.getByTestId('shared-video-status')).toHaveAttribute('data-status', 'WITHDRAWN')
    await expect(mine.getByTestId('shared-video-withdraw')).toHaveCount(0)
    expect(await query(request, user.token, 'qb.sponsor.shared-videos', { q: mark })).toHaveLength(0)
    expect((await publicationOf(request, user.token, publicationId)).status).toBe('LIVE')

    // 7. Infringing after all: the operator takes it down (a reason, a second factor). The provider is mailed; the
    // minute's job ends the publication using it, not as a takedown of the publication, and its Sponsor is mailed.
    const reason = `Uses a song without a licence (${mark})`
    await run(request, reviewer, 'QB_SHARED_VIDEO_TAKEDOWN', { sharedVideoId, reason })
    const toProvider = await mailTo(request, provider.email, /taken down/)
    expect(toProvider.text).toContain(reason)
    await expect.poll(async () => {
      const publication = await publicationOf(request, user.token, publicationId)
      return `${publication.status} ${publication.endReason}`
    }, { message: 'the publication using it ends', timeout: 150_000, intervals: [5_000] }).toBe('ENDED SHARED_VIDEO')
    await mailTo(request, user.email, new RegExp(`Your publication "${name}" ended`))
    expect((await adminSharedVideo(request, reviewer, sharedVideoId)).status).toBe('TAKEN_DOWN')

    // The provider reads the reason (dark as well); headquarters see it taken down.
    await page.reload()
    await expect(mine.getByTestId('shared-video-status')).toHaveAttribute('data-status', 'TAKEN_DOWN')
    await expect(mine.getByTestId('shared-video-reason')).toContainText(reason)
    await expectAccessible(page, 'console my shared video taken down')
    await setAppearance(page, 'dark')
    await page.reload()
    await expect(mine.getByTestId('shared-video-reason')).toContainText(reason)
    await expectAccessible(page, 'console my shared video taken down (dark)')
    await signInAdmin(page)
    await page.goto(`/admin/qb/shared-videos/${sharedVideoId}`)
    await expect(page.getByTestId('shared-video-page')).toHaveAttribute('data-status', 'TAKEN_DOWN')
    await expect(page.getByTestId('shared-video-content')).toContainText(reason)
    await expectAccessible(page, 'admin shared video taken down (dark)')

    // The App no longer lists the publication.
    await signIn(page, '/taker/', me.email)
    await expect(page.getByTestId('home')).toBeVisible()
    await searchHome(page, name)
    await expect(page.getByTestId('feed-card').filter({ hasText: name })).toHaveCount(0)
  })
