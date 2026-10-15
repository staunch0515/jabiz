import { signInAdmin, submittedPublication } from './admin'
import { completeQuiz, sponsor } from './content'
import { publicationOf } from './publishing'
import { expect, prepareQuizbuks, rows, run, signIn, test, unique } from './support'

/*
 * The administration's review (docs/quizbuks/plans/Q9a-admin-extension.md): the administrator approves a sponsor's
 * application from its page, then sees a submitted publication in full (question, correct answer, material), sends it
 * back with a reason the sponsor reads, and approves it once it is submitted again.
 */

test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('a sponsor and a publication are reviewed in the administration', async ({ page, context, request }) => {
  test.setTimeout(180_000)
  // A sponsor applies; the application waits for review.
  const made = await sponsor(request, 'Vera')
  const company = unique('Vera Co ')
  const applied = await run(request, made.token, 'QB_SPONSOR_APPLY', { displayName: 'Vera', company,
    intro: 'We ask about **capitals**.', acceptTerms: true })
  expect(applied.approval).toBe('PENDING')

  await signInAdmin(page)
  await expect(page.getByText('QuizBuks', { exact: true }).first()).toBeVisible()
  await page.goto('/admin/qb/reviews?tab=sponsors')
  await page.getByRole('link', { name: `Review the application of ${company}` }).click()
  const application = page.getByTestId('sponsor-review')
  await expect(application).toHaveAttribute('data-status', 'PENDING')
  await expect(page.getByTestId('sponsor-application')).toContainText('Vera')
  await expect(page.getByTestId('sponsor-email')).toContainText(`${made.email} (verified)`)
  await expect(page.getByTestId('sponsor-intro').locator('strong')).toHaveText('capitals')
  await page.getByTestId('approval-approve').click()
  await expect(application).toHaveAttribute('data-status', 'APPROVED', { timeout: 30_000 })

  // The approved sponsor submits a publication of a complete quiz.
  const quiz = await completeQuiz(request, made.token)
  const name = `Review run ${quiz.title}`
  const publicationId = await submittedPublication(request, made.token, quiz.quizId, name)

  await page.goto('/admin/qb/reviews')
  await page.getByRole('link', { name: `Review ${name}` }).click()
  const review = page.getByTestId('publication-review')
  await expect(review).toHaveAttribute('data-status', 'PENDING_REVIEW')
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible()
  await expect(page.getByTestId('publication-sponsor')).toHaveText(company)
  await expect(page.getByTestId('publication-tier')).toHaveCount(2)
  // The whole frozen version: every question with its correct answer marked, and the material.
  const preview = page.getByTestId('version-preview')
  await expect(preview.getByTestId('preview-title')).toHaveText(quiz.title)
  await expect(preview.getByTestId('preview-question')).toHaveCount(2)
  await expect(preview.getByTestId('preview-stem').first()).toHaveText('Question 1?')
  const correct = preview.locator('[data-testid="preview-option"][data-correct="true"]')
  await expect(correct).toHaveCount(2)
  await expect(correct.first()).toContainText('Right 1')
  await expect(correct.first()).toContainText('Correct answer')
  await expect(preview.locator('[data-testid="preview-option"][data-correct="false"]').first()).toContainText('Wrong 1')
  const material = preview.getByTestId('preview-material')
  await expect(material).toHaveCount(1)
  await expect(material).toContainText('Read first')
  await material.getByText('Show the text').click()
  await expect(material).toContainText('The right answers start with')
  await expect(preview.getByRole('img').first()).toBeVisible()

  // Sent back with a reason; the sponsor reads it in the console.
  const reason = `Make the questions harder (${unique('r')})`
  const decision = page.getByTestId('publication-decision')
  await expect(decision.getByTestId('approval-reject')).toBeDisabled()
  await decision.getByRole('textbox').fill(reason)
  await decision.getByTestId('approval-reject').click()
  await expect(review).toHaveAttribute('data-status', 'DRAFT', { timeout: 30_000 })
  await expect(page.getByTestId('publication-not-pending')).toContainText(reason)

  const sponsorPage = await context.newPage()
  await signIn(sponsorPage, '/sponsor/', made.email)
  await expect(sponsorPage.getByTestId('dashboard-status')).toBeVisible()
  await sponsorPage.goto(`/sponsor/publications/${publicationId}`)
  await expect(sponsorPage.getByTestId('publication-sent-back-reason')).toContainText(reason)
  await sponsorPage.close()

  // Submitted again and approved: it goes on at its start.
  await run(request, made.token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  await page.reload()
  await expect(review).toHaveAttribute('data-status', 'PENDING_REVIEW')
  await expect(preview.getByTestId('preview-question')).toHaveCount(2)
  await decision.getByTestId('approval-approve').click()
  await expect(review).toHaveAttribute('data-status', /^(SCHEDULED|LIVE)$/, { timeout: 30_000 })
  expect(['SCHEDULED', 'LIVE']).toContain((await publicationOf(request, made.token, publicationId)).status)

  // The sponsor's summary (P-01) shows the account; the administrator holds finance too, so money is shown.
  await page.getByTestId('publication-sponsor').click()
  await expect(page.getByTestId('user-summary')).toBeVisible()
  await expect(page.getByTestId('user-profile')).toContainText(made.email)
  await expect(page.getByTestId('user-finance')).toBeVisible()
  const onboarding = await rows(request, made.token, 'urn:jabiz:dataset:sponsor:QbSponsor', [])
  expect(onboarding[0]?.attributes.reviewStatus).toBe('APPROVED')
})
