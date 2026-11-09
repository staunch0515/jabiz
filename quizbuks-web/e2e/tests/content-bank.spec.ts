import { query, sponsor } from './content'
import { expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique } from './support'

/**
 * F-S6b (docs/quizbuks/plans/impl/F-S6.md §3.5): the editor of a large question bank against the packaged server: a
 * quiz file's questions imported in two batches (`QB_QUIZ_IMPORT_QUESTIONS`), one batch undone, the list searched a
 * page at a time, one question moved (`QB_QUESTION_MOVE`) and several given the same points at once
 * (`QB_QUESTION_POINTS_SET`). F-S6c: the translations tab of a large bank, a page at a time.
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
