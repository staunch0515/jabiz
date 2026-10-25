import { expect, expectAccessible, mailTo, MERCHANT_MAIL, prepareQuizbuks, signIn, test } from './support'
import { approvedSponsor } from './publishing'

/**
 * The sponsor's own mail server (docs/quizbuks/plans/Q29-merchant-mail-server.md): the console page saves the SMTP
 * settings and sends a test mail through them to the sponsor's address, shows the server verified, or the server's
 * answer when nothing left; invitations are refused until a server is verified (invitation.spec.ts sends them).
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('a sponsor sets up the mail server: the test mail arrives, a wrong port is reported', async ({ page, request }) => {
  test.setTimeout(180_000)
  const sponsor = await approvedSponsor(request, 'Mia')
  await signIn(page, '/sponsor/', sponsor.email)
  await expect(page.getByTestId('dashboard-status')).toBeVisible()
  await page.getByRole('link', { name: 'Mail server' }).click()
  await expect(page.getByTestId('mail-server-status')).toHaveAttribute('data-status', 'NONE')
  await expectAccessible(page, 'mail server, empty')

  // Nothing listens on the port: the server is saved with the connection's answer, not verified.
  await page.getByTestId('mail-server-host').fill('localhost')
  await page.getByTestId('mail-server-port').fill(String(MERCHANT_MAIL.smtpPort + 1000))
  await page.getByTestId('mail-server-username').fill('mia')
  await page.getByTestId('mail-server-password').fill('mia-mail-password-1')
  await page.getByTestId('mail-server-from').fill('mia@example.com')
  await page.getByTestId('mail-server-from-name').fill('Mia Co')
  await page.getByTestId('mail-server-save').click()
  await expect(page.getByTestId('mail-server-result')).toHaveAttribute('data-verified', 'false', { timeout: 60_000 })
  await expect(page.getByTestId('mail-server-status')).toHaveAttribute('data-status', 'FAILED')
  await expectAccessible(page, 'mail server, refused')

  // The right port: the test mail leaves through the server to the sponsor's own address, in the sponsor's name.
  await page.getByTestId('mail-server-port').fill(String(MERCHANT_MAIL.smtpPort))
  // The password is kept from the first save: the field stays blank.
  await expect(page.getByTestId('mail-server-password')).toHaveValue('')
  await page.getByTestId('mail-server-save').click()
  await expect(page.getByTestId('mail-server-result')).toHaveAttribute('data-verified', 'true', { timeout: 60_000 })
  await expect(page.getByTestId('mail-server-result')).toContainText(sponsor.email)
  await expect(page.getByTestId('mail-server-status')).toHaveAttribute('data-status', 'VERIFIED')
  const mail = await mailTo(request, sponsor.email, /mail server/i, MERCHANT_MAIL.api)
  expect(mail.subject).toContain('localhost')
  await expectAccessible(page, 'mail server, verified')

  // Removed: back to none.
  await page.getByTestId('mail-server-remove').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Remove the mail server' }).click()
  await expect(page.getByTestId('mail-server-status')).toHaveAttribute('data-status', 'NONE')
})
