import { adminToken, expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique } from './support'
import { taker } from './play'

/*
 * Certificates (docs/quizbuks/plans/Q77-certificates.md): a taker who holds a certification issues its certificate in
 * the app's account, downloads the PDF and copies its link; anybody opens the link and sees it valid; the taker turns
 * the public certification page on under a name of their own, and anybody sees the certificate there. A grant by hand
 * stands in for passing a round (the rounds are Q69's tests).
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('a holder issues a certificate, anybody verifies it and sees it on the public page', async ({ page, browser,
  request }) => {
  test.setTimeout(180_000)
  const name = unique('Cert ')
  const me = await taker(request, name)
  const userId = String((await (await request.get('/api/auth/me', {
    headers: { Authorization: `Bearer ${me.token}` } })).json()).userId)
  await run(request, await adminToken(request), 'QB_CERTIFICATION_GRANT', { userId, certification: 'SPONSOR_APPLY',
    reason: 'End-to-end test' })

  await signIn(page, '/taker/', me.email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto('/taker/account/certificates')
  const item = page.getByTestId('certificate-SPONSOR_APPLY')
  await expect(item).toContainText('Sponsor Certification')
  await expectAccessible(page, 'app certificates')
  await item.getByTestId('certificate-issue-SPONSOR_APPLY').click()
  const link = item.getByTestId('certificate-link-SPONSOR_APPLY')
  await expect(link).toContainText('/taker/verify/')
  const address = (await link.textContent())!.trim()

  const downloading = page.waitForEvent('download')
  await item.getByTestId('certificate-download-SPONSOR_APPLY').click()
  const pdf = await downloading
  expect(pdf.suggestedFilename()).toMatch(/\.pdf$/)

  // The public page: a name of the taker's own, then on.
  const handle = unique('cert-').toLowerCase().replace(/[^a-z0-9-]/g, '-').slice(0, 30).replace(/-+$/, '')
  await page.getByTestId('certificates-handle').fill(handle)
  await page.getByTestId('certificates-handle-save').click()
  const on = page.getByTestId('certificates-page-on')
  await expect(on).toBeEnabled()
  await on.click()
  await expect(page.getByTestId('certificates-page-link')).toBeVisible()

  // Anybody, signed out: the verify page and the public page.
  const visitor = await browser.newPage()
  await visitor.goto(address)
  await expect(visitor.getByTestId('verify-state')).toHaveText('Valid')
  await expect(visitor.getByTestId('verify-holder')).toHaveText(name)
  await expectAccessible(visitor, 'certificate verify page')
  await visitor.goto(`/taker/u/${handle}`)
  await expect(visitor.getByTestId('taker-page-name')).toHaveText(name)
  await expect(visitor.getByTestId('taker-page-SPONSOR_APPLY')).toBeVisible()
  await expectAccessible(visitor, 'public certification page')
  await visitor.getByTestId('taker-page-SPONSOR_APPLY').click()
  await expect(visitor.getByTestId('verify-state')).toHaveText('Valid')

  // Off again: the page is as unknown.
  await on.click()
  await expect(page.getByTestId('certificates-page-link')).toBeHidden()
  await visitor.goto(`/taker/u/${handle}`)
  await expect(visitor.getByText('This page is not available')).toBeVisible()
  await visitor.close()
})
