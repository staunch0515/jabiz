import {
  adminToken,
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
  test,
  tokenOf,
  verifiedUser,
  verifyByMail,
} from './support'

/**
 * Accounts across the QuizBuks front ends (docs/quizbuks/plans/QP-pages.md section 3, QP1): registering at each entry,
 * the mailed links opening at that entry's own pages, the app's profile step, the sponsor's onboarding and its review,
 * and sessions kept apart by entry.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('a taker registers, verifies at the app\'s own page, completes the profile and stays signed in', async ({
  page,
  context,
}) => {
  const email = address('taker')
  await signUp(page, '/taker/', email, 'Mei')
  // The link leads to the app's own page (/auth/…, decision D43 item 4).
  await verifyByMail(page, email, '/taker/')
  await page.getByTestId('auth-continue').click()
  await expect(page).toHaveURL(/\/login$/)

  await signIn(page, '/taker/', email)
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
  await again.goto('/taker/account')
  await expect(again.getByTestId('profile-city')).toHaveValue('Seattle')
  await expect(again.getByTestId('sign-in').first()).toBeVisible()
  await expect(again.getByTestId('account-email')).toContainText(email)

  // The sponsor console has sessions of its own: the app's signs nobody in there.
  await again.goto('/sponsor/')
  await expect(again).toHaveURL(/\/sponsor\/login$/)
  // Nor does a taker's account open it: the console takes sponsors only.
  await signIn(again, '/sponsor/', email)
  await expect(again.getByTestId('login-error')).toBeVisible()
})

test('a taker who has not verified the address yet browses the app and is told to verify', async ({ page }) => {
  const email = address('unverified')
  await signUp(page, '/taker/', email, 'Yui')
  await signIn(page, '/taker/', email)
  // Not held at the welcome page: the app lets unverified takers browse (D-Q2-1); every write waits for the address.
  await expect(page.getByTestId('home')).toContainText('Yui')
  await expect(page.getByTestId('verify-email-banner')).toContainText(email)
  await page.goto('/taker/welcome')
  await expect(page.getByTestId('profile-verify-first')).toBeVisible()
  await expect(page.getByTestId('profile-save')).toBeDisabled()
})

test('a taker who forgot the password gets a link to the app\'s own page and signs in with a new one', async ({
  page,
}) => {
  const email = address('forgot')
  await signUp(page, '/taker/', email, 'Ken')
  await verifyByMail(page, email, '/taker/')

  await page.goto('/taker/login')
  await page.getByTestId('forgot-password').click()
  await expect(page).toHaveURL(/\/password-reset$/)
  await page.getByPlaceholder('E-mail address', { exact: true }).fill(email)
  await page.getByRole('button', { name: /send|link/i }).click()
  const mail = await mailTo(page.request, email, /password/i)
  const link = linkIn(mail.text, '/auth/password-reset')
  expect(link.pathname).toBe('/taker/auth/password-reset')
  await page.goto(link.pathname + link.search)
  const fresh = `${PASSWORD}-new`
  await page.getByPlaceholder('New password', { exact: true }).fill(fresh)
  await page.getByPlaceholder('Repeat the new password', { exact: true }).fill(fresh)
  await page.getByRole('button', { name: /password/i }).click()
  await expect(page.getByTestId('auth-continue')).toBeVisible()

  await page.goto('/taker/login')
  await page.getByPlaceholder('User name', { exact: true }).fill(email)
  await page.getByPlaceholder('Password', { exact: true }).fill(fresh)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page).toHaveURL(/\/welcome$/)
})

test('a sponsor registers, verifies at the console\'s own page, applies and is approved', async ({ page, request }) => {
  const email = address('sponsor')
  await signUp(page, '/sponsor/', email, 'Sara')
  // The console takes verified addresses only, and offers the mail again.
  await signIn(page, '/sponsor/', email)
  await expect(page.getByTestId('login-email-not-verified')).toBeVisible()
  await verifyByMail(page, email, '/sponsor/')

  await signIn(page, '/sponsor/', email)
  // Not applied yet: the console leads to the onboarding.
  await expect(page).toHaveURL(/\/sponsor\/onboarding$/)
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

  // An administrator approves the application; the console shows it and the mail links to the console.
  const sponsorToken = await tokenOf(request, email, PASSWORD, 'sponsor')
  const [own] = await rows(request, sponsorToken, 'urn:jabiz:dataset:sponsor:QbSponsor', [])
  await run(request, await adminToken(request), 'APPROVAL_DECIDE', {
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
  await expect(page).toHaveURL(/\/sponsor\/dashboard$/)
  await expect(page.getByTestId('dashboard-status')).toHaveAttribute('data-status', 'APPROVED')
})

test('the administration does not accept a taker', async ({ request }) => {
  const email = await verifiedUser(request, 'app', 'Tom')
  const app = await request.post('/api/auth/login', { data: { userName: email, password: PASSWORD, entry: 'app' } })
  expect(app.status()).toBe(200)
  // Refused as a wrong password would be: nothing tells whether the account exists (decision D36 item 1).
  const admin = await request.post('/api/auth/login', { data: { userName: email, password: PASSWORD, entry: 'admin' } })
  expect(admin.status()).toBe(401)
})
