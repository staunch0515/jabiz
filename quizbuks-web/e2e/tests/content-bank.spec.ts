import { query, sponsor } from './content'
import { expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique } from './support'

/**
 * F-S6b (docs/quizbuks/plans/impl/F-S6.md §3.5): the editor of a large question bank against the packaged server: a
 * quiz file's questions imported in two batches (`QB_QUIZ_IMPORT_QUESTIONS`), one batch undone, the list searched a
 * page at a time, one question moved (`QB_QUESTION_MOVE`) and several given the same points at once
 * (`QB_QUESTION_POINTS_SET`).
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
  await dialog.getByRole('button', { name: 'Close' }).click()
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
