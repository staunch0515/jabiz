import { query, sponsor } from './content'
import { decideReview } from './publishing'
import { expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique } from './support'
import { fundedSponsor, takerInJapan } from './wallet'

/**
 * F-S6b (docs/quizbuks/plans/impl/F-S6.md §3.5): the editor of a large question bank against the packaged server: a
 * quiz file's questions imported in two batches (`QB_QUIZ_IMPORT_QUESTIONS`), one batch undone, the list searched a
 * page at a time, one question moved (`QB_QUESTION_MOVE`) and several given the same points at once
 * (`QB_QUESTION_POINTS_SET`). F-S6c: the translations tab of a large bank, a page at a time. F-S7: difficulties set
 * in bulk in the editor and a publication drawing one question of each, answered by a Taker.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** A quiz file (Q16's format) of `count` questions only, in English. */
function quizFile(count: number) {
  return {
    format: 'quizbuks.quiz', formatVersion: 3, exportedAt: new Date().toISOString(),
    quiz: { kind: 'QUIZ', title: 'Bank', intro: null, timeLimitSec: null, cover: null, language: 'en' },
    materials: [],
    questions: Array.from({ length: count }, (_, i) => ({ stem: `Bank Q${i + 1}`, image: null, points: 1,
      selection: null, options: [{ text: 'Right', image: null, correct: true },
        { text: 'Wrong', image: null, correct: false }] })),
    files: {},
  }
}

test('a Sponsor imports a large bank in batches, undoes one, searches, moves and sets points', async ({ page,
  request }) => {
  const { email, token } = await sponsor(request, 'Bianca')
  const quiz = await run(request, token, 'QB_QUIZ_SAVE', { title: unique('Bank '), intro: null, cover: null,
    timeLimitSec: null, language: 'en' })
  const quizId = quiz.quizId as string
  // One question more than a batch holds, and two more: two batches.
  const [limits] = await query(request, token, 'qb.sponsor.quiz-limits', {})
  const batch = Number(limits.importBatch)
  const total = batch + 3

  await signIn(page, '/sponsor/', email)
  // Signed in before going on: signIn only submits the form (a sponsor not yet approved lands on the profile).
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(`/sponsor/quizzes/${quizId}`)
  await page.getByTestId('tab-questions').click()
  await expect(page.getByTestId('questions-empty')).toBeVisible()
  await expect(page.getByTestId('questions-draw-hint')).toContainText(String(limits.maxPerAttempt))

  await page.getByTestId('bank-import-file').setInputFiles({ name: 'bank.quizbuks.json',
    mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(quizFile(total))) })
  const dialog = page.getByTestId('bank-import-dialog')
  await expect(dialog.getByTestId('bank-import-summary')).toContainText(`${total} questions in 2 batches`)
  await dialog.getByTestId('bank-import-confirm').click()
  await expect(dialog.getByTestId('bank-import-done')).toContainText(`${total} questions imported in 2 batches`,
    { timeout: 60_000 })
  await dialog.locator('[data-slot="button"]', { hasText: 'Close' }).click()
  await expect(page.getByTestId('questions-count')).toContainText(`${total} /`)
  await expect(page.getByTestId('questions-page')).toContainText('Page 1 of')

  // The newest batch (3 questions) undone: all removed, none changed.
  const batches = page.getByTestId('bank-batch')
  await expect(batches).toHaveCount(2)
  await batches.first().getByTestId('bank-batch-undo').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Undo' }).click()
  await expect(page.getByTestId('bank-undo-result')).toContainText('3 questions removed; 0 kept')
  await expect(page.getByTestId('questions-count')).toContainText(`${batch} /`)
  await expect(batches.first().getByTestId('bank-batch-undone')).toBeVisible()
  await expectAccessible(page, 'question bank')

  // Searched by text; the question found keeps its number, and moves up one place.
  await page.getByTestId('questions-search').fill('Bank Q150')
  await page.getByTestId('questions-search-submit').click()
  await expect(page.getByTestId('questions-found')).toContainText('1 question found')
  const found = page.getByTestId('question')
  await expect(found).toHaveAttribute('data-no', '150')
  await found.getByRole('button', { name: 'Move question 150 up' }).click()
  await expect(found).toHaveAttribute('data-no', '149')
  const moved = await query(request, token, 'qb.sponsor.quiz-questions', { quizId, q: 'Bank Q150' })
  expect(Number(moved[0].questionNo)).toBe(149)

  // Searched by number, then the first page's questions given 3 points at once.
  await page.getByTestId('questions-search').fill('7')
  await page.getByTestId('questions-search-submit').click()
  await expect(page.getByTestId('question')).toHaveCount(1)
  await expect(page.getByTestId('question').getByTestId('question-first-line')).toHaveText('Bank Q7')
  await page.getByTestId('questions-search-clear').click()
  await expect(page.getByTestId('question')).toHaveCount(50)
  await page.getByRole('checkbox', { name: 'Select the questions on this page' }).click()
  await expect(page.getByTestId('bank-selected')).toHaveText('50 selected')
  await page.getByTestId('bank-points').click()
  await page.getByTestId('bank-points-input').fill('3')
  await page.getByTestId('bank-points-save').click()
  await expect(page.getByTestId('bank-selection')).toBeHidden()
  await expect(page.getByTestId('question').first().getByTestId('question-badge')).toContainText('Points: 3')
  const rows = await query(request, token, 'qb.sponsor.quiz-questions', { quizId })
  expect(rows.slice(0, 50).every((row) => Number(row.points) === 3)).toBe(true)
  expect(rows.slice(50).every((row) => Number(row.points) === 1)).toBe(true)
})

test('a Sponsor translates a large bank a page at a time (F-S6c)', async ({ page, request }) => {
  const { email, token } = await sponsor(request, 'Bruno')
  const quiz = await run(request, token, 'QB_QUIZ_SAVE', { title: unique('Translated bank '), intro: null,
    cover: null, timeLimitSec: null, language: 'en' })
  const quizId = quiz.quizId as string
  // More than a page (50) of questions, and a language to translate them into.
  await run(request, token, 'QB_QUIZ_IMPORT_QUESTIONS', { quizId, document: quizFile(60), files: {} })
  await run(request, token, 'QB_QUIZ_LANGUAGES_SET', { quizId, languages: ['ja'] })

  await signIn(page, '/sponsor/', email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(`/sponsor/quizzes/${quizId}`)
  await page.getByTestId('tab-languages').click()
  const questions = page.getByTestId('translation-questions')
  await expect(questions.getByTestId('translations-page')).toHaveText('Page 1 of 2')
  // Each question with its two options.
  await expect(questions.getByTestId('translation-text')).toHaveCount(150)
  await questions.getByTestId('translations-next').click()
  await expect(questions.getByTestId('translations-page')).toHaveText('Page 2 of 2')
  const row = questions.getByTestId('translation-text').first()
  await expect(row).toContainText('Question 51')
  await expect(row).toHaveAttribute('data-state', 'missing')
  await expectAccessible(page, 'translations page 2')

  await row.getByTestId('translation-input').fill('バンク 問 51')
  await questions.getByTestId('translation-page-save').click()
  // Saved, and read back from the server as done; the page stays on page 2.
  await expect(questions.getByTestId('translation-text').first()).toHaveAttribute('data-state', 'done')
  await expect(questions.getByTestId('translation-text').first().getByTestId('translation-input'))
    .toHaveValue('バンク 問 51')
  await expect(questions.getByTestId('translations-page')).toHaveText('Page 2 of 2')
  const [saved] = await query(request, token, 'qb.sponsor.quiz-translations', { quizId, language: 'ja',
    q: 'Bank Q51' })
  expect(saved.stemText).toBe('バンク 問 51')
  expect(saved.stemState).toBe('done')

  // Only what needs doing: the question translated has its options still to do; question 1 has everything.
  await page.getByTestId('translation-only-pending').click()
  await expect(questions.getByTestId('translations-page')).toHaveText('Page 1 of 2')
})

/** F-S6d: the most API requests opening the editor on its questions may make, whatever the size of the bank. */
const EDITOR_REQUESTS_BOUND = 25

test('opening the editor costs the same requests for 1000 questions as for 3 (F-S6d)', async ({ page, request }) => {
  test.setTimeout(240_000)
  const { email, token } = await sponsor(request, 'Bea')
  const [limits] = await query(request, token, 'qb.sponsor.quiz-limits', {})
  const batch = Number(limits.importBatch)
  const quizOf = async (count: number) => {
    const quiz = await run(request, token, 'QB_QUIZ_SAVE', { title: unique(`Size ${count} `), intro: null,
      cover: null, timeLimitSec: null, language: 'en' })
    const quizId = quiz.quizId as string
    for (let done = 0; done < count; done += batch) {
      await run(request, token, 'QB_QUIZ_IMPORT_QUESTIONS', { quizId, document: quizFile(Math.min(batch,
        count - done)), files: {} })
    }
    return quizId
  }
  const small = await quizOf(3)
  const large = await quizOf(1000)

  await signIn(page, '/sponsor/', email)
  await expect(page).not.toHaveURL(/\/login/)
  // The landing page's own requests finish before counting starts.
  await page.waitForLoadState('networkidle')
  /** The `/api/` requests from opening the editor until its questions tab has rendered and the page is idle. */
  const opened = async (quizId: string, count: number) => {
    const requests: string[] = []
    const record = (r: { url: () => string }) => {
      const url = new URL(r.url())
      if (url.pathname.startsWith('/api/')) requests.push(url.pathname)
    }
    page.on('request', record)
    await page.goto(`/sponsor/quizzes/${quizId}`)
    await page.getByTestId('tab-questions').click()
    await expect(page.getByTestId('questions-count')).toContainText(`${count} /`)
    await expect(page.getByTestId('questions-score')).toBeVisible()
    await expect(page.getByTestId('question').first()).toBeVisible()
    await page.waitForLoadState('networkidle')
    page.off('request', record)
    return requests
  }
  const few = await opened(small, 3)
  const many = await opened(large, 1000)
  console.log(`F-S6d: opening the editor: ${few.length} requests for 3 questions, ${many.length} for 1000`)
  expect(many.length).toBeLessThanOrEqual(EDITOR_REQUESTS_BOUND)
  expect(many.length).toBe(few.length)
  expect([...many].sort()).toEqual([...few].sort().map((path) => path.replace(small, large)))
  await expect(page.getByTestId('questions-page')).toContainText('Page 1 of 20')
})

test('a Sponsor sets difficulties in bulk and a publication draws one of each for a Taker (F-S7)', async ({ page,
  request }) => {
  const funded = await fundedSponsor(request, 1000, 'Dita')
  const quiz = await run(request, funded.token, 'QB_QUIZ_SAVE', { title: unique('Levels '), intro: null, cover: null,
    timeLimitSec: 300, language: 'en' })
  const quizId = quiz.quizId as string
  for (const stem of ['Easy A?', 'Easy B?', 'Hard A?', 'Hard B?']) {
    await run(request, funded.token, 'QB_QUESTION_SAVE', { quizId, stem, points: 1, options: [
      { text: `Right ${stem}`, correct: true }, { text: `Wrong ${stem}`, correct: false }] })
  }

  await signIn(page, '/sponsor/', funded.email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(`/sponsor/quizzes/${quizId}`)
  // A new quiz shuffles its questions (A2-14).
  await expect(page.getByRole('switch', { name: 'Shuffle the questions' })).toBeChecked()
  await page.getByTestId('tab-questions').click()
  await expect(page.getByTestId('question')).toHaveCount(4)
  /** Selects the questions of these numbers and gives them the difficulty, from the bulk actions. */
  const setLevel = async (numbers: number[], level: string) => {
    for (const n of numbers) await page.getByRole('checkbox', { name: `Select question ${n}` }).click()
    await page.getByTestId('bank-difficulty').click()
    const dialog = page.getByTestId('bank-difficulty-dialog')
    await dialog.getByRole('combobox', { name: 'Difficulty' }).click()
    await page.getByRole('option', { name: level }).click()
    await dialog.getByTestId('bank-difficulty-save').click()
    await expect(page.getByTestId('bank-selection')).toBeHidden()
  }
  await setLevel([1, 2], 'Easy')
  await setLevel([3, 4], 'Hard')
  await expect(page.getByTestId('question').first().getByTestId('question-difficulty-badge')).toHaveText('Easy')
  // The list filtered by difficulty: none is left without one.
  await page.getByRole('combobox', { name: 'Difficulty' }).click()
  await page.getByRole('option', { name: 'Not set' }).click()
  await expect(page.getByTestId('questions-no-match')).toBeVisible()
  await expectAccessible(page, 'question difficulties')

  await run(request, funded.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  const [version] = await query(request, funded.token, 'qb.sponsor.quiz-versions', { quizId })
  expect(Number(version.easyCount)).toBe(2)
  expect(Number(version.hardCount)).toBe(2)
  const now = Date.now()
  const saved = await run(request, funded.token, 'QB_PUBLICATION_SAVE', { versionId: version.versionId,
    name: unique('One of each '), regions: ['GLOBAL'], startAt: new Date(now - 60_000).toISOString(),
    endAt: new Date(now + 7 * 86_400_000).toISOString(), passScore: 2, pickByDifficulty: { EASY: 1, HARD: 1 },
    budgetCap: 500, maxWinners: 5, tiers: [{ fromScore: 0, toScore: 1, reward: 0 },
      { fromScore: 2, toScore: 2, reward: 10 }] })
  expect(saved.problems).toEqual([])
  expect(saved.fullScore).toBe(2)
  const publicationId = saved.publicationId as string
  await run(request, funded.token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  await decideReview(request, funded.token, publicationId, 'APPROVE')
  await page.goto(`/sponsor/publications/${publicationId}`)
  await expect(page.getByTestId('summary-by-difficulty')).toContainText('Easy 1 · Hard 1')
  await expect(page.getByTestId('summary-question-order')).toContainText('Shuffled for each Taker')

  // The Taker gets one easy and one hard question, numbered 1 and 2, and full marks pay the top tier.
  const taker = await takerInJapan(request, 'Levi')
  const started = await run(request, taker.token, 'QB_ATTEMPT_START', { publicationId })
  const questions = started.questions as { no: number; stem: string; options: { no: number; text: string }[] }[]
  expect(questions.map((q) => q.no)).toEqual([1, 2])
  expect(questions.filter((q) => q.stem.startsWith('Easy'))).toHaveLength(1)
  expect(questions.filter((q) => q.stem.startsWith('Hard'))).toHaveLength(1)
  const result = await run(request, taker.token, 'QB_ATTEMPT_SUBMIT', { attemptId: started.attemptId, publicationId,
    answers: questions.map((q) => ({ questionNo: q.no,
      selected: q.options.filter((o) => o.text.startsWith('Right')).map((o) => o.no) })) })
  expect(result.score).toBe(2)
  expect(result.rewardStatus).toBe('PAID')
})
