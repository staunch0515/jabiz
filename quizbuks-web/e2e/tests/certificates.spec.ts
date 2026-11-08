import { expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique } from './support'
import { operatorWithLead } from './operators'
import { taker } from './play'

/*
 * Certificates (docs/quizbuks/plans/Q77-certificates.md). F-S2c (D8, the requester's answer of 2026-11-08): no new
 * certificate is issued until the knowledge map's certificates (F-S5): a taker who holds a certification sees it in the
 * app's account with the note, and no button to issue; the public certification page still takes a name of the
 * taker's own and turns on, and shows "not available" while it lists no certificate; an unknown key verifies nothing.
 * Certificates issued before stay verifiable and downloadable (CertificateIT). A grant by hand stands in for passing a
 * round (the rounds are Q69's tests).
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('a holder sees issuing paused, and the public pages show nothing to verify', async ({ page, browser,
  baseURL, request }) => {
  test.setTimeout(180_000)
  const name = unique('Cert ')
  const me = await taker(request, name)
  const userId = String((await (await request.get('/api/auth/me', {
    headers: { Authorization: `Bearer ${me.token}` } })).json()).userId)
  // F-S1: certifications are an operator's: one of the tests' own grants it.
  const granter = (await operatorWithLead(request, 'Gra')).operatorToken
  await run(request, granter, 'QB_CERTIFICATION_GRANT', { userId, certification: 'SPONSOR_APPLY',
    reason: 'End-to-end test' })

  await signIn(page, '/taker/', me.email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto('/taker/account/certificates')
  const item = page.getByTestId('certificate-SPONSOR_APPLY')
  await expect(item).toContainText('Sponsor Certification')
  await expect(item.getByTestId('certificate-paused-SPONSOR_APPLY')).toContainText('not issued for now')
  await expect(item.getByTestId('certificate-issue-SPONSOR_APPLY')).toHaveCount(0)
  await expectAccessible(page, 'app certificates')

  // The public page: a name of the taker's own, then on.
  const handle = unique('cert-').toLowerCase().replace(/[^a-z0-9-]/g, '-').slice(0, 30).replace(/-+$/, '')
  await page.getByTestId('certificates-handle').fill(handle)
  await page.getByTestId('certificates-handle-save').click()
  const on = page.getByTestId('certificates-page-on')
  await expect(on).toBeEnabled()
  await on.click()
  await expect(page.getByTestId('certificates-page-link')).toBeVisible()

  // Anybody, signed out: no certificate is listed, so the page is not available; an unknown key verifies nothing.
  // A context of its own (signed out; axe needs a page of a context, not of the bare browser), at the tests' address.
  const visitorContext = await browser.newContext({ baseURL })
  const visitor = await visitorContext.newPage()
  await visitor.goto(`/taker/u/${handle}`)
  await expect(visitor.getByText('This page is not available')).toBeVisible()
  await expectAccessible(visitor, 'public certification page')
  await visitor.goto(`/taker/verify/${'0'.repeat(32)}`)
  await expect(visitor.getByText('No such certificate')).toBeVisible()
  await expectAccessible(visitor, 'certificate verify page')
  await visitorContext.close()

  // Off again.
  await on.click()
  await expect(page.getByTestId('certificates-page-link')).toBeHidden()
})
