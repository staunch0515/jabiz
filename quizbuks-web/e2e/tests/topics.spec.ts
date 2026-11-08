import type { APIRequestContext, Page } from '@playwright/test'
import { signInAdmin } from './admin'
import { completeQuiz } from './content'
import { contentAdmin } from './legal'
import { fundedSponsor, searchHome, taker, untilLive } from './play'
import { decideReview, livePublication, publicationOf } from './publishing'
import {
  expect,
  expectAccessible,
  PASSWORD,
  prepareQuizbuks,
  run,
  setAppearance,
  signIn,
  test,
  unique,
} from './support'

/*
 * F-S9 (docs/quizbuks/plans/impl/F-S9.md): headquarters add words to the word list of topics on the administration's
 * page (through the platform's process page it links to); a sponsor gives a quiz words in its editor, saves a version
 * and, in the publication wizard, adds a word of the list to the version's; a taker likes a word in Account → About me
 * and turns strict mode on: the home lists only the publications sharing a liked word of the list (a publication
 * without words, or with only own words, even one spelled as the liked word's name, is left out), says so with a link
 * to change it, and lists everything again once strict mode is off. The search narrows the home to this run's
 * publications.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/**
 * A code no earlier run used that sorts before every earlier run's: the lists of words (the administration's page, the
 * app's and the console's reads of at most 500) are by code, and the tables only grow.
 */
function newCode(): string {
  const countdown = (36 ** 9 - Date.now()).toString(36).padStart(9, '0')
  return `0e2e-${countdown}${Math.floor(Math.random() * 36 ** 2).toString(36).padStart(2, '0')}`
}

/** Adds a word on the platform's process page the topics page links to; back on the topics page, it is listed. */
async function addWord(page: Page, code: string, english: string) {
  await page.goto('/admin/qb/topics')
  await expect(page.getByTestId('qb-topics')).toBeVisible()
  await page.getByTestId('topic-add').click()
  await page.getByLabel(/^topicId/).fill(code)
  await page.getByLabel(/^nameZh/).fill(`主题 ${english}`)
  await page.getByLabel(/^nameJa/).fill(`トピック ${english}`)
  await page.getByLabel(/^nameEn/).fill(english)
  await page.getByTestId('process-run').click()
  await expect(page.getByTestId('process-result')).toContainText(code)
  await page.goto('/admin/qb/topics')
  const row = page.locator(`[data-testid="topic-row"][data-id="${code}"]`)
  await expect(row).toBeVisible()
  await expect(row.getByTestId('topic-name-en')).toHaveText(english)
  await expect(row.getByTestId('topic-name-ja')).toHaveText(`トピック ${english}`)
  await expect(row.getByTestId('topic-state')).toContainText('Offered')
  return row
}

/** Picks a word of the list by its English name in a topics field's combobox (`<testId>-word`). */
async function pickWord(page: Page, testId: string, english: string) {
  await page.getByTestId(`${testId}-word`).click()
  await page.getByPlaceholder('Search topics').fill(english)
  await page.getByRole('option', { name: english, exact: true }).click()
}

/** The cards the home lists for this run's search. */
async function homeCards(page: Page, base: string) {
  await page.goto('/taker/')
  await expect(page.getByTestId('home')).toBeVisible()
  await searchHome(page, base)
  return page.getByTestId('feed-card')
}

/** A live publication of a quiz whose only topic is an own word (for search), made through the API. */
async function ownWordOnly(request: APIRequestContext, token: string, name: string, word: string) {
  const quiz = await completeQuiz(request, token, { title: unique('Own words quiz ') })
  await run(request, token, 'QB_QUIZ_TOPICS_SET', { quizId: quiz.quizId, topics: [], customTopics: [word] })
  await run(request, token, 'QB_QUIZ_PUBLISH_VERSION', { quizId: quiz.quizId })
  return livePublication(request, token, quiz.quizId, name)
}

test('headquarters add topics, a sponsor uses them, and strict mode keeps only the liked ones on the home', async ({
  page, request }) => {
  test.setTimeout(300_000)
  const suffix = unique('')
  const first = { code: newCode(), english: `Tea ${suffix}` }
  const second = { code: newCode(), english: `Kites ${suffix}` }

  // 1. Headquarters: two new words, listed with their names and no figures yet.
  await signInAdmin(page, await contentAdmin(request, 'Toma'), PASSWORD)
  await addWord(page, first.code, first.english)
  const row = await addWord(page, second.code, second.english)
  await expect(row.getByTestId('topic-publications')).toContainText('0')
  await expectAccessible(page, 'admin topics')

  // 2. The sponsor: the quiz's own topics in its editor (a word of the list and an own word), then a version.
  const sponsor = await fundedSponsor(request)
  const base = unique('Topics run ')
  const quiz = await completeQuiz(request, sponsor.token, { title: unique('Topical quiz ') })
  await signIn(page, '/sponsor/', sponsor.email)
  await expect(page.getByTestId('dashboard-status')).toBeVisible()
  await page.goto(`/sponsor/quizzes/${quiz.quizId}`)
  const quizTopics = page.getByTestId('quiz-topics')
  await expect(quizTopics).toBeVisible()
  await pickWord(page, 'quiz-topics-add', first.english)
  await expect(quizTopics.getByTestId(`quiz-topic-${first.code}`)).toContainText(first.english)
  await page.getByTestId('quiz-topics-add-custom').fill('  homemade   lemonade ')
  await page.getByTestId('quiz-topics-add-custom-add').click()
  await expect(quizTopics.getByTestId('quiz-custom-0')).toContainText('homemade lemonade')
  await expect(page.getByTestId('quiz-topics-count')).toContainText('2')
  await expectAccessible(page, 'console quiz topics')
  await page.getByTestId('save-version').click()
  await expect(page.getByTestId('version-saved')).toBeVisible()

  // The wizard: a draft of the new version shows its words; the sponsor adds the second word of the list.
  const matching = `${base} match`
  const now = Date.now()
  const versionId = (await (await request.post('/api/queries/qb.sponsor.quiz-versions', {
    headers: { Authorization: `Bearer ${sponsor.token}` }, data: { params: { quizId: quiz.quizId }, limit: 1 },
  })).json()).items[0].versionId as string
  const draft = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', { versionId, name: matching,
    regions: ['GLOBAL'], startAt: new Date(now - 60_000).toISOString(),
    endAt: new Date(now + 7 * 86_400_000).toISOString(), passScore: 1, budgetCap: 500, maxWinners: 5,
    tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 2, reward: 20 }] })
  const matchingId = draft.publicationId as string
  await page.goto(`/sponsor/publications/${matchingId}`)
  const wizard = page.getByTestId('publication-wizard')
  await expect(wizard).toHaveAttribute('data-step', '0')
  const field = page.getByTestId('publication-topics')
  await expect(field.getByTestId(`publication-topic-${first.code}`)).toContainText(first.english)
  await expect(field.getByTestId('publication-version-custom-0')).toContainText('homemade lemonade')
  await pickWord(page, 'publication-topics-add', second.english)
  await expect(field.getByTestId(`publication-topic-${second.code}`)).toContainText(second.english)
  await expect(page.getByTestId('publication-topics-count')).toContainText('3')
  await expectAccessible(page, 'console publication topics')
  for (const step of ['1', '2', '3']) {
    await page.getByTestId('publication-next').click()
    await expect(wizard).toHaveAttribute('data-step', step)
  }
  // Saved with the draft (the column holds the changes' JSON).
  const changes = (await publicationOf(request, sponsor.token, matchingId)).topicChanges
  expect(JSON.parse(String(changes))).toMatchObject({ add: [second.code] })
  await expect(page.getByTestId('publication-ready')).toBeVisible()
  await page.getByTestId('publication-submit').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Submit' }).click()
  await expect(page.getByTestId('publication-view')).toHaveAttribute('data-status', 'PENDING_REVIEW')
  await decideReview(request, sponsor.token, matchingId, 'APPROVE')
  await expect.poll(async () => (await publicationOf(request, sponsor.token, matchingId)).status,
    { message: 'the publication is live', timeout: 30_000 }).toBe('LIVE')

  // Two more: one without topics, one whose only topic is an own word spelled as the liked word's name.
  const plainQuiz = await completeQuiz(request, sponsor.token, { title: unique('Plain quiz ') })
  const plainId = await livePublication(request, sponsor.token, plainQuiz.quizId, `${base} plain`)
  const ownId = await ownWordOnly(request, sponsor.token, `${base} own`, second.english)

  // 3. The taker: everything at first; then the second word liked and strict mode on in About me.
  const me = await taker(request, 'Kaito')
  for (const id of [matchingId, plainId, ownId]) await untilLive(request, me.token, id)
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()
  let cards = await homeCards(page, base)
  await expect(cards).toHaveCount(3)
  await expect(page.getByTestId('home-strict-topics')).toHaveCount(0)

  await page.goto('/taker/account/details')
  const section = page.getByTestId('account-topics')
  await expect(section).toBeVisible()
  const strict = section.getByTestId('strict-topics-switch')
  // Strict mode needs a liked topic first.
  await expect(strict).toBeDisabled()
  await expect(section.getByTestId('strict-topics-needs')).toBeVisible()
  const liked = section.getByTestId(`liked-topic-${second.code}`)
  await liked.click()
  await expect(liked).toBeChecked()
  await expect(strict).toBeEnabled()
  await strict.click()
  await expect(strict).toBeChecked()
  await expectAccessible(page, 'app liked topics')

  // The home: only the publication sharing the liked word, with the notice and its link back.
  cards = await homeCards(page, base)
  await expect(cards).toHaveCount(1)
  await expect(cards.first()).toContainText(matching)
  const notice = page.getByTestId('home-strict-topics')
  await expect(notice).toContainText('Showing only the topics you like.')
  await expectAccessible(page, 'app home in strict mode')

  // Dark: the same two states.
  await setAppearance(page, 'dark')
  await page.goto('/taker/account/details')
  await expect(page.getByTestId('account-topics').getByTestId('strict-topics-switch')).toBeChecked()
  await expectAccessible(page, 'app liked topics (dark)')
  cards = await homeCards(page, base)
  await expect(cards).toHaveCount(1)
  await expectAccessible(page, 'app home in strict mode (dark)')

  // "Change" leads to the setting; strict mode off, the home lists everything again.
  await page.getByTestId('home-strict-topics-change').click()
  await expect(page).toHaveURL(/\/taker\/account\/details$/)
  const again = page.getByTestId('account-topics').getByTestId('strict-topics-switch')
  await again.click()
  await expect(again).not.toBeChecked()
  cards = await homeCards(page, base)
  await expect(cards).toHaveCount(3)
  await expect(page.getByTestId('home-strict-topics')).toHaveCount(0)
})
