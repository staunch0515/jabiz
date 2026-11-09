import type { APIRequestContext } from '@playwright/test'
import { completeQuiz, query } from './content'
import { contentAdmin } from './legal'
import { reviewerFor } from './operators'
import { fundedSponsor } from './play'
import { approvedSponsor, decideReview, publicationOf } from './publishing'
import {
  adminToken,
  expect,
  expectAccessible,
  PASSWORD,
  prepareQuizbuks,
  run,
  runJobNow,
  setAppearance,
  signIn,
  test,
  tokenOf,
  unique,
} from './support'

/*
 * F-S14 (docs/quizbuks/plans/impl/F-S14.md): the ledger of a shared video's use. A provider shares a video link;
 * another Sponsor's publication goes live with it. The Sponsor using it writes the ledger entry on the publication's
 * page and sends it; the provider, on the asset library under the videos it shares, sends it back with a reason; the
 * Sponsor reads the reason, corrects the price and sends it again; the provider confirms it. Both open the entry's
 * history (every act, the reason sent back). Once the daily job ran, both see the same figures (fewer than the minimum
 * group, as nobody watched). Both pages pass axe, light and dark.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

const VIDEO_HOST = 'https://e2e-video.quizbuks.test'

/** A word list code no earlier run used (the list only grows), as topics.spec makes them. */
function newCode(): string {
  const countdown = (36 ** 9 - Date.now()).toString(36).padStart(9, '0')
  return `0e2e-${countdown}${Math.floor(Math.random() * 36 ** 2).toString(36).padStart(2, '0')}`
}

/** The provider's video link, shared (horizontal) and approved by its operator; the provider and the video. */
async function sharedVideo(request: APIRequestContext, mark: string) {
  const hq = await tokenOf(request, await contentAdmin(request, 'Hana'), PASSWORD, 'admin')
  const code = newCode()
  await run(request, hq, 'QB_TOPIC_ADD', { topicId: code, nameZh: `宠物 ${mark}`, nameJa: `ペット ${mark}`,
    nameEn: `Pets ${mark}` })
  const provider = await approvedSponsor(request, 'Pavo')
  const asset = await run(request, provider.token, 'QB_ASSET_SAVE', { kind: 'VIDEO_LINK', name: `Cats ${mark}`,
    language: 'en', tags: [], confirmed: [], url: `${VIDEO_HOST}/wide/${encodeURIComponent(mark)}`, durationSec: 95 })
  const shared = await run(request, provider.token, 'QB_SHARED_VIDEO_SHARE', { assetId: asset.assetId,
    titleZh: `猫 ${mark}`, titleJa: `猫 ${mark}`, titleEn: `Cats ${mark}`, topics: [code], vertical: false })
  await run(request, await reviewerFor(request, provider.token), 'APPROVAL_DECIDE', {
    requestId: shared.approvalRequestId, decision: 'APPROVE' })
  const sharedVideoId = String(shared.sharedVideoId)
  await expect.poll(async () => (await query(request, provider.token, 'qb.sponsor.my-shared-videos', {}))
    .find((row) => row.sharedVideoId === sharedVideoId)?.status, { message: 'the share is approved',
    timeout: 30_000 }).toBe('APPROVED')
  return { provider, sharedVideoId }
}

/** A live publication of a new quiz of the Sponsor's, with the shared video. */
async function live(request: APIRequestContext, token: string, name: string, sharedVideoId: string) {
  const quiz = await completeQuiz(request, token, { title: `${name} quiz` })
  const versionId = String((await query(request, token, 'qb.sponsor.quiz-versions', { quizId: quiz.quizId }))[0]
    .versionId)
  const now = Date.now()
  const draft = await run(request, token, 'QB_PUBLICATION_SAVE', { versionId, name, regions: ['GLOBAL'],
    startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
    passScore: 1, budgetCap: 500, maxWinners: 5,
    tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 2, reward: 20 }] })
  const publicationId = String(draft.publicationId)
  await run(request, token, 'QB_PUBLICATION_SHARED_VIDEO_SET', { publicationId, sharedVideoId })
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

test('the user files the ledger entry, the provider sends it back, the user corrects it, the provider confirms; '
  + 'both see the figures', async ({ browser, page, request }) => {
  test.setTimeout(300_000)
  const mark = unique('')
  const { provider, sharedVideoId } = await sharedVideo(request, mark)
  const user = await fundedSponsor(request)
  const name = `Ledger ${mark}`
  const publicationId = await live(request, user.token, name, sharedVideoId)
  const players = async (target: typeof page) => {
    await target.route(`${VIDEO_HOST}/**`, (route) => route.fulfill({ contentType: 'text/html',
      body: '<!doctype html><html lang="en"><title>Video</title><body style="background:#000"></body></html>' }))
  }
  await players(page)

  // 1. The user writes the entry on the publication's page and sends it.
  await signIn(page, '/sponsor/', user.email)
  await expect(page.getByTestId('dashboard-status')).toBeVisible()
  await page.goto(`/sponsor/publications/${publicationId}`)
  const section = page.getByTestId('video-use-section')
  await expect(section).toBeVisible()
  await expect(section.getByTestId('video-use-none')).toBeVisible()
  await expect(section.getByTestId('video-use-not-counted')).toBeVisible()
  await section.getByTestId('video-use-edit').click()
  const dialog = page.getByTestId('video-use-dialog')
  await dialog.getByTestId('video-use-pricing-PER_TAKER').click()
  await dialog.getByTestId('video-use-price').fill('100')
  await dialog.getByTestId('video-use-note-input').fill('Agreed by mail')
  await expectAccessible(page, 'sponsor ledger entry dialog')
  await dialog.getByTestId('video-use-save').click()
  await expect(dialog).toHaveCount(0)
  await expect(section.getByTestId('video-use-status')).toHaveAttribute('data-status', 'DRAFT')
  await section.getByTestId('video-use-submit').click()
  await expect(section.getByTestId('video-use-status')).toHaveAttribute('data-status', 'SUBMITTED')
  await expect(section.getByTestId('video-use-waiting')).toBeVisible()
  await expectAccessible(page, 'sponsor publication page with its ledger')

  // 2. The provider, under the videos it shares, sends it back with a reason.
  const elsewhere = await browser.newContext({ baseURL: test.info().project.use.baseURL })
  const other = await elsewhere.newPage()
  await players(other)
  await signIn(other, '/sponsor/', provider.email)
  await expect(other.getByTestId('dashboard-status')).toBeVisible()
  await other.goto('/sponsor/assets')
  const provided = other.getByTestId('video-uses-provided')
  const item = provided.locator(`[data-testid="video-use-provided"][data-publication="${publicationId}"]`)
  await expect(item).toBeVisible()
  await expect(item.getByTestId('video-use-status')).toHaveAttribute('data-status', 'SUBMITTED')
  await expect(item.getByTestId('video-use-pricing')).toContainText('100')
  await expectAccessible(other, 'sponsor provider ledger')
  await item.getByTestId('video-use-reject').click()
  const reject = other.getByTestId('video-use-reject-dialog')
  await reject.getByTestId('video-use-reason-input').fill('We agreed 120 yen per Taker')
  await expectAccessible(other, 'sponsor send back dialog')
  await reject.getByTestId('video-use-reject-send').click()
  await expect(reject).toHaveCount(0)
  await expect(item.getByTestId('video-use-status')).toHaveAttribute('data-status', 'REJECTED')

  // 3. The user reads the reason, corrects the price and sends it again.
  await page.reload()
  await expect(section.getByTestId('video-use-reason')).toContainText('We agreed 120 yen per Taker')
  await section.getByTestId('video-use-edit').click()
  await expect(dialog.getByTestId('video-use-price')).toHaveValue('100')
  await dialog.getByTestId('video-use-price').fill('120')
  await dialog.getByTestId('video-use-save').click()
  await expect(dialog).toHaveCount(0)
  await expect(section.getByTestId('video-use-status')).toHaveAttribute('data-status', 'DRAFT')
  await section.getByTestId('video-use-submit').click()
  await expect(section.getByTestId('video-use-status')).toHaveAttribute('data-status', 'SUBMITTED')

  // 4. The provider confirms the revision it reads.
  await other.reload()
  await expect(item.getByTestId('video-use-status')).toHaveAttribute('data-status', 'SUBMITTED')
  await expect(item.getByTestId('video-use-pricing')).toContainText('120')
  await item.getByTestId('video-use-confirm').click()
  await other.getByRole('alertdialog').getByRole('button', { name: /confirm|确认|確認/i }).click()
  await expect(item.getByTestId('video-use-status')).toHaveAttribute('data-status', 'CONFIRMED')
  await expect(item.getByTestId('video-use-confirmed')).toContainText('5')
  // The provider opens the history: written, sent, sent back (with the reason), corrected, sent, confirmed.
  await item.getByTestId('video-use-history-toggle').click()
  const acts = ['CREATED', 'SUBMITTED', 'REJECTED', 'CORRECTED', 'SUBMITTED', 'CONFIRMED']
  await expect(item.getByTestId('video-use-history-entry')).toHaveCount(acts.length)
  expect(await item.getByTestId('video-use-history-entry').evaluateAll((all) =>
    all.map((e) => (e as HTMLElement).dataset.act))).toEqual(acts)
  await expect(item.getByTestId('video-use-history-reason')).toContainText('We agreed 120 yen per Taker')
  await expectAccessible(other, 'sponsor provider ledger history')

  // 5. The daily job counts the figures: both sides see them (fewer than the minimum group: nobody watched).
  await runJobNow(request, 'qb.video-use-figures')
  await page.reload()
  await expect(section.getByTestId('video-use-status')).toHaveAttribute('data-status', 'CONFIRMED')
  await expect(section.getByTestId('video-use-counted')).toBeVisible()
  await expect(section.getByTestId('video-use-figures-successes-value')).toContainText('5')
  await expect(section.getByTestId('video-use-amount')).not.toContainText('120 yen')
  // The user opens the same history.
  await section.getByTestId('video-use-history-toggle').click()
  await expect(section.getByTestId('video-use-history-entry')).toHaveCount(acts.length)
  await expect(section.getByTestId('video-use-history-reason')).toContainText('We agreed 120 yen per Taker')
  await other.reload()
  await expect(item.getByTestId('video-use-counted')).toBeVisible()
  await expect(item.getByTestId('video-use-provided-figures-goes-value')).toContainText('5')
  await expectAccessible(page, 'sponsor ledger with figures')
  await expectAccessible(other, 'sponsor provider ledger with figures')
  await elsewhere.close()

  // 6. The dark appearance of both sides.
  await setAppearance(page, 'dark')
  await page.goto(`/sponsor/publications/${publicationId}`)
  await expect(section.getByTestId('video-use-counted')).toBeVisible()
  await section.getByTestId('video-use-history-toggle').click()
  await expect(section.getByTestId('video-use-history-entry')).toHaveCount(acts.length)
  await expectAccessible(page, 'sponsor ledger with figures and history (dark)')
  const dark = await browser.newContext({ baseURL: test.info().project.use.baseURL })
  const darkPage = await dark.newPage()
  await setAppearance(darkPage, 'dark')
  await signIn(darkPage, '/sponsor/', provider.email)
  await expect(darkPage.getByTestId('dashboard-status')).toBeVisible()
  await darkPage.goto('/sponsor/assets')
  await expect(darkPage.getByTestId('video-uses-provided')
    .locator(`[data-testid="video-use-provided"][data-publication="${publicationId}"]`)
    .getByTestId('video-use-status')).toHaveAttribute('data-status', 'CONFIRMED')
  await expectAccessible(darkPage, 'sponsor provider ledger (dark)')
  await dark.close()
})
