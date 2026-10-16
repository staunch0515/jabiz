import type { APIRequestContext, Page } from '@playwright/test'
import { signInAdmin, submittedPublication } from './admin'
import { bookingMonthOf } from './finance'
import { completeQuiz, sponsor } from './content'
import { approvedSponsor, livePublication } from './publishing'
import { rewardingPublication, startAttempt, submitRight } from './transactions'
import { fundedSponsor, takerInJapan } from './wallet'
import {
  expect,
  expectAccessible,
  PASSWORD,
  prepareQuizbuks,
  rows,
  run,
  signIn,
  test,
  tokenOf,
  useAppearance,
  verifiedUser,
} from './support'

/**
 * WCAG 2.2 A and AA on every page of the QuizBuks front ends (docs/quizbuks/plans/QP-pages.md section 6), in the light
 * and the dark appearance, at a phone's width for the app (it is designed for 430 px) and a desktop's for both.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** A taker with a complete profile, signed in at the app. */
async function takerWithProfile(page: Page, request: APIRequestContext) {
  const email = await verifiedUser(request, 'app', 'Aiko')
  const token = await tokenOf(request, email, PASSWORD, 'app')
  await run(request, token, 'QB_PROFILE_SAVE', { displayName: 'Aiko', countryCode: 'JP', preferredCurrency: 'JPY' })
  await signIn(page, '/', email)
  await expect(page.getByTestId('home')).toBeVisible()
}

for (const appearance of ['light', 'dark'] as const) {
  const at = (name: string) => (appearance === 'light' ? name : `${name} (dark)`)

  test(`the app's pages pass axe (${appearance})`, async ({ page, request }) => {
    await useAppearance(page, appearance)
    for (const width of [390, 1280]) {
      await page.setViewportSize({ width, height: 844 })
      const size = `${width}px`
      await page.goto('/login')
      await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible()
      if (appearance === 'dark') await expect(page.locator('html')).toHaveClass(/dark/)
      else await expect(page.locator('html')).not.toHaveClass(/dark/)
      await expectAccessible(page, at(`app sign-in ${size}`))
      await page.goto('/signup')
      await expect(page.getByRole('button', { name: 'Create the account' })).toBeVisible()
      await expectAccessible(page, at(`app sign-up ${size}`))
      await page.goto('/password-reset')
      await expect(page.getByPlaceholder('E-mail address', { exact: true })).toBeVisible()
      await expectAccessible(page, at(`app password reset ${size}`))
      await page.goto('/auth/verify-email?entry=app&token=not-a-real-token')
      await expect(page.getByTestId('verify-start')).toBeVisible()
      await expectAccessible(page, at(`app verification link ${size}`))
    }

    // Signed in without a profile: the welcome page (the address is verified, so the form is open).
    await page.setViewportSize({ width: 390, height: 844 })
    const email = await verifiedUser(request, 'app', 'Ren')
    await signIn(page, '/', email)
    await expect(page.getByTestId('profile-form')).toBeVisible()
    await expect(page.getByTestId('profile-country')).not.toBeEmpty()
    await expectAccessible(page, at('app welcome'))
    await page.getByTestId('profile-country').click()
    await expect(page.getByRole('option').first()).toBeVisible()
    await expectAccessible(page, at('app country picker'))
    await page.keyboard.press('Escape')
    await page.getByTestId('sign-out').click()
    await expect(page).toHaveURL(/\/login$/)

    await takerWithProfile(page, request)
    await expectAccessible(page, at('app home'))
    await page.getByTestId('nav-account').click()
    await expect(page.getByTestId('sign-in').first()).toBeVisible()
    await expectAccessible(page, at('app account'))
    await page.setViewportSize({ width: 1280, height: 900 })
    await expectAccessible(page, at('app account 1280px'))
  })

  test(`the sponsor console's pages pass axe (${appearance})`, async ({ page, request }) => {
    await useAppearance(page, appearance)
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto('/sponsor/login')
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible()
    await expectAccessible(page, at('sponsor sign-in'))
    await page.goto('/sponsor/signup')
    await expect(page.getByRole('button', { name: 'Create the account' })).toBeVisible()
    await expectAccessible(page, at('sponsor sign-up'))
    await page.goto('/sponsor/auth/password-reset?entry=sponsor')
    await expect(page.getByPlaceholder('E-mail address', { exact: true })).toBeVisible()
    await expectAccessible(page, at('sponsor password reset'))

    const email = await verifiedUser(request, 'sponsor', 'Sam')
    await signIn(page, '/sponsor/', email)
    await expect(page.getByTestId('onboarding-form')).toBeVisible()
    await expectAccessible(page, at('sponsor onboarding'))
    // The refusal of a form without the terms, next to its fields.
    await page.getByTestId('onboarding-company').fill('Access Co')
    await page.getByTestId('onboarding-submit').click()
    await expect(page.locator('form .text-destructive').first()).toBeVisible()
    await expectAccessible(page, at('sponsor onboarding refused'))
    await page.getByRole('link', { name: 'Dashboard' }).click()
    await expect(page.getByTestId('dashboard-status')).toBeVisible()
    await expectAccessible(page, at('sponsor dashboard'))
    await page.getByRole('link', { name: 'Account' }).click()
    await expect(page.getByTestId('sign-in').first()).toBeVisible()
    await expectAccessible(page, at('sponsor account'))
    // The console at a phone's width: the menu is a sheet.
    await page.setViewportSize({ width: 390, height: 844 })
    await expectAccessible(page, at('sponsor account 390px'))
  })

  test(`the sponsor console's quiz pages pass axe (${appearance})`, async ({ page, request }) => {
    await useAppearance(page, appearance)
    await page.setViewportSize({ width: 1280, height: 900 })
    const { email, token } = await sponsor(request, 'Cleo')
    const quiz = await completeQuiz(request, token)
    await signIn(page, '/sponsor/', email)
    await expect(page.getByTestId('onboarding-form')).toBeVisible()

    await page.goto('/sponsor/quizzes')
    await expect(page.getByTestId('quiz-row').first()).toBeVisible()
    await expectAccessible(page, at('sponsor quizzes'))
    await page.getByTestId('new-quiz').click()
    await expect(page.getByTestId('new-quiz-dialog')).toBeVisible()
    await expectAccessible(page, at('sponsor new quiz'))
    await page.keyboard.press('Escape')

    await page.getByRole('link', { name: quiz.title }).click()
    await expect(page.getByTestId('heading-cover-current').getByRole('img')).toBeVisible()
    await page.getByTestId('heading-intro-preview-toggle').click()
    await expectAccessible(page, at('sponsor quiz editor'))
    await page.getByTestId('tab-questions').click()
    await expect(page.getByTestId('question')).toHaveCount(2)
    await expectAccessible(page, at('sponsor questions'))
    await page.getByTestId('question-edit').first().click()
    await expect(page.getByTestId('option-text')).toHaveCount(2)
    await expectAccessible(page, at('sponsor question dialog'))
    await page.keyboard.press('Escape')
    await page.getByTestId('tab-materials').click()
    await page.getByTestId('material').first().locator('summary').click()
    await expectAccessible(page, at('sponsor materials'))
    await page.getByTestId('material-add').click()
    await page.getByTestId('material-dialog').getByRole('radio', { name: 'Images' }).click()
    await expectAccessible(page, at('sponsor material dialog'))
    await page.keyboard.press('Escape')
    await page.getByTestId('tab-versions').click()
    await expect(page.getByTestId('version-row')).toHaveCount(1)
    await expectAccessible(page, at('sponsor versions'))
    await page.getByTestId('version-view').click()
    await expect(page.getByTestId('version-question')).toHaveCount(2)
    await expectAccessible(page, at('sponsor version'))
    await page.keyboard.press('Escape')

    // Refused: a version that did not change, and an edit of a quiz changed elsewhere.
    await page.getByTestId('save-version').click()
    await expect(page.getByTestId('version-problems')).toBeVisible()
    await run(request, token, 'QB_QUIZ_SAVE', { quizId: quiz.quizId, title: `${quiz.title} 2` })
    await page.getByTestId('tab-quiz').click()
    await page.getByTestId('heading-save').click()
    await expect(page.getByTestId('stale')).toBeVisible()
    await expectAccessible(page, at('sponsor quiz refused'))
    await page.setViewportSize({ width: 390, height: 844 })
    await expectAccessible(page, at('sponsor quiz editor 390px'))
  })

  test(`the sponsor console's publishing pages pass axe (${appearance})`, async ({ page, request }) => {
    await useAppearance(page, appearance)
    await page.setViewportSize({ width: 1280, height: 900 })
    const { email, token } = await approvedSponsor(request, 'Ada')
    const quiz = await completeQuiz(request, token)
    const live = await livePublication(request, token, quiz.quizId, `Access run ${quiz.title}`)
    await signIn(page, '/sponsor/', email)
    await expect(page.getByTestId('dashboard-status')).toBeVisible()

    await page.goto('/sponsor/publications')
    await expect(page.getByTestId('publication-row').first()).toBeVisible()
    await expect(page.getByTestId('publication-status').first()).toContainText('Live')
    await expectAccessible(page, at('sponsor publications'))

    // The wizard's three steps, the quiz chooser open, and the problems of an incomplete draft.
    await page.getByTestId('new-publication').click()
    await expect(page.getByTestId('publication-name')).toBeVisible()
    await expectAccessible(page, at('sponsor new publication'))
    await page.getByTestId('publication-name').fill('Access draft')
    await page.getByTestId('publication-quiz').click()
    await page.getByPlaceholder('Search your quizzes').fill(quiz.title)
    await expect(page.getByRole('option', { name: new RegExp(quiz.title) })).toBeVisible()
    await expectAccessible(page, at('sponsor publication quiz chooser'))
    await page.getByRole('option', { name: new RegExp(quiz.title) }).click()
    await expect(page.getByTestId('publication-version')).toContainText(quiz.label)
    await page.getByTestId('publication-next').click()
    await expect(page.getByTestId('publication-step-title')).toHaveText('Audience and rewards')
    await page.getByTestId('tier-add').click()
    await page.getByTestId('tier-reward').fill('10')
    await expectAccessible(page, at('sponsor publication audience and rewards'))
    // Typed wrong: the field is marked and says why.
    await page.getByTestId('publication-pass-score').fill('1.5')
    await page.getByTestId('publication-next').click()
    await expect(page.getByTestId('publication-pass-score')).toHaveAttribute('aria-invalid', 'true')
    await expectAccessible(page, at('sponsor publication typed wrong'))
    await page.getByTestId('publication-pass-score').fill('')
    await page.getByTestId('publication-next').click()
    await expect(page.getByTestId('publication-problems')).toBeVisible()
    await expectAccessible(page, at('sponsor publication problems'))
    await page.setViewportSize({ width: 390, height: 844 })
    await expectAccessible(page, at('sponsor publication problems 390px'))
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.getByTestId('publication-delete').click()
    await expect(page.getByRole('alertdialog')).toBeVisible()
    await expectAccessible(page, at('sponsor publication delete'))
    await page.getByRole('alertdialog').getByRole('button', { name: 'Delete' }).click()
    await expect(page.getByTestId('publication-list')).toBeVisible()

    // A live publication: frozen, with its actions and their confirmations.
    await page.goto(`/sponsor/publications/${live}`)
    await expect(page.getByTestId('publication-view')).toHaveAttribute('data-status', 'LIVE')
    await expectAccessible(page, at('sponsor live publication'))
    await page.getByTestId('publication-pause').click()
    await expect(page.getByRole('alertdialog')).toBeVisible()
    await expectAccessible(page, at('sponsor publication pause'))
    await page.keyboard.press('Escape')
    await page.getByTestId('publication-copy').click()
    await expect(page.getByTestId('publication-copy-dialog')).toBeVisible()
    await expectAccessible(page, at('sponsor publication copy'))
    await page.keyboard.press('Escape')
    await page.setViewportSize({ width: 390, height: 844 })
    await expectAccessible(page, at('sponsor live publication 390px'))
  })

  test(`the administration's QuizBuks pages pass axe (${appearance})`, async ({ page, request }) => {
    test.setTimeout(150_000)
    await useAppearance(page, appearance)
    await page.setViewportSize({ width: 1280, height: 900 })
    const { token } = await approvedSponsor(request, 'Ines')
    const quiz = await completeQuiz(request, token)
    const publicationId = await submittedPublication(request, token, quiz.quizId, `Access review ${quiz.title}`)
    const applicant = await sponsor(request, 'Jonas')
    await run(request, applicant.token, 'QB_SPONSOR_APPLY', { displayName: 'Jonas', company: 'Jonas Co',
      intro: 'We **ask**.', acceptTerms: true })
    const userId = (await rows(request, applicant.token, 'urn:jabiz:dataset:sponsor:QbSponsor', []))[0]
      .attributes.userId as string
    await signInAdmin(page)

    await page.goto('/admin/qb/reviews')
    await expect(page.getByTestId('queue-publication').first()).toBeVisible()
    if (appearance === 'dark') await expect(page.locator('html')).toHaveClass(/dark/)
    else await expect(page.locator('html')).not.toHaveClass(/dark/)
    await expectAccessible(page, at('admin review queue'))
    await page.getByTestId('tab-sponsors').click()
    await expect(page.getByTestId('queue-sponsor').first()).toBeVisible()
    await expectAccessible(page, at('admin sponsor queue'))
    await page.goto(`/admin/qb/reviews/publications/${publicationId}`)
    await expect(page.getByTestId('preview-question')).toHaveCount(2)
    await expect(page.getByTestId('version-preview').getByRole('img').first()).toBeVisible()
    await expect(page.getByTestId('approval-approve')).toBeVisible()
    await expectAccessible(page, at('admin publication review'))
    await page.goto(`/admin/qb/reviews/sponsors/${userId}`)
    await expect(page.getByTestId('approval-approve')).toBeVisible()
    await expectAccessible(page, at('admin sponsor review'))
    await page.goto('/admin/qb/users')
    await expect(page.getByTestId('user-row').first()).toBeVisible()
    await expectAccessible(page, at('admin users'))
    await page.goto(`/admin/qb/users/${userId}`)
    await expect(page.getByTestId('user-attempts')).toBeVisible()
    await expect(page.getByTestId('user-payout')).toBeVisible()
    await expectAccessible(page, at('admin user summary'))
  })
}

// Q9c: finance's pages in the administration, with a funded sponsor and a paid reward so that the lists and the
// month's reports have rows.
for (const appearance of ['light', 'dark'] as const) {
  const at = (name: string) => (appearance === 'light' ? name : `${name} (dark)`)

  test(`the administration's finance pages pass axe (${appearance})`, async ({ page, request }) => {
    test.setTimeout(150_000)
    await useAppearance(page, appearance)
    await page.setViewportSize({ width: 1280, height: 900 })
    const sponsor = await fundedSponsor(request, 1000)
    const taker = await takerInJapan(request, 'Ida')
    const { publicationId } = await rewardingPublication(request, sponsor.token, 100, taker.token)
    const started = await startAttempt(request, taker.token, publicationId)
    expect(await submitRight(request, taker.token, started)).toBe('PAID')
    const month = await bookingMonthOf(request, 'QbAttempt', started.attemptId)
    await signInAdmin(page)

    await page.goto('/admin/qb/finance')
    await expect(page.getByTestId('finance-in-review')).toBeVisible()
    await expect(page.getByTestId('finance-account')).toHaveCount(7)
    if (appearance === 'dark') await expect(page.locator('html')).toHaveClass(/dark/)
    await expectAccessible(page, at('admin finance overview'))
    await page.goto('/admin/qb/finance/transfers')
    await expect(page.getByTestId('finance-transfers')).toBeVisible()
    await expect(page.locator('[data-slot="table"]').first()).toBeVisible()
    await expectAccessible(page, at('admin finance transfers'))
    await page.goto('/admin/qb/finance/topups')
    await expect(page.getByTestId('topup-row').first()).toBeVisible()
    await expectAccessible(page, at('admin finance top-ups'))
    await page.goto('/admin/qb/finance/reconciliation')
    await expect(page.getByTestId('recon-runs')).toBeVisible()
    await expectAccessible(page, at('admin finance reconciliation'))
    await page.goto(`/admin/qb/finance/reports?month=${month}`)
    await expect(page.locator('[data-testid="monthly-row"][data-category="REWARD"]')).toBeVisible()
    await expect(page.getByTestId('balance-row').first()).toBeVisible()
    await expectAccessible(page, at('admin finance reports'))
    // Q9d: the journal export with the next month's preview, and the account mapping.
    await page.goto('/admin/qb/finance/journal')
    await page.getByTestId('journal-preview').click()
    await expect(page.getByTestId('journal-preview-result')).toBeVisible()
    await expectAccessible(page, at('admin finance journal'))
    await page.goto('/admin/qb/finance/journal/mapping')
    await expect(page.getByTestId('mapping-row').first()).toBeVisible()
    await expectAccessible(page, at('admin finance journal mapping'))
  })
}

// Q9b: the transactions page with a reward and a top-up, the filters open and one user chosen, at both widths.
for (const appearance of ['light', 'dark'] as const) {
  const at = (name: string) => (appearance === 'light' ? name : `${name} (dark)`)

  test(`the sponsor console's transactions page passes axe (${appearance})`, async ({ page, request }) => {
    test.setTimeout(180_000)
    await useAppearance(page, appearance)
    await page.setViewportSize({ width: 1280, height: 900 })
    const sponsor = await fundedSponsor(request, 1000)
    const taker = await takerInJapan(request, 'Iris')
    const { publicationId } = await rewardingPublication(request, sponsor.token, 100, taker.token)
    expect(await submitRight(request, taker.token, await startAttempt(request, taker.token, publicationId)))
      .toBe('PAID')
    await signIn(page, '/sponsor/', sponsor.email)
    await expect(page.getByTestId('dashboard-status')).toBeVisible()

    await page.goto('/sponsor/transactions')
    await expect(page.getByTestId('transaction-row')).toHaveCount(2)
    await expect(page.getByTestId('summary-total')).toHaveText('2')
    await expectAccessible(page, at('sponsor transactions'))
    await page.getByRole('combobox', { name: 'Status' }).click()
    await expect(page.getByRole('option', { name: 'Completed' })).toBeVisible()
    await expectAccessible(page, at('sponsor transactions status chooser'))
    await page.keyboard.press('Escape')
    await page.getByTestId('transaction-only-user').click()
    await expect(page.getByTestId('transactions-user-filter')).toBeVisible()
    await expect(page.getByTestId('transaction-row')).toHaveCount(1)
    await expectAccessible(page, at('sponsor transactions of one user'))
    await page.setViewportSize({ width: 390, height: 844 })
    await expectAccessible(page, at('sponsor transactions 390px'))
  })
}
