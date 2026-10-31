import { completeQuiz } from './content'
import { fundedSponsor } from './play'
import { livePublication } from './publishing'
import {
  expect,
  expectAccessible,
  linkIn,
  mailTo,
  PASSWORD,
  prepareQuizbuks,
  run,
  signIn,
  test,
  tokenOf,
  unique,
} from './support'

/*
 * A sponsor in the taker app (Q68, docs/quizbuks/plans/Q68-sponsor-in-app.md): a sponsor who also uses the app (the
 * same address, joined at the app's entry) finds their own account from the header, reads the figures of their
 * publication on, and pauses and resumes it; nothing that moves Kudos is there.
 */

test.beforeAll(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('a sponsor reads their publication in the app and pauses and resumes it', async ({ page, request }) => {
  const sponsor = await fundedSponsor(request)
  const quiz = await completeQuiz(request, sponsor.token, { title: unique('In-app quiz ') })
  const name = unique('In the app ')
  await livePublication(request, sponsor.token, quiz.quizId, name)

  // The same address registers at the app: the mail of an address in use joins it to the app's entry.
  const registered = await request.post('/api/auth/register', {
    data: { email: sponsor.email, password: PASSWORD, displayName: 'Sora', locale: 'en', entry: 'app' },
  })
  expect(registered.status(), await registered.text()).toBe(202)
  const mail = await mailTo(request, sponsor.email, /already have an account/i)
  const token = linkIn(mail.text, '/auth/join').searchParams.get('token')!
  const joined = await request.post('/api/auth/join', { data: { token } })
  expect(joined.status(), await joined.text()).toBe(204)
  const app = await tokenOf(request, sponsor.email, PASSWORD, 'app')
  await run(request, app, 'QB_PROFILE_SAVE', { displayName: 'Sora', countryCode: 'JP', preferredCurrency: 'JPY' })

  await page.setViewportSize({ width: 390, height: 844 })
  await signIn(page, '/taker/', sponsor.email)
  await expect(page.getByTestId('home')).toBeVisible()
  await page.getByTestId('header-my-sponsor').click()
  await expect(page).toHaveURL(/\/my-sponsor$/)
  await expect(page.getByTestId('my-sponsor-status')).toHaveAttribute('data-status', 'APPROVED')
  await expect(page.getByTestId('my-sponsor-figures')).toBeVisible()
  const row = page.getByTestId('my-sponsor-publication').filter({ hasText: name })
  await expect(row).toHaveAttribute('data-status', 'LIVE')
  await expect(row.getByTestId('my-sponsor-counts')).toContainText('Answered: 0')
  await expectAccessible(page, 'app sponsor page')

  await row.getByTestId('my-sponsor-pause').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Pause' }).click()
  await expect(row).toHaveAttribute('data-status', 'PAUSED')
  await row.getByTestId('my-sponsor-resume').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Resume' }).click()
  await expect(row).toHaveAttribute('data-status', 'LIVE')

  // What moves Kudos stays in the console: the app's sign-in may not top up.
  const topUp = await request.post('/api/processes/QB_TOPUP_START/latest', {
    headers: { Authorization: `Bearer ${app}` }, data: { amount: 1000 },
  })
  expect(topUp.status()).toBe(403)
})
