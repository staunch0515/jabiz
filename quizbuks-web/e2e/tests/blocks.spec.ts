import type { APIRequestContext } from '@playwright/test'
import { completeQuiz, query } from './content'
import { contentAdmin } from './legal'
import { reviewerFor } from './operators'
import { fundedSponsor, searchHome, taker, untilLive } from './play'
import { approvedSponsor, decideReview, publicationOf } from './publishing'
import {
  expect,
  expectAccessible,
  PASSWORD,
  prepareQuizbuks,
  run,
  setAppearance,
  signIn,
  test,
  tokenOf,
  unique,
} from './support'

/*
 * F-S8 (docs/quizbuks/plans/impl/F-S8.md): a provider's shared video is used by two publications of another Sponsor,
 * who also has one with its own content. A taker blocks the video provider from a card on the home: both of its
 * publications go, the third stays; in the app's settings the block is listed and unblocked, and both come back. Then
 * "not interested" on the third (undone once from the message, then done again): its link says "you blocked this"
 * (light and dark) and unblocking there offers the start again. The player is a blank page: no YouTube here.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** A YouTube video id (11 characters) no earlier run used. */
function videoId(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2)}`.slice(-11).padStart(11, 'b')
}

/** A word list code no earlier run used (the list only grows), as topics.spec makes them. */
function newCode(): string {
  const countdown = (36 ** 9 - Date.now()).toString(36).padStart(9, '0')
  return `0e2e-${countdown}${Math.floor(Math.random() * 36 ** 2).toString(36).padStart(2, '0')}`
}

/** A provider's video link shared and approved by its operator; the shared video's key. */
async function sharedVideo(request: APIRequestContext, mark: string) {
  const hq = await tokenOf(request, await contentAdmin(request, 'Hana'), PASSWORD, 'admin')
  const code = newCode()
  await run(request, hq, 'QB_TOPIC_ADD', { topicId: code, nameZh: `宠物 ${mark}`, nameJa: `ペット ${mark}`,
    nameEn: `Pets ${mark}` })
  const provider = await approvedSponsor(request, 'Pavo')
  const asset = await run(request, provider.token, 'QB_ASSET_SAVE', { kind: 'VIDEO_LINK', name: `Cats ${mark}`,
    language: 'en', tags: [], confirmed: [], url: `https://www.youtube.com/watch?v=${videoId()}`, durationSec: 95 })
  const shared = await run(request, provider.token, 'QB_SHARED_VIDEO_SHARE', { assetId: asset.assetId,
    titleZh: `猫 ${mark}`, titleJa: `猫 ${mark}`, titleEn: `Cats ${mark}`, topics: [code], vertical: false })
  await run(request, await reviewerFor(request, provider.token), 'APPROVAL_DECIDE', {
    requestId: shared.approvalRequestId, decision: 'APPROVE' })
  const sharedVideoId = String(shared.sharedVideoId)
  await expect.poll(async () => (await query(request, provider.token, 'qb.sponsor.my-shared-videos', {}))
    .find((row) => row.sharedVideoId === sharedVideoId)?.status, { message: 'the share is approved',
    timeout: 30_000 }).toBe('APPROVED')
  return sharedVideoId
}

/** A live publication of a new quiz of the Sponsor's, with the shared video when given. */
async function live(request: APIRequestContext, token: string, name: string, sharedVideoId: string | null) {
  const quiz = await completeQuiz(request, token, { title: `${name} quiz` })
  const versionId = (await query(request, token, 'qb.sponsor.quiz-versions', { quizId: quiz.quizId }))[0].versionId
  const now = Date.now()
  const draft = await run(request, token, 'QB_PUBLICATION_SAVE', { versionId, name, regions: ['GLOBAL'],
    startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
    passScore: 1, budgetCap: 500, maxWinners: 5,
    tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 2, reward: 20 }] })
  const publicationId = String(draft.publicationId)
  if (sharedVideoId) {
    await run(request, token, 'QB_PUBLICATION_SHARED_VIDEO_SET', { publicationId, sharedVideoId })
  }
  const submitted = await run(request, token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  if (submitted.status !== 'LIVE') await decideReview(request, token, publicationId, 'APPROVE')
  await expect.poll(async () => (await publicationOf(request, token, publicationId)).status,
    { message: `${name} is live`, timeout: 30_000 }).toBe('LIVE')
  return publicationId
}

test('blocking a video provider hides both of its publications; a blocked link says so and unblocks',
  async ({ page, request }) => {
    test.setTimeout(420_000)
    await page.route('https://www.youtube-nocookie.com/**', (route) => route.fulfill({ contentType: 'text/html',
      body: '<!doctype html><html lang="en"><title>Player</title></html>' }))
    const mark = unique('')
    const video = await sharedVideo(request, mark)
    const sponsor = await fundedSponsor(request)
    const base = `Blocks ${mark}`
    const first = await live(request, sponsor.token, `${base} one`, video)
    const second = await live(request, sponsor.token, `${base} two`, video)
    const own = await live(request, sponsor.token, `${base} own`, null)
    const me = await taker(request, 'Rin')
    for (const id of [first, second, own]) await untilLive(request, me.token, id)

    await page.setViewportSize({ width: 390, height: 844 })
    await signIn(page, '/taker/', me.email)
    await expect(page.getByTestId('home')).toBeVisible()
    await searchHome(page, base)
    const cards = page.getByTestId('feed-card')
    const card = (id: string) => page.locator(`[data-testid="feed-card"][data-publication="${id}"]`)
    await expect(cards).toHaveCount(3)

    // 1. The video provider, from the first card's menu: both publications with its video go, the own one stays.
    await card(first).getByTestId('block-menu').click()
    await expect(page.getByTestId('block-PROVIDER')).toBeVisible()
    await expectAccessible(page, 'app card menu')
    await page.getByTestId('block-PROVIDER').click()
    await expect(cards).toHaveCount(1)
    await expect(card(own)).toBeVisible()
    await expectAccessible(page, 'app home after blocking a video provider')

    // 2. The app's settings list it; unblocked there, both come back.
    await page.goto('/taker/account/preferences')
    const list = page.getByTestId('account-blocks')
    const item = list.getByTestId('block-list-item')
    await expect(item).toHaveCount(1)
    await expect(item).toHaveAttribute('data-kind', 'PROVIDER')
    await expectAccessible(page, 'app settings with a block')
    await item.getByTestId('block-list-unblock').click()
    await expect(list.getByTestId('block-list-empty')).toBeVisible()
    await page.goto('/taker/')
    await searchHome(page, base)
    await expect(cards).toHaveCount(3)

    // 3. Not interested in the own one: undone once from the message, then done again.
    await card(own).getByTestId('block-menu').click()
    await page.getByTestId('block-PUBLICATION').click()
    await expect(card(own)).toHaveCount(0)
    await page.getByRole('button', { name: 'Undo' }).click()
    await expect(card(own)).toBeVisible()
    await expect(cards).toHaveCount(3)
    await card(own).getByTestId('block-menu').click()
    await page.getByTestId('block-PUBLICATION').click()
    await expect(card(own)).toHaveCount(0)
    await expect(cards).toHaveCount(2)

    // 4. Its link: "you blocked this", nothing of it shown, no start; unblocked there, the start is offered again.
    await page.goto(`/taker/p/${own}`)
    const blocked = page.getByTestId('detail-blocked-page')
    await expect(blocked).toBeVisible()
    await expect(blocked.getByTestId('detail-blocked-item')).toHaveAttribute('data-kind', 'PUBLICATION')
    await expect(page.getByTestId('detail-start')).toHaveCount(0)
    await expectAccessible(page, 'app blocked publication')
    await setAppearance(page, 'dark')
    await page.reload()
    await expect(blocked).toBeVisible()
    await expectAccessible(page, 'app blocked publication (dark)')
    await blocked.getByTestId('detail-unblock').click()
    await expect(page.getByTestId('detail')).toBeVisible()
    await expect(page.getByTestId('detail-start')).toBeEnabled()
    // The server agrees: the start is no longer refused.
    expect((await run(request, me.token, 'QB_ATTEMPT_START', { publicationId: own })).attemptId).toBeTruthy()
  })
