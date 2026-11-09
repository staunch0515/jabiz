import { expect, expectAccessible, prepareQuizbuks, signIn, test, unique } from './support'
import { taker } from './play'

/*
 * Certificates (docs/quizbuks/plans/Q77-certificates.md). F-S2c (D8, the requester's answer of 2026-11-08): no new
 * certificate is issued until the knowledge map's certificates (F-S5), and F-S2c2b (D1) removed the certifications: a
 * Taker's account lists the certificates still valid (none here) with the note that issuing is paused; the public
 * certification page still takes a name of the Taker's own and turns on, and shows "not available" while it lists no
 * certificate; an unknown key verifies nothing. Certificates issued before stay verifiable and downloadable
 * (CertificateIT).
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('a Taker sees issuing paused, and the public pages show nothing to verify', async ({ page, browser,
  baseURL, request }) => {
  test.setTimeout(180_000)
  const name = unique('Cert ')
  const me = await taker(request, name)

  await signIn(page, '/taker/', me.email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto('/taker/account/certificates')
  await expect(page.getByTestId('certificates-none')).toBeVisible()
  await expect(page.getByTestId('certificates-paused')).toContainText('not issued for now')
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
