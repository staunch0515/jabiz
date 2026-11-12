import { approvedSponsor } from './publishing'
import { adminOfRole, contentAdmin } from './legal'
import { loadBrand } from '../../brands/brand.mjs'
import { signInAdmin } from './admin'
import { expect, expectAccessible, mailTo, PASSWORD, prepareQuizbuks, run, setAppearance, test, tokenOf } from './support'

/*
 * The letterhead of every mail (F-C9, platform decision D72 item 6): a content administrator proposes one, another
 * administrator (who holds control.publish and mail.text.write) publishes it, the next mail is sent on it, and
 * returning to the default takes effect at once. The roles are the tests' own, with the permissions QbRoles gives the
 * content and the finance administrators; the production roles are checked by QbRolesTest and MailLayoutIT.
 */

// The brand the build chose: its English name heads the default letterhead and its support address is in the footer.
const BRAND = loadBrand()
const PUBLISHER_ROLE = 'QB_E2E_PUBLISHER'
const LAYOUTS = '/api/mail/layouts'

async function layoutVersion(request: import('@playwright/test').APIRequestContext, token: string): Promise<number | null> {
  const response = await request.get(LAYOUTS, { headers: { Authorization: `Bearer ${token}` } })
  expect(response.status(), await response.text()).toBe(200)
  const english = ((await response.json()).languages as { locale: string; versionNo: number | null }[])
    .find((language) => language.locale === 'en')
  return english?.versionNo ?? null
}

test('a letterhead one administrator proposes and another publishes goes on the next mail, and the default returns at once', async ({
  request,
}) => {
  await prepareQuizbuks(request)
  const proposerEmail = await contentAdmin(request, 'Lena')
  const proposer = await tokenOf(request, proposerEmail, PASSWORD, 'admin')
  const publisherEmail = await adminOfRole(request, 'Pavel', PUBLISHER_ROLE, 'E2E publisher',
    ['control.publish', 'mail.text.read', 'mail.text.write'])
  const publisher = await tokenOf(request, publisherEmail, PASSWORD, 'admin')

  // Before: a mail comes on the default letterhead, the brand's.
  const before = await approvedSponsor(request, 'Mia')
  const first = await mailTo(request, before.email, /approved/i)
  expect(first.text).toContain(BRAND.names.en)
  expect(first.text).toContain(BRAND.supportEmail)
  expect(first.text).not.toContain('E2E letterhead')

  const displayName = `E2E letterhead ${Date.now()}`
  const change = await run(request, proposer, 'CONTROL_CHANGE_PROPOSE', { targetEntity: 'MailLayout',
    values: { locale: 'en', displayName, logoUrl: '', logoAlt: '', footer: 'Sent on the end-to-end letterhead' },
    reason: 'End-to-end test' })
  // The proposer cannot publish their own proposal: the content administrator holds no control.publish.
  const refused = await request.post('/api/processes/CONTROL_CHANGE_PUBLISH/latest', {
    headers: { Authorization: `Bearer ${proposer}` }, data: { changeId: change.changeId } })
  expect(refused.status()).toBe(403)
  await run(request, publisher, 'CONTROL_CHANGE_PUBLISH', { changeId: change.changeId })

  try {
    const after = await approvedSponsor(request, 'Noa')
    const mail = await mailTo(request, after.email, /approved/i)
    expect(mail.text).toContain(displayName)
    expect(mail.text).toContain('Sent on the end-to-end letterhead')
  } finally {
    // Back to the default, by the content administrator alone and at once (the database outlives the run).
    await run(request, proposer, 'MAIL_LAYOUT_RESET', { locale: 'en', baseVersionNo: await layoutVersion(request, proposer) })
  }
  const last = await approvedSponsor(request, 'Omar')
  const reset = await mailTo(request, last.email, /approved/i)
  expect(reset.text).not.toContain(displayName)
  expect(reset.text).toContain(BRAND.names.en)
  expect(reset.text).toContain(BRAND.supportEmail)
})

// The platform's letterhead page in the administration, with the brand's default letterhead in it, in both appearances.
for (const appearance of ['light', 'dark'] as const) {
  test(`the letterhead page passes axe (${appearance})`, async ({ page }) => {
    await setAppearance(page, appearance)
    await page.setViewportSize({ width: 1280, height: 900 })
    await signInAdmin(page)
    await page.goto('/admin/mail/layout')
    await expect(page.getByTestId('mail-layout-displayName')).not.toHaveValue('')
    await expectAccessible(page, appearance === 'light' ? 'admin mail layout' : 'admin mail layout (dark)')
  })
}
