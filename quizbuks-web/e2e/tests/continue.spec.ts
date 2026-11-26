import {
  accessibleInBoth,
  address,
  expect,
  linkIn,
  mailTo,
  PASSWORD,
  prepareQuizbuks,
  takerWithPassword,
  test,
} from './support'

/*
 * One "continue" for signing in and signing up (Q113f-2, docs/quizbuks/plans/impl/Q113.md; platform decision D84):
 * the address, then a link by mail with the password as the other way on the same step, whatever the address. A new
 * address gives its name at the link before the account exists; a Taker with a password goes straight to it next time
 * on the same device (only the way is kept, never the address).
 */

test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('a new Taker continues with the address and finishes signing up at the link, with the texts in sight', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const email = address('continue-new')
  await page.goto('/taker/login?next=%2Fhistory')
  await page.getByTestId('continue-email').fill(email)
  await page.getByTestId('continue-form').locator('button[type="submit"]').click()
  await expect(page.getByTestId('continue-link-sent')).toContainText(email)
  await expect(page.getByTestId('continue-use-password')).toBeVisible()

  const mail = await mailTo(page.request, email, /link/i)
  const link = linkIn(mail.text, '/auth/email-link')
  expect(link.pathname).toBe('/taker/auth/email-link')
  expect(link.searchParams.get('to')).toBe('/taker/history')
  await page.goto(link.pathname + link.search)
  const finish = page.getByTestId('complete-sign-up')
  await expect(finish).toBeVisible()
  await expect(page.getByTestId('complete-sign-up-note')).toContainText(/accept these/i)
  await accessibleInBoth(page, 'app finish signing up')
  // A name is needed; nothing is opened without it (the link stays good).
  await finish.locator('button[type="submit"]').click()
  await expect(page.getByText('Enter a name')).toBeVisible()
  await page.getByTestId('complete-sign-up-name').fill('Nao')
  await finish.locator('button[type="submit"]').click()
  // Signed in: the welcome step first (country and terms), with the name given.
  await expect(page).toHaveURL(/\/welcome/)
  await expect(page.getByTestId('profile-name')).toHaveValue('Nao')
})

test('a Taker with a password goes straight to it on the device that used it, and a link is one click away', async ({
  page,
  request,
}) => {
  const email = await takerWithPassword(request, 'Rin')
  await page.goto('/taker/login')
  await page.getByTestId('continue-email').fill(email)
  await page.getByTestId('continue-form').locator('button[type="submit"]').click()
  await page.getByTestId('continue-use-password').click()
  await page.getByTestId('continue-password').fill(PASSWORD)
  await page.getByTestId('continue-password-step').locator('form button[type="submit"]').click()
  await expect(page).toHaveURL(/\/welcome/)

  // Signed out, the same device asks for the password without mailing a link.
  await page.evaluate(() => {
    for (const key of Object.keys(window.localStorage)) if (key.startsWith('jabiz.session.')) window.localStorage.removeItem(key)
  })
  await page.goto('/taker/login')
  await page.getByTestId('continue-email').fill(email)
  await page.getByTestId('continue-form').locator('button[type="submit"]').click()
  await expect(page.getByTestId('continue-password')).toBeVisible()
  await expect(page.getByTestId('continue-link-sent')).toHaveCount(0)
  expect(await page.evaluate(() => window.localStorage.getItem('jabiz.signInMethod.app'))).toBe('password')
  await page.getByTestId('continue-use-link').click()
  await expect(page.getByTestId('continue-link-sent')).toContainText(email)
})

test('the old sign-up address leads to the sign-in, and password registration is closed at the app', async ({
  page,
  request,
}) => {
  await page.goto('/taker/signup?next=%2Fhelp')
  await expect(page.getByTestId('continue-email')).toBeVisible()
  await expect(page).toHaveURL(/\/taker\/login\?next=%2Fhelp$/)
  const refused = await request.post('/api/auth/register', {
    data: { email: address('continue-pw'), password: PASSWORD, displayName: 'Sho', locale: 'en', entry: 'app' } })
  expect(refused.status()).toBe(422)
})
