import type { APIRequestContext, Page } from '@playwright/test'
import { completeQuiz, sponsor } from './content'
import {
  expect,
  expectAccessible,
  PASSWORD,
  prepareQuizbuks,
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
}
