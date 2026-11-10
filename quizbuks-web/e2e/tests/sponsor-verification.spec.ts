import { query } from './content'
import { reviewerFor } from './operators'
import { approvedSponsor } from './wallet'
import {
  expect, expectAccessible, linkIn, mailTo, prepareQuizbuks, run, signIn, test, unique,
} from './support'

/*
 * F-M3 (docs/quizbuks/first-launch-spec.md F-M3, plans/impl/F-M.md §6): a Sponsor verifies their company by its mail
 * domain. Their account's address is not on it, so a one-time link goes to an address on the domain; opened while
 * signed in, the verification goes to the review of their operator, whose other member approves it; the page then says
 * the company is verified. A public mail service's domain is refused on the page.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('a Sponsor verifies their company through a link to its domain, and their operator approves it',
  async ({ page, request }) => {
    test.setTimeout(180_000)
    const sponsor = await approvedSponsor(request, 'Kana')
    const domain = `${unique('acme-').toLowerCase().replace(/[^a-z0-9-]/g, '')}.example`
    const address = `info@${domain}`

    await signIn(page, '/sponsor/', sponsor.email)
    // Signed in once the home is drawn (the session is kept only then).
    await expect(page.getByTestId('dashboard-status')).toBeVisible()
    await page.goto('/sponsor/profile/verification')
    const form = page.getByTestId('verification-form')
    await expect(form).toBeVisible()
    await expectAccessible(page, 'sponsor company verification')

    // A public mail service's domain proves nothing: refused, by its field.
    await page.getByTestId('verification-company').fill('Acme K.K.')
    await page.getByTestId('verification-domain').fill('gmail.com')
    await page.getByTestId('verification-address').fill('boss@gmail.com')
    await page.getByTestId('verification-submit').click()
    await expect(page.getByTestId('verification-domain')).toHaveAttribute('aria-invalid', 'true')

    await page.getByTestId('verification-domain').fill(domain)
    await page.getByTestId('verification-address').fill(address)
    await page.getByTestId('verification-submit').click()
    await expect(page.getByTestId('verification-status')).toHaveAttribute('data-status', 'LINK_SENT')
    await expect(page.getByTestId('verification-link')).toContainText(address)

    // The link in the mail to the company's address, opened in the Sponsor's session.
    const mail = await mailTo(request, address, /Confirm that/)
    const link = linkIn(mail.text, '/profile/verification/confirm')
    await page.goto(link.pathname + link.search)
    await expect(page.getByTestId('verification-confirmed')).toBeVisible()

    // Another member of the Sponsor's operator approves it.
    const reviewer = await reviewerFor(request, sponsor.token)
    const [row] = await query(request, reviewer, 'qb.admin.sponsor-verifications', { domain })
    expect(row).toMatchObject({ status: 'REVIEW', method: 'LINK', confirmAddress: address })
    await run(request, reviewer, 'APPROVAL_DECIDE', { requestId: row.approvalRequestId, decision: 'APPROVE' })
    await expect.poll(async () => (await query(request, reviewer, 'qb.admin.sponsor-verifications', { domain }))[0]
      ?.status, { message: 'the verification is approved', timeout: 30_000 }).toBe('APPROVED')

    await page.goto('/sponsor/profile/verification')
    await expect(page.getByTestId('verification-status')).toHaveAttribute('data-status', 'APPROVED')
    await expect(page.getByTestId('verification-status')).toContainText(domain)
    await expectAccessible(page, 'sponsor company verified')
  })
