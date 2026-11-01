import { readFile } from 'node:fs/promises'
import { expect, expectAccessible, prepareQuizbuks, rows, run, signIn, test, unique } from './support'
import { png, sponsor, upload } from './content'

/**
 * Quiz files (docs/quizbuks/plans/Q16-quiz-transfer.md): a sponsor exports a quiz with its cover and a question's image
 * from the quiz list, imports the downloaded file again, and gets a new draft with the same content and new files.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('a quiz is exported to a file and imported back as a new draft', async ({ page, request }) => {
  test.setTimeout(180_000)
  const me = await sponsor(request, 'Toru')
  const title = unique('Exported ')
  const cover = await upload(request, me.token, 'qb.content.image', 'cover.png', 'image/png', png(32))
  const picture = await upload(request, me.token, 'qb.content.image', 'q.png', 'image/png', png(24, [10, 200, 90]))
  const quiz = await run(request, me.token, 'QB_QUIZ_SAVE', { title, intro: 'From **a file**.', cover,
    timeLimitSec: 240 })
  const quizId = quiz.quizId as string
  await run(request, me.token, 'QB_QUESTION_SAVE', { quizId, stem: 'Which colour?', image: picture, points: 2,
    options: [{ text: 'Green', correct: true }, { text: 'Red' }] })

  await signIn(page, '/sponsor/', me.email)
  // Signed in once the console leads to the onboarding (not approved yet; the quizzes are open).
  await expect(page).toHaveURL(/\/sponsor\/onboarding$/)
  await page.getByRole('link', { name: 'Quizzes' }).click()
  await expect(page.getByTestId('quiz-list')).toBeVisible()
  // Other tests' quizzes share the database: find this one by its title.
  await page.getByTestId('quiz-search').fill(title)
  const row = page.locator(`[data-testid="quiz-row"][data-quiz="${quizId}"]`)
  await expect(row).toBeVisible()
  const downloaded = page.waitForEvent('download')
  await row.getByTestId('quiz-export').click()
  const file = await downloaded
  expect(file.suggestedFilename()).toMatch(/\.quizbuks\.json$/)
  const path = await file.path()
  const document = JSON.parse(await readFile(path, 'utf8'))
  expect(document).toMatchObject({ format: 'quizbuks.quiz', formatVersion: 2, quiz: { title, cover: 'f1' } })
  expect(Object.keys(document.files)).toEqual(['f1', 'f2'])

  await page.getByTestId('quiz-import-file').setInputFiles(path)
  await expect(page.getByTestId('quiz-import-summary')).toContainText(`${title} — questions: 1`)
  await expectAccessible(page, 'quiz import')
  await page.getByTestId('quiz-import-confirm').click()
  await expect(page).not.toHaveURL(new RegExp(`/quizzes/${quizId}$`))
  await expect(page).toHaveURL(/\/sponsor\/quizzes\/[0-9a-f-]{36}$/)
  const importedId = new URL(page.url()).pathname.split('/').pop()!
  expect(importedId).not.toBe(quizId)

  // The same content, with files of its own.
  // Read by its key: the quiz dataset is not filtered by key.
  const read = await request.get(`/api/datasets/${encodeURIComponent('urn:jabiz:dataset:sponsor:QbQuiz')}`
    + `/entities/${importedId}`, { headers: { Authorization: `Bearer ${me.token}` } })
  expect(read.status(), await read.text()).toBe(200)
  const copy = (await read.json()) as { attributes: Record<string, unknown> }
  expect(copy.attributes).toMatchObject({ title, intro: 'From **a file**.', timeLimitSec: 240, status: 'DRAFT' })
  expect(copy.attributes.cover).toBeTruthy()
  expect(copy.attributes.cover).not.toBe(cover)
  const questions = await rows(request, me.token, 'urn:jabiz:dataset:sponsor:QbQuestion',
    [{ field: 'quizId', op: 'eq', value: importedId }])
  expect(questions.map((q) => q.attributes)).toMatchObject([{ stem: 'Which colour?', points: 2 }])
  expect(questions[0].attributes.image).not.toBe(picture)
})
