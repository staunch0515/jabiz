import { readFileSync } from 'node:fs'
import { certificatesOf, stateOfVerifyPage, verifyPage } from './certificates'
import { query } from './content'
import { contentAdmin } from './legal'
import { decideAsOperator, operatorWithLead, reviewerFor } from './operators'
import { taker } from './play'
import { boundPublication, operatorTree, pass, platformMap } from './tree'
import { approvedSponsor } from './wallet'
import {
  accessibleInBoth, adminToken, expect, PASSWORD, prepareQuizbuks, run, signIn, test, tokenOf, unique,
} from './support'

/*
 * Node certificates (docs/quizbuks/plans/impl/F-S5.md, F-S5a and F-S5b; the first launch's loop, steps 5 and 6): a node
 * that names a certificate gives one the moment it is lit, with no end date. A Taker lights a node of the platform's map:
 * the certificate is in the app, its PDF is issued on the first download, anybody opens its verify page without signing in
 * (valid, with the day it was lit and no validity), the holder turns the link off (the page says only that) and back on,
 * and once the account is deleted the number finds nothing. A Sponsor of an operator lights the operator's copy of a
 * template on the "challenges" tab: the certificate is on the "certificates" tab of the Sponsor hub, and once the
 * lighting is revoked (four eyes) the verify page says so. The pages are checked by keyboard and with axe in both
 * appearances.
 *
 * The verify page is cached by the browser for up to a minute (`cacheSeconds` of `qb.public.certificate`), so every
 * look at it is in a browser context of its own, which has no cache.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

type Row = Record<string, unknown>

test('a Taker lights a node: the certificate is in the app, its PDF is issued on the first download, anybody verifies it, the holder turns the link off and on, and a deleted account leaves nothing',
  async ({ page, browser, baseURL, request }) => {
    test.setTimeout(420_000)
    const certificate = unique('Fair dealing certificate ')
    const map = await platformMap(request, 'TAKER', certificate)
    // The operator's own Sponsor binds a publication to the platform's map; the Taker passes it.
    const operator = await operatorWithLead(request, 'Cert')
    const publicationId = await boundPublication(request, operator.sponsorToken, map.treeId, map.node,
      unique('Certificate quiz '))
    const me = await taker(request, 'Mio')
    await pass(request, me.token, publicationId)
    const [issued] = await certificatesOf(request, me.token, 'QB_MY_CERTIFICATIONS')
    const key = String(issued.verifyKey)
    expect(issued).toMatchObject({ source: 'NODE', shared: true, revokedAt: null, runId: null, heldUntil: null })

    // The app lists it; nothing is "paused" any more.
    await signIn(page, '/taker/', me.email)
    await expect(page.getByTestId('home')).toBeVisible()
    await page.goto('/taker/account/certificates')
    const item = page.getByTestId('certificate-item').filter({ hasText: certificate })
    await expect(item).toBeVisible()
    await expect(page.getByTestId('certificates-paused')).toHaveCount(0)
    await expect(item.getByTestId('certificate-issuer')).toBeVisible()
    await expect(item.getByTestId('certificate-dates')).toContainText('Lit on')
    await expect(item.getByTestId('certificate-link')).toContainText(`/taker/verify/${key}`)
    await accessibleInBoth(page, 'app certificates')

    // The first download issues the PDF (from the keyboard); it is a PDF.
    const downloading = page.waitForEvent('download')
    await item.getByTestId('certificate-download').focus()
    await page.keyboard.press('Enter')
    const download = await downloading
    expect(download.suggestedFilename()).toMatch(/\.pdf$/i)
    expect(readFileSync(await download.path()!).subarray(0, 4).toString()).toBe('%PDF')
    // The row now has its document: it was issued once, by the holder.
    await expect.poll(async () => ((await run(request, me.token, 'QB_MY_CERTIFICATIONS', {}))
      .certificates as Row[])[0].runId, { message: 'the PDF is recorded', timeout: 30_000 }).not.toBeNull()

    // Anybody opens the verify page: valid, with the day it was lit and no validity row.
    const first = await verifyPage(browser, baseURL, key)
    await expect(first.visitor.getByTestId('verify-state')).toHaveText('Valid')
    await expect(first.visitor.getByTestId('verify-certification')).toHaveText(certificate)
    await expect(first.visitor.getByTestId('verify-lit')).toBeVisible()
    await expect(first.visitor.getByTestId('verify-held')).toHaveCount(0)
    await expect(first.visitor.getByText(/valid until|expires/i)).toHaveCount(0)
    await accessibleInBoth(first.visitor, 'certificate verify page')
    await first.close()

    // The holder turns the link off, with the keyboard: the page says only that.
    const toggle = item.getByTestId('certificate-share')
    await toggle.focus()
    await page.keyboard.press('Space')
    await expect(toggle).not.toBeChecked()
    await expect(item.getByTestId('certificate-share-hint')).toContainText('turned off')
    await expect(item.getByTestId('certificate-copy')).toHaveCount(0)
    await expect.poll(() => stateOfVerifyPage(browser, baseURL, key), { message: 'the page says it is turned off',
      timeout: 90_000 }).toBe('DISABLED')
    const off = await verifyPage(browser, baseURL, key)
    await expect(off.visitor.getByTestId('verify-state')).toHaveText('Link turned off')
    await expect(off.visitor.getByTestId('verify-disabled')).toBeVisible()
    await expect(off.visitor.getByTestId('verify-holder')).toHaveCount(0)
    await expect(off.visitor.getByText(certificate)).toHaveCount(0)
    await accessibleInBoth(off.visitor, 'certificate verify page, link turned off')
    await off.close()

    // Turned on again: the same number is valid.
    await toggle.focus()
    await page.keyboard.press('Space')
    await expect(toggle).toBeChecked()
    await expect.poll(() => stateOfVerifyPage(browser, baseURL, key), { message: 'the page is valid again',
      timeout: 90_000 }).toBe('VALID')

    // The account is deleted (an administrator schedules it; the tests' cooling-off is a second): the number finds nothing.
    const myId = String((await (await request.get('/api/auth/me', { headers: { Authorization: `Bearer ${me.token}` } }))
      .json()).userId)
    await run(request, await adminToken(request), 'SEC_USER_DELETION_SCHEDULE', { userId: myId })
    // The deletion job runs every minute here (app.env): not asked for by hand, which a scheduled run holding its lock
    // answers 409 JOB_BUSY, and which would only take the requests due by an earlier scheduled time anyway.
    await expect.poll(() => stateOfVerifyPage(browser, baseURL, key), {
      message: 'the certificate is gone with the account', timeout: 180_000, intervals: [5_000] }).toBe('NONE')
    const gone = await verifyPage(browser, baseURL, key)
    await expect(gone.visitor.getByText('No such certificate')).toBeVisible()
    await accessibleInBoth(gone.visitor, 'certificate verify page, no such certificate')
    await gone.close()
  })

test('a Sponsor lights an operator\'s copy on the challenges tab: the certificate is on the certificates tab, and a revoked lighting is revoked on its verify page',
  async ({ page, browser, baseURL, request }) => {
    test.setTimeout(420_000)
    const certificate = unique('Sponsor skills certificate ')
    const template = await platformMap(request, 'SPONSOR', certificate)
    const operator = await operatorWithLead(request, 'Cert')
    // The operator copies the template (the copy keeps its certificate); another member puts it in effect.
    const copy = await run(request, operator.operatorToken, 'QB_TREE_COPY', { templateTreeId: template.treeId })
    const submitted = await run(request, operator.operatorToken, 'QB_TREE_SUBMIT', { treeId: copy.treeId,
      baseRevision: copy.revision })
    await decideAsOperator(request, operator.sponsorToken, submitted.approvalRequestId, 'APPROVE')
    const reviewer = await reviewerFor(request, operator.sponsorToken)
    await expect.poll(async () => (await operatorTree(request, reviewer, String(copy.treeId)))?.currentVersionId ?? null,
      { message: 'the copy is in effect', timeout: 30_000 }).not.toBeNull()
    const publicationId = await boundPublication(request, operator.sponsorToken, String(copy.treeId), template.node,
      unique('Skills challenge '))

    // Another Sponsor of the operator answers the challenge (the keyboard answering is sponsor-challenge.spec's).
    const sponsor = await approvedSponsor(request, 'Chika')
    const profile = await (await request.get('/api/auth/me', { headers: { Authorization: `Bearer ${sponsor.token}` } }))
      .json()
    const hq = await tokenOf(request, await contentAdmin(request), PASSWORD, 'admin')
    await run(request, hq, 'QB_SPONSOR_OPERATOR_ASSIGN', { userId: profile.userId, operatorId: operator.operatorId,
      reason: 'Certificate test' })
    const token = await tokenOf(request, sponsor.email, PASSWORD, 'sponsor')
    const started = await run(request, token, 'QB_SPONSOR_ATTEMPT_START', { publicationId })
    const questions = started.questions as { no: number; options: { no: number; text: string }[] }[]
    await run(request, token, 'QB_SPONSOR_ATTEMPT_SUBMIT', { attemptId: started.attemptId, publicationId,
      answers: questions.map((q) => ({ questionNo: q.no,
        selected: q.options.filter((o) => o.text === 'Paris').map((o) => o.no) })) })
    const [issued] = await certificatesOf(request, token, 'QB_SPONSOR_MY_CERTIFICATES')
    const key = String(issued.verifyKey)
    // The operator's name is the issuer (the copy is the operator's).
    expect(String(issued.issuerName)).toContain('operator')

    // The certificates tab of the Sponsor hub.
    await signIn(page, '/sponsor/', sponsor.email)
    await expect(page.getByTestId('dashboard-status')).toBeVisible()
    await page.goto('/sponsor/profile')
    await page.getByTestId('page-tab-certificates').focus()
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL(/\/profile\/certificates$/)
    const item = page.getByTestId('certificate-item').filter({ hasText: certificate })
    await expect(item).toBeVisible()
    await expect(item.getByTestId('certificate-issuer')).toContainText('operator')
    await expect(item.getByTestId('certificate-link')).toContainText(`/taker/verify/${key}`)
    await accessibleInBoth(page, 'sponsor certificates')
    const downloading = page.waitForEvent('download')
    await item.getByTestId('certificate-download').focus()
    await page.keyboard.press('Enter')
    expect(readFileSync(await (await downloading).path()!).subarray(0, 4).toString()).toBe('%PDF')
    expect(await stateOfVerifyPage(browser, baseURL, key)).toBe('VALID')

    // Revoked (another administrator approves): the verify page says so; the Sponsor's list marks it.
    const revoker = await tokenOf(request, await contentAdmin(request, 'Revoker'), PASSWORD, 'admin')
    const [row] = await query(request, revoker, 'qb.admin.certificates', { userId: profile.userId })
    const asked = await run(request, revoker, 'QB_TREE_LIT_REVOKE', { litId: row.litId, reason: 'Answers shared' })
    await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: asked.approvalRequestId,
      decision: 'APPROVE' })
    await expect.poll(() => stateOfVerifyPage(browser, baseURL, key), { message: 'the verify page says revoked',
      timeout: 90_000 }).toBe('REVOKED')
    const revoked = await verifyPage(browser, baseURL, key)
    await expect(revoked.visitor.getByTestId('verify-state')).toHaveText('Revoked')
    await accessibleInBoth(revoked.visitor, 'certificate verify page, revoked')
    await revoked.close()
    await page.reload()
    const marked = page.getByTestId('certificate-item').filter({ hasText: certificate })
    await expect(marked.getByTestId('certificate-revoked')).toBeVisible()
    await expect(marked.getByTestId('certificate-share')).toHaveCount(0)
    await accessibleInBoth(page, 'sponsor certificates, revoked')
  })

test('the public pages say there is nothing to verify for an unknown number, and the public certification page lists nothing without a certificate',
  async ({ page, browser, baseURL, request }) => {
    test.setTimeout(180_000)
    const me = await taker(request, 'Mei')
    await signIn(page, '/taker/', me.email)
    await expect(page.getByTestId('home')).toBeVisible()
    await page.goto('/taker/account/certificates')
    await expect(page.getByTestId('certificates-none')).toBeVisible()
    await accessibleInBoth(page, 'app certificates, none')

    // The public page: a name of the taker's own, then on; with no certificate it is not available.
    const handle = unique('cert-').toLowerCase().replace(/[^a-z0-9-]/g, '-').slice(0, 30).replace(/-+$/, '')
    await page.getByTestId('certificates-handle').fill(handle)
    await page.getByTestId('certificates-handle-save').click()
    const on = page.getByTestId('certificates-page-on')
    await expect(on).toBeEnabled()
    await on.focus()
    await page.keyboard.press('Space')
    await expect(page.getByTestId('certificates-page-link')).toBeVisible()
    const context = await browser.newContext({ baseURL })
    const visitor = await context.newPage()
    await visitor.goto(`/taker/u/${handle}`)
    await expect(visitor.getByText('This page is not available')).toBeVisible()
    await accessibleInBoth(visitor, 'public certification page')
    await visitor.goto(`/taker/verify/${'0'.repeat(32)}`)
    await expect(visitor.getByText('No such certificate')).toBeVisible()
    await accessibleInBoth(visitor, 'certificate verify page, unknown number')
    await context.close()
    await on.focus()
    await page.keyboard.press('Space')
    await expect(page.getByTestId('certificates-page-link')).toBeHidden()
  })
