import type { Locator, Page } from '@playwright/test'
import { PDF, png, PNG, POLICY, query, sponsor, upload } from './content'
import { expect, prepareQuizbuks, run, signIn, test, unique } from './support'

/**
 * The sponsor console's content workshop (docs/quizbuks/plans/QP-pages.md, QP3a; M-20 – M-24, M-27, M-28) against the
 * packaged server: a quiz made, edited with images and materials, reordered, saved as versions, viewed, copied and
 * deleted; an edit of a quiz changed elsewhere asks to reload; uploads go through the content policies.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

const image = (name = 'photo.png', size?: number) =>
  ({ name, mimeType: 'image/png', buffer: size ? png(size) : PNG })

/** Saves the open question dialog with the stem and options given (the first `correct` ones marked). */
async function fillQuestion(dialog: Locator, stem: string, options: { text: string; correct?: boolean }[]) {
  await dialog.getByTestId('question-stem').fill(stem)
  for (let i = 0; i < options.length; i++) {
    if (i >= 2) await dialog.getByTestId('option-add').click()
    await dialog.getByTestId('option-text').nth(i).fill(options[i].text)
    if (options[i].correct) await dialog.getByTestId('option-correct').nth(i).click()
  }
}

async function saveDialog(page: Page, dialog: string, button: string) {
  await page.getByTestId(dialog).getByTestId(button).click()
  await expect(page.getByTestId(dialog)).toBeHidden()
}

test('a sponsor makes a quiz with questions, images and materials, and saves versions', async ({ page, request }) => {
  const { email, token } = await sponsor(request)
  const title = unique('Coffee ')
  await signIn(page, '/sponsor/', email)
  // Not approved yet: the onboarding, but the quizzes are open (editing needs no approval).
  await expect(page).toHaveURL(/\/sponsor\/onboarding$/)
  await page.getByRole('link', { name: 'Quizzes' }).click()
  await expect(page.getByTestId('quiz-list')).toBeVisible()

  await page.getByTestId('new-quiz').click()
  await page.getByTestId('new-quiz-title').fill(title)
  await page.getByTestId('new-quiz-create').click()
  await expect(page).toHaveURL(/\/sponsor\/quizzes\/[0-9a-f-]{36}$/)
  const quizId = page.url().split('/').pop()!
  await expect(page.getByTestId('editor-title')).toHaveText(title)

  // The heading: a Markdown introduction (previewed as takers see it), a cover and a time limit.
  await page.getByTestId('heading-intro').fill('Learn your **beans**.')
  await page.getByTestId('heading-intro-preview-toggle').click()
  await expect(page.getByTestId('heading-intro-preview').locator('strong')).toHaveText('beans')
  await page.getByTestId('heading-cover-upload').setInputFiles(image('cover.png', 700))
  await expect(page.getByTestId('heading-cover-current').getByRole('img')).toBeVisible()
  await page.getByTestId('heading-time-limit').fill('120')
  await page.getByTestId('heading-save').click()
  await expect(page.getByTestId('heading-saved')).toBeVisible()

  // A file the image policy refuses by its content, whatever its name says.
  await page.getByTestId('heading-cover-upload').setInputFiles({ name: 'fake.png', mimeType: 'image/png',
    buffer: Buffer.from('not an image at all') })
  await expect(page.getByTestId('heading-cover').getByRole('alert')).toBeVisible()

  // Two questions; the first with images on itself and on an option.
  await page.getByTestId('tab-questions').click()
  await page.getByTestId('question-add').click()
  let dialog = page.getByTestId('question-dialog')
  await fillQuestion(dialog, 'Which is a coffee bean?', [{ text: 'Arabica', correct: true }, { text: 'Granite' }])
  await dialog.getByTestId('question-image-upload').setInputFiles(image())
  await expect(dialog.getByTestId('question-image-current')).toBeVisible()
  await dialog.getByTestId('option-image-upload').nth(1).setInputFiles(image('granite.png'))
  await expect(dialog.getByTestId('option-image-current')).toHaveCount(1)
  await saveDialog(page, 'question-dialog', 'question-save')
  await page.getByTestId('question-add').click()
  dialog = page.getByTestId('question-dialog')
  await fillQuestion(dialog, 'Pick a tea', [{ text: 'Espresso' }, { text: 'Sencha', correct: true },
    { text: 'Latte' }])
  await saveDialog(page, 'question-dialog', 'question-save')
  const questions = page.getByTestId('question')
  await expect(questions).toHaveCount(2)
  await expect(questions.nth(1).locator('[data-correct="true"]')).toContainText('Sencha')

  // Moved up: the order is the server's (read again), and the keyboard stays on the moved question.
  await questions.nth(1).getByTestId('question-up').click()
  await expect(questions.nth(0).getByTestId('question-stem-text')).toHaveText('Pick a tea')
  await expect(questions.nth(0).getByTestId('question-down')).toBeFocused()

  // Materials: an article, an image group with a caption, a PDF, a link.
  await page.getByTestId('tab-materials').click()
  await page.getByTestId('material-add').click()
  dialog = page.getByTestId('material-dialog')
  await dialog.getByRole('radio', { name: 'Article' }).click()
  await dialog.getByTestId('material-title').fill('Bean guide')
  await dialog.getByTestId('material-body').fill('# Beans\n\nArabica and *robusta*.')
  await saveDialog(page, 'material-dialog', 'material-save')
  await page.getByTestId('material-add').click()
  await dialog.getByRole('radio', { name: 'Images' }).click()
  await dialog.getByTestId('material-title').fill('Gallery')
  await dialog.getByTestId('material-image-add-upload').setInputFiles(image('bean.png'))
  await dialog.getByTestId('material-image-caption').fill('A roasted bean')
  await saveDialog(page, 'material-dialog', 'material-save')
  await page.getByTestId('material-add').click()
  await dialog.getByRole('radio', { name: 'PDF' }).click()
  await dialog.getByTestId('material-title').fill('Price list')
  await dialog.getByTestId('material-pdf-upload').setInputFiles({ name: 'prices.pdf', mimeType: 'application/pdf',
    buffer: PDF })
  await expect(dialog.getByTestId('material-pdf-current')).toBeVisible()
  await saveDialog(page, 'material-dialog', 'material-save')
  await page.getByTestId('material-add').click()
  await dialog.getByRole('radio', { name: 'Link', exact: true }).click()
  await dialog.getByTestId('material-title').fill('Origins')
  await dialog.getByTestId('material-url').fill('https://example.com/origins')
  await saveDialog(page, 'material-dialog', 'material-save')
  const materials = page.getByTestId('material')
  await expect(materials).toHaveCount(4)
  await expect(materials.nth(1).getByRole('img')).toBeVisible()
  await expect(materials.nth(3).getByRole('link', { name: /example\.com\/origins/ })).toHaveAttribute('target', '_blank')

  // Version 1.0, then a change and 1.1; the first stays as it was.
  await page.getByTestId('save-version').click()
  await expect(page.getByTestId('version-saved')).toContainText('v1.0')
  await expect(page.getByTestId('editor-version')).toHaveText('v1.0')
  await page.getByTestId('tab-questions').click()
  await questions.nth(0).getByTestId('question-edit').click()
  await page.getByTestId('question-dialog').getByTestId('question-stem').fill('Pick a green tea')
  await saveDialog(page, 'question-dialog', 'question-save')
  await page.getByTestId('save-version').click()
  await expect(page.getByTestId('version-saved')).toContainText('v1.1')
  // Nothing changed since: refused, with the server's reason.
  await page.getByTestId('save-version').click()
  await expect(page.getByTestId('version-problems')).toBeVisible()

  await page.getByTestId('tab-versions').click()
  const rows = page.getByTestId('version-row')
  await expect(rows).toHaveCount(2)
  await page.locator('[data-testid="version-row"][data-label="v1.0"]').getByTestId('version-view').click()
  const view = page.getByTestId('version-dialog')
  await expect(view.getByTestId('version-question')).toHaveCount(2)
  await expect(view.getByTestId('version-question').first()).toContainText('Pick a tea')
  await expect(view.getByTestId('version-question').first().locator('[data-correct="true"]')).toContainText('Sencha')
  await page.keyboard.press('Escape')
  await expect(view).toBeHidden()

  // Back through the breadcrumb, the list: found by its title, with its latest version.
  await page.getByTestId('quiz-editor').getByRole('link', { name: 'Quizzes' }).click()
  await page.getByTestId('quiz-search').fill(title)
  const row = page.locator(`[data-testid="quiz-row"][data-quiz="${quizId}"]`)
  await expect(row).toContainText('v1.1')
  await expect(page.getByTestId('quiz-row')).toHaveCount(1)

  // The server agrees with what the page showed.
  const versions = await query(request, token, 'qb.sponsor.quiz-versions', { quizId })
  expect(versions.map((v) => v.label)).toEqual(['v1.1', 'v1.0'])
})

test('a sponsor copies a version into a new quiz and deletes the copy', async ({ page, request }) => {
  const { email, token } = await sponsor(request)
  const title = unique('Tea ')
  const quiz = await run(request, token, 'QB_QUIZ_SAVE', { title })
  const quizId = quiz.quizId as string
  await run(request, token, 'QB_QUESTION_SAVE', { quizId, stem: 'Green?', options: [
    { text: 'Yes', correct: true }, { text: 'No', correct: false }] })
  await run(request, token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  await run(request, token, 'QB_QUESTION_SAVE', { quizId, stem: 'Black?', options: [
    { text: 'Yes', correct: true }, { text: 'No', correct: false }] })

  await signIn(page, '/sponsor/', email)
  await expect(page).toHaveURL(/\/sponsor\/onboarding$/)
  await page.goto(`/sponsor/quizzes/${quizId}`)
  await page.getByTestId('tab-versions').click()
  await page.getByTestId('version-copy').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Copy' }).click()
  await expect(page).not.toHaveURL(new RegExp(`${quizId}$`))
  await expect(page).toHaveURL(/\/sponsor\/quizzes\/[0-9a-f-]{36}$/)
  const copyId = page.url().split('/').pop()!
  // The copy has the version's one question, not the draft's two.
  await page.getByTestId('tab-questions').click()
  await expect(page.getByTestId('question')).toHaveCount(1)

  await page.getByTestId('delete-quiz').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Delete' }).click()
  await expect(page).toHaveURL(/\/sponsor\/quizzes$/)
  await page.getByTestId('quiz-search').fill(title)
  await expect(page.locator(`[data-testid="quiz-row"][data-quiz="${quizId}"]`)).toBeVisible()
  await expect(page.locator(`[data-testid="quiz-row"][data-quiz="${copyId}"]`)).toHaveCount(0)
  // Deleted for good: the editor of the copy says so.
  await page.goto(`/sponsor/quizzes/${copyId}`)
  await expect(page.getByText('This quiz does not exist or is no longer yours.')).toBeVisible()

  // The draft copy (not a version) keeps the draft's content under the title chosen.
  await page.goto(`/sponsor/quizzes/${quizId}`)
  await page.getByTestId('copy-quiz').click()
  await page.getByTestId('copy-title').fill(`${title} (2)`)
  await page.getByTestId('copy-confirm').click()
  await expect(page.getByTestId('editor-title')).toHaveText(`${title} (2)`)
  await page.getByTestId('tab-questions').click()
  await expect(page.getByTestId('question')).toHaveCount(2)
})

test('an edit of a quiz changed elsewhere asks to reload first', async ({ page, request }) => {
  const { email, token } = await sponsor(request)
  const title = unique('Shared ')
  const cover = await upload(request, token, POLICY.image, 'cover.png', 'image/png', PNG)
  const quiz = await run(request, token, 'QB_QUIZ_SAVE', { title, cover })
  const quizId = quiz.quizId as string
  await signIn(page, '/sponsor/', email)
  await expect(page).toHaveURL(/\/sponsor\/onboarding$/)
  await page.goto(`/sponsor/quizzes/${quizId}`)
  await expect(page.getByTestId('heading-title')).toHaveValue(title)

  // Another window renames it.
  await run(request, token, 'QB_QUIZ_SAVE', { quizId, title: `${title} renamed`, cover })
  await page.getByTestId('heading-intro').fill('Mine')
  await page.getByTestId('heading-save').click()
  await expect(page.getByTestId('stale')).toBeVisible()
  await expect(page.getByTestId('stale')).toBeFocused()
  await expect(page.getByTestId('heading-save')).toBeDisabled()
  await page.getByTestId('reload').click()
  await expect(page.getByTestId('stale')).toBeHidden()
  await expect(page.getByTestId('heading-title')).toHaveValue(`${title} renamed`)
  await page.getByTestId('heading-intro').fill('Mine, after reading theirs')
  await page.getByTestId('heading-save').click()
  await expect(page.getByTestId('heading-saved')).toBeVisible()

  // Another sponsor's quiz is not found.
  const other = await sponsor(request, 'Olga')
  const theirs = await run(request, other.token, 'QB_QUIZ_SAVE', { title: unique('Theirs ') })
  await page.goto(`/sponsor/quizzes/${theirs.quizId as string}`)
  await expect(page.getByText('This quiz does not exist or is no longer yours.')).toBeVisible()
})
