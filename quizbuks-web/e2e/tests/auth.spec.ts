import {
  
  address,
  expect,
  linkIn,
  mailTo,
  PASSWORD,
  prepareQuizbuks,
  rows,
  run,
  signIn,
  signUp,
  submitSignIn,
  takerByLink,
  test,
  tokenOf,
  verifiedUser,
  adminToken,
  insert,
} from './support'
import { reviewerFor } from './operators'

/**
 * Accounts across the QuizBuks front ends (docs/quizbuks/plans/QP-pages.md section 3, QP1): registering at each entry,
 * the mailed links opening at that entry's own pages, the app's profile step, the sponsor's onboarding and its review,
 * and sessions kept apart by entry.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('a Taker continues with the address, finishes signing up at the mailed link, completes the profile and stays signed in',
  async ({ page, context }) => {
  const email = address('taker')
  // Q113f-2: one "continue" (platform D84): the address, then a link by mail, whether or not it has an account.
  await page.goto('/taker/login')
  await page.getByTestId('continue-email').fill(email)
  await page.getByTestId('continue-form').locator('button[type="submit"]').click()
  await expect(page.getByTestId('continue-link-sent')).toContainText(email)
  // The link leads to the app's own page (/auth/…, decision D43 item 4); a new address gives its name first.
  const mail = await mailTo(page.request, email, /link/i)
  const link = linkIn(mail.text, '/auth/email-link')
  expect(link.pathname).toBe('/taker/auth/email-link')
  await page.goto(link.pathname + link.search)
  await expect(page.getByTestId('complete-sign-up-note')).toContainText(/accept these/i)
  await page.getByTestId('complete-sign-up-name').fill('Mei')
  await page.getByTestId('complete-sign-up').locator('button[type="submit"]').click()

  // Registration's second step: the profile, starting from the sign-up name and the browser's country (en-US).
  await expect(page).toHaveURL(/\/welcome$/)
  await expect(page.getByTestId('profile-name')).toHaveValue('Mei')
  await expect(page.getByTestId('profile-country')).toContainText('United States')
  await page.getByTestId('profile-city').fill('Seattle')
  // The terms and the privacy policy in effect are accepted here (Q7b), each linked to its text.
  await expect(page.getByTestId('profile-save')).toBeDisabled()
  await expect(page.getByTestId('legal-link-TERMS_APP')).toHaveAttribute('href', '/taker/legal/terms')
  await page.getByTestId('profile-terms').click()
  await page.getByTestId('profile-save').click()
  await expect(page.getByTestId('home')).toContainText('Mei')
  await expect(page).toHaveURL(/\/$/)
  await mailTo(page.request, email, /Welcome, Mei/)

  // Kept on the device (the app's session persistence), under the app's own key.
  const keys = await page.evaluate(() => Object.keys(window.localStorage))
  expect(keys).toContain('jabiz.session.app.refresh')
  await page.reload()
  await expect(page.getByTestId('home')).toBeVisible()
  const again = await context.newPage()
  await again.goto('/taker/account/profile')
  await expect(again.getByTestId('profile-city')).toHaveValue('Seattle')
  await again.goto('/taker/account/security')
  await expect(again.getByTestId('sign-in').first()).toBeVisible()
  await expect(again.getByTestId('account-email')).toContainText(email)

  // The sponsor console has sessions of its own: the app's signs nobody in there.
  await again.goto('/sponsor/')
  await expect(again).toHaveURL(/\/sponsor\/login$/)
  // Nor does a taker's account open it: the console takes sponsors only.
  await submitSignIn(again, '/sponsor/', email)
  await expect(again.getByTestId('login-error')).toBeVisible()
})

test('a Taker who has not verified the address yet browses the app and is told to verify', async ({ page, request }) => {
  // Since Q113f-2 nobody signs up at the app without proving the address; the Takers who registered with a password
  // before it may not have verified theirs yet. Such an account, made as theirs were: the address as its name, a
  // password, unverified.
  const email = address('unverified')
  const admin = await adminToken(request)
  const created = await run(request, admin, 'SEC_USER_CREATE', { userName: email, displayName: 'Yui', email,
    password: PASSWORD })
  const [taker] = await rows(request, admin, 'urn:jabiz:dataset:platform:SecRole',
    [{ field: 'roleCode', op: 'eq', value: 'QB_TAKER' }], 1)
  await insert(request, admin, 'urn:jabiz:dataset:platform:SecUserRole', { userId: created.userId, roleId: taker.id })
  await signIn(page, '/taker/', email)
  // Not held at the welcome page: the app lets unverified takers browse (D-Q2-1); every write waits for the address.
  await expect(page.getByTestId('home')).toContainText('Yui')
  await expect(page.getByTestId('verify-email-banner')).toContainText(email)
  await page.goto('/taker/welcome')
  await expect(page.getByTestId('profile-verify-first')).toBeVisible()
  await expect(page.getByTestId('profile-save')).toBeDisabled()
})

test('a Taker who forgot the password gets a link to the app\'s own page and signs in with a new one', async ({
  page,
}) => {
  // Signed up by link (Q113f-2): no password yet; "forgot your password" gives it one.
  const email = await takerByLink(page.request, 'Ken', address('forgot'))

  // "Forgot your password?" is on the password step of "continue" (Q113f-2).
  await page.goto('/taker/login')
  await page.getByTestId('continue-email').fill(email)
  await page.getByTestId('continue-form').locator('button[type="submit"]').click()
  await page.getByTestId('continue-use-password').click()
  await page.getByTestId('forgot-password').click()
  await expect(page).toHaveURL(/\/password-reset$/)
  await page.getByLabel('E-mail address', { exact: true }).fill(email)
  await page.getByRole('button', { name: /send|link/i }).click()
  const mail = await mailTo(page.request, email, /password/i)
  const link = linkIn(mail.text, '/auth/password-reset')
  expect(link.pathname).toBe('/taker/auth/password-reset')
  await page.goto(link.pathname + link.search)
  const fresh = `${PASSWORD}-new`
  await page.getByLabel('New password', { exact: true }).fill(fresh)
  await page.getByLabel('Repeat the new password', { exact: true }).fill(fresh)
  await page.getByRole('button', { name: /password/i }).click()
  await expect(page.getByTestId('auth-continue')).toBeVisible()

  // "Continue" mails a link on a device that never signed in with a password; the password is on the same step.
  await page.goto('/taker/login')
  await page.getByTestId('continue-email').fill(email)
  await page.getByTestId('continue-form').locator('button[type="submit"]').click()
  await page.getByTestId('continue-use-password').click()
  await page.getByTestId('continue-password').fill(fresh)
  await page.getByTestId('continue-password-step').locator('form button[type="submit"]').click()
  await expect(page).toHaveURL(/\/welcome$/)
})

test('a Sponsor registers, verifies at the console\'s own page, applies and is approved', async ({ page, request }) => {
  const email = address('sponsor')
  await signUp(page, '/sponsor/', email, 'Sara')
  // The console signs the sponsor in unverified (Q40) and says to verify; applying waits for it.
  await signIn(page, '/sponsor/', email)
  await expect(page).toHaveURL(/\/sponsor\/profile\/basics$/)
  await expect(page.getByTestId('verify-email-banner')).toBeVisible()
  // The link opened where the console is signed in verifies without a password (platform D61 item 4).
  const mail = await mailTo(request, email, /verify|confirm|address/i)
  const link = linkIn(mail.text, '/auth/verify-email')
  expect(link.pathname).toBe('/sponsor/auth/verify-email')
  await page.goto(link.pathname + link.search)
  await page.getByTestId('verify-start').click()
  await expect(page.getByTestId('verify-state').locator('[data-state]')).toHaveAttribute('data-state', 'done')
  await expect(page.getByTestId('verify-password-hint')).toHaveCount(0)

  // Back in the console, the session's next token knows the address is verified.
  await page.goto('/sponsor/')
  await expect(page.getByTestId('verify-email-banner')).toHaveCount(0)
  // Not applied yet: the console leads to the hub's basics (Q79; the old /onboarding redirects there).
  await expect(page).toHaveURL(/\/sponsor\/profile\/basics$/)
  await expect(page.getByTestId('onboarding-status')).toHaveAttribute('data-status', 'NONE')
  await expect(page.getByTestId('onboarding-name')).toHaveValue('Sara')
  await page.getByTestId('onboarding-company').fill('Acme Quizzes')
  await page.getByTestId('onboarding-intro').fill('We make **quizzes** about tea.')
  // The terms are required.
  await page.getByTestId('onboarding-submit').click()
  await expect(page.getByText(/terms/i).last()).toBeVisible()
  await page.getByTestId('onboarding-terms').click()
  await page.getByTestId('onboarding-submit').click()
  await expect(page.getByTestId('onboarding-done')).toBeVisible()
  await expect(page.getByTestId('onboarding-status')).toHaveAttribute('data-status', 'PENDING')
  // The console keeps its session in the tab only.
  const stored = await page.evaluate(() => Object.keys(window.localStorage))
  expect(stored).not.toContain('jabiz.session.sponsor.refresh')

  // A member of the Sponsor's operator approves the application (F-S1); the Sponsor app shows it and the mail links
  // to it.
  const sponsorToken = await tokenOf(request, email, PASSWORD, 'sponsor')
  const [own] = await rows(request, sponsorToken, 'urn:jabiz:dataset:sponsor:QbSponsor', [])
  await run(request, await reviewerFor(request, sponsorToken), 'APPROVAL_DECIDE', {
    requestId: own.attributes.approvalRequestId, decision: 'APPROVE' })
  await expect.poll(async () => {
    await page.reload()
    return page.getByTestId('onboarding-status').getAttribute('data-status')
  }, { timeout: 30_000 }).toBe('APPROVED')
  const approved = await mailTo(request, email, /approved/i)
  expect(linkIn(approved.text, '/sponsor/').pathname).toBe('/sponsor/')

  // Approved: the profile changes without a review, and the console starts at the dashboard.
  await expect(page.getByTestId('onboarding-terms')).toHaveCount(0)
  await page.getByTestId('onboarding-company').fill('Acme Quizzes Ltd')
  await page.getByTestId('onboarding-submit').click()
  await expect(page.getByTestId('onboarding-done')).toBeVisible()
  await page.goto('/sponsor/')
  await expect(page).toHaveURL(/\/sponsor\/home$/)
  await expect(page.getByTestId('dashboard-status')).toHaveAttribute('data-status', 'APPROVED')
})

test('the administration does not accept a Taker', async ({ request }) => {
  const email = await verifiedUser(request, 'app', 'Tom')
  const app = await request.post('/api/auth/login', { data: { userName: email, password: PASSWORD, entry: 'app' } })
  expect(app.status()).toBe(200)
  // Refused as a wrong password would be: nothing tells whether the account exists (decision D36 item 1).
  const admin = await request.post('/api/auth/login', { data: { userName: email, password: PASSWORD, entry: 'admin' } })
  expect(admin.status()).toBe(401)
})
