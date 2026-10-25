import type { APIRequestContext, Page } from '@playwright/test'
import {
  adminToken,
  expect,
  expectAccessible,
  prepareQuizbuks,
  run,
  signIn,
  test,
  unique,
  setAppearance,
} from './support'
import { fundedSponsor, taker, untilLive } from './play'

/**
 * Answering in the app (docs/quizbuks/plans/QP-pages.md, QP2): the feed, a publication's page, one question per page
 * with the countdown, saving and continuing, submitting, the result with the reward paid or pending, and the history.
 * Until the sponsor console's pages exist (QP3), the publications are prepared through the API as a sponsor would
 * make them: an approved sponsor tops up through the Stripe stub, writes a quiz, saves a version, publishes it and an
 * administrator approves the publication. axe checks every page in both appearances.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

interface Seeded {
  publicationId: string
  name: string
  /** The sponsor's whole balance, which is also the publication's top reward. */
  reward: number
}

/**
 * A live publication of two questions (a point each; both right pass) open to every region, whose top reward is the
 * sponsor's whole balance: one taker's full marks use it up.
 */
async function seedPublication(request: APIRequestContext, timeLimitSec: number): Promise<Seeded> {
  const sponsor = await fundedSponsor(request)
  const quiz = await run(request, sponsor.token, 'QB_QUIZ_SAVE', { title: 'World capitals',
    intro: 'Two **easy** questions about capitals.', timeLimitSec })
  const quizId = quiz.quizId as string
  await run(request, sponsor.token, 'QB_MATERIAL_SAVE', { quizId, kind: 'ARTICLE', title: 'Read first',
    body: 'Paris is **the capital** of France.' })
  await run(request, sponsor.token, 'QB_MATERIAL_SAVE', { quizId, kind: 'VIDEO_LINK', title: 'A tour',
    url: 'https://video.example.com/tour' })
  await run(request, sponsor.token, 'QB_QUESTION_SAVE', { quizId, stem: 'Capital of France?', points: 1,
    options: [{ text: 'Paris', correct: true }, { text: 'Lyon' }] })
  await run(request, sponsor.token, 'QB_QUESTION_SAVE', { quizId, stem: 'Capital of Japan?', points: 1,
    options: [{ text: 'Tokyo', correct: true }, { text: 'Osaka' }] })
  const version = await run(request, sponsor.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  const name = unique('Capitals ')
  const now = Date.now()
  const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', {
    versionId: version.versionId, name, regions: ['GLOBAL'],
    startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
    timeLimitSec, passScore: 2,
    tiers: [{ fromScore: 0, toScore: 1, reward: 0 }, { fromScore: 2, toScore: 2, reward: sponsor.balance }],
  })
  const publicationId = saved.publicationId as string
  const submitted = await run(request, sponsor.token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  if (submitted.approval === 'PENDING') {
    await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId,
      decision: 'APPROVE' })
  }
  return { publicationId, name, reward: sponsor.balance }
}

/** Opens the publication from the feed, found by its name. */
async function openFromFeed(page: Page, name: string) {
  await page.getByTestId('feed-search').fill(name)
  await page.getByTestId('feed-search-submit').click()
  const card = page.getByTestId('feed-card').filter({ hasText: name })
  await expect(card).toBeVisible()
  await expect(card).toContainText('Kudos')
  await card.click()
  await expect(page.getByTestId('detail-name')).toHaveText(name)
}

/** Chooses an option of the current question by its text and waits until the answer is saved. */
async function choose(page: Page, text: string) {
  const saved = page.waitForResponse((r) => r.url().includes('/api/processes/QB_ATTEMPT_SAVE/') && r.ok())
  await page.getByRole('checkbox', { name: text }).click()
  await saved
}

for (const appearance of ['light', 'dark'] as const) {
  const at = (name: string) => (appearance === 'light' ? name : `${name} (dark)`)

  test(`a taker answers, continues after a reload, submits and waits for a pending reward (${appearance})`, async ({
    page,
    request,
  }) => {
    test.setTimeout(180_000)
    await setAppearance(page, appearance)
    await page.setViewportSize({ width: 390, height: 844 })
    const seeded = await seedPublication(request, 300)
    const me = await taker(request, 'Aki')
    const other = await taker(request, 'Ben')
    await untilLive(request, me.token, seeded.publicationId)

    await signIn(page, '/taker/', me.email)
    await expect(page.getByTestId('home')).toBeVisible()
    await openFromFeed(page, seeded.name)
    await expectAccessible(page, at('app feed'))
    // The materials under the title: Markdown and a link opening in a new tab; the tiers in Kudos.
    await expect(page.getByTestId('materials').locator('strong')).toHaveText('the capital')
    await expect(page.getByTestId('material-link')).toHaveAttribute('target', '_blank')
    await expect(page.getByTestId('detail-tiers')).toContainText(`${seeded.reward.toLocaleString('en-US')} Kudos`)
    await expect(page.getByTestId('detail-tiers')).not.toContainText('JPY')
    await expectAccessible(page, at('app publication'))

    // The start answers the questions and never which options are correct.
    const started = page.waitForResponse((r) => r.url().includes('/api/processes/QB_ATTEMPT_START/'))
    await page.getByTestId('detail-start').click()
    const startBody = await (await started).text()
    expect(startBody).toContain('Capital of France?')
    expect(startBody).not.toContain('"correct"')
    await expect(page).toHaveURL(/\/play\/[^/]+$/)
    const attemptUrl = page.url()
    await expect(page.getByTestId('play-progress')).toHaveText('Question 1 of 2')
    await expect(page.getByTestId('play-timer')).toHaveText(/^[45]:\d\d$/)
    await choose(page, 'Paris')
    await expectAccessible(page, at('app play'))

    // A reload continues at the same question with the answer saved.
    await page.reload()
    await expect(page.getByTestId('play-progress')).toHaveText('Question 1 of 2')
    await expect(page.getByRole('checkbox', { name: 'Paris' })).toBeChecked()
    const moved = page.waitForResponse((r) => r.url().includes('/api/processes/QB_ATTEMPT_SAVE/') && r.ok())
    await page.getByTestId('play-next').click()
    await moved
    await expect(page.getByTestId('play-question')).toContainText('Capital of Japan?')
    await expect(page.getByTestId('play-question')).toBeFocused()
    await choose(page, 'Tokyo')
    await page.reload()
    await expect(page.getByTestId('play-progress')).toHaveText('Question 2 of 2')
    await expect(page.getByRole('checkbox', { name: 'Tokyo' })).toBeChecked()

    // Meanwhile another taker earns the whole budget: this taker's reward cannot be paid yet.
    const theirs = await run(request, other.token, 'QB_ATTEMPT_START', { publicationId: seeded.publicationId })
    const questions = theirs.questions as { no: number; options: { no: number; text: string }[] }[]
    const right = (q: { options: { no: number; text: string }[] }) =>
      q.options.filter((o) => o.text === 'Paris' || o.text === 'Tokyo').map((o) => o.no)
    const paid = await run(request, other.token, 'QB_ATTEMPT_SUBMIT', { attemptId: theirs.attemptId,
      publicationId: seeded.publicationId, answers: questions.map((q) => ({ questionNo: q.no, selected: right(q) })) })
    expect(paid.rewardStatus).toBe('PAID')

    await page.getByTestId('play-submit').click()
    const dialog = page.getByRole('alertdialog')
    await expect(dialog).toContainText('Submit your answers?')
    await expectAccessible(page, at('app submit confirmation'))
    await dialog.getByRole('button', { name: 'Submit answers' }).click()
    await expect(page).toHaveURL(/\/result\/[^/]+$/)
    await expect(page.getByTestId('result-score')).toHaveText('2 / 2')
    await expect(page.getByTestId('result-passed')).toHaveText('Passed')
    await expect(page.getByTestId('result-reward')).toHaveAttribute('data-status', 'PENDING')
    await expect(page.getByTestId('result-reward')).toContainText(`${seeded.reward.toLocaleString('en-US')} Kudos`)
    await expect(page.getByTestId('result-question')).toHaveCount(2)
    await expect(page.getByTestId('result-correct').first()).toContainText('Paris')
    await expectAccessible(page, at('app result'))

    // The attempt is over: its play page leads to the result, and the publication offers no second try.
    await page.goto(attemptUrl)
    await expect(page).toHaveURL(/\/result\/[^/]+$/)
    await page.getByTestId('nav-history').click()
    const row = page.getByTestId('history-row').filter({ hasText: seeded.name })
    await expect(row).toHaveAttribute('data-category', 'PASSED')
    await expect(row).toContainText('Pending')
    await expectAccessible(page, at('app history'))
    // Q20: the result read later shows the questions and the answers, from the server (this tab kept nothing).
    await page.evaluate(() => window.sessionStorage.clear())
    await row.locator('a[href^="/taker/result/"]').first().click()
    await expect(page).toHaveURL(/\/result\/[^/]+$/)
    await expect(page.getByTestId('result-question')).toHaveCount(2)
    await expect(page.getByTestId('result-mine').first()).toContainText('Paris')
    await expect(page.getByTestId('result-correct').first()).toContainText('Paris')
    await page.goto(`/taker/p/${seeded.publicationId}`)
    await expect(page.getByTestId('detail-answered')).toBeVisible()
    await expect(page.getByTestId('detail-start')).toHaveCount(0)
  })
}

test('the time runs out: the answers are submitted by themselves, after continuing from the history', async ({
  page,
  request,
}) => {
  test.setTimeout(180_000)
  await page.setViewportSize({ width: 390, height: 844 })
  // The shortest time limit a publication may have.
  const seeded = await seedPublication(request, 30)
  const me = await taker(request, 'Chie')
  await untilLive(request, me.token, seeded.publicationId)

  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()
  await openFromFeed(page, seeded.name)
  await page.getByTestId('detail-start').click()
  await expect(page.getByTestId('play-progress')).toHaveText('Question 1 of 2')
  await choose(page, 'Lyon')

  // The history continues it at the question and answer saved.
  await page.goto('/taker/history')
  const row = page.getByTestId('history-row').filter({ hasText: seeded.name })
  await expect(row).toHaveAttribute('data-category', 'IN_PROGRESS')
  await row.getByTestId('history-continue').click()
  await expect(page).toHaveURL(/\/play\/[^/]+$/)
  await expect(page.getByRole('checkbox', { name: 'Lyon' })).toBeChecked()

  // Nothing else is done: at 0:00 the page submits what was saved and says the time ran out.
  await expect(page).toHaveURL(/\/result\/[^/]+$/, { timeout: 45_000 })
  await expect(page.getByTestId('result-automatic')).toBeVisible()
  await expect(page.getByTestId('result-score')).toHaveText('0 / 2')
  await expect(page.getByTestId('result-reward')).toHaveAttribute('data-status', 'NONE')
  await expectAccessible(page, 'app result after the time ran out')
})
