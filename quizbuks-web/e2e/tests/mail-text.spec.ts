import type { APIRequestContext } from '@playwright/test'
import { adminOfRole, contentAdmin } from './legal'
import { approvedSponsor } from './publishing'
import { signInAdmin } from './admin'
import { expect, expectAccessible, mailTo, PASSWORD, prepareQuizbuks, run, setAppearance, test, tokenOf } from './support'

/*
 * The texts of the mail (F-C8, platform decision D72): a content administrator changes an ordinary notice and the next
 * mail has the new wording, then returns it to the default at once; the verification mail, which carries a token,
 * changes only when another administrator publishes what the first proposed. The roles are the tests' own, with the
 * permissions QbRoles gives the content and the finance administrators (checked by QbRolesTest and MailTextIT).
 */

const PUBLISHER_ROLE = 'QB_E2E_PUBLISHER'
const TEXTS = '/api/mail/texts'

type English = { defaultSubject: string; defaultBody: string; versionNo: number | null; state: string }

async function english(request: APIRequestContext, token: string, template: string): Promise<English> {
  const response = await request.get(TEXTS, { headers: { Authorization: `Bearer ${token}` } })
  expect(response.status(), await response.text()).toBe(200)
  const found = ((await response.json()).templates as { template: string; languages: (English & { locale: string })[] }[])
    .find((candidate) => candidate.template === template)
  const text = found?.languages.find((language) => language.locale === 'en')
  expect(text, `${template} in English`).toBeTruthy()
  return text!
}

async function reset(request: APIRequestContext, token: string, template: string) {
  const current = await english(request, token, template)
  if (current.state === 'DEFAULT') return
  await run(request, token, 'MAIL_TEXT_RESET', { template, locale: 'en', baseVersionNo: current.versionNo })
}

test('a content administrator changes an ordinary notice, the next mail has the new wording, and the default returns at once', async ({
  request,
}) => {
  await prepareQuizbuks(request)
  const editor = await tokenOf(request, await contentAdmin(request, 'Lena'), PASSWORD, 'admin')
  const template = 'qb.sponsor.approved'
  await reset(request, editor, template)
  const current = await english(request, editor, template)

  const marker = `E2E wording ${Date.now()}`
  await run(request, editor, 'MAIL_TEXT_SAVE', { template, locale: 'en', subject: `${marker}: ${current.defaultSubject}`,
    body: `${current.defaultBody}\n\n${marker}`, baseVersionNo: current.versionNo })
  try {
    const sponsor = await approvedSponsor(request, 'Mia')
    const mail = await mailTo(request, sponsor.email, new RegExp(marker))
    expect(mail.subject).toContain(marker)
    expect(mail.text).toContain(marker)
  } finally {
    await reset(request, editor, template)
  }
  const later = await approvedSponsor(request, 'Noa')
  const plain = await mailTo(request, later.email, /approved/i)
  expect(plain.subject).not.toContain(marker)
  expect(plain.text).not.toContain(marker)
})

test('the verification mail changes only when another administrator publishes what the first proposed', async ({
  request,
}) => {
  await prepareQuizbuks(request)
  const proposer = await tokenOf(request, await contentAdmin(request, 'Lena'), PASSWORD, 'admin')
  const publisher = await tokenOf(request, await adminOfRole(request, 'Pavel', PUBLISHER_ROLE, 'E2E publisher',
    ['control.publish', 'mail.text.read', 'mail.text.write']), PASSWORD, 'admin')
  const template = 'jabiz.auth.verify-email'
  await reset(request, proposer, template)
  const current = await english(request, proposer, template)

  // It carries a token: a plain save is refused, and a proposal changes nothing until another administrator publishes.
  const marker = `E2E verify ${Date.now()}`
  const subject = `${marker}: ${current.defaultSubject}`
  const refused = await request.post('/api/processes/MAIL_TEXT_SAVE/latest', {
    headers: { Authorization: `Bearer ${proposer}` },
    data: { template, locale: 'en', subject, body: current.defaultBody, baseVersionNo: current.versionNo } })
  expect(refused.status()).toBe(422)
  expect(await refused.text()).toContain('MAIL_TEXT_NEEDS_APPROVAL')
  const change = await run(request, proposer, 'CONTROL_CHANGE_PROPOSE', { targetEntity: 'MailText',
    values: { template, locale: 'en', subject, body: current.defaultBody }, reason: 'End-to-end test' })
  // A mail that goes out while the proposal waits is still on the default text.
  const waiting = `verify-waiting-${Date.now()}@example.com`
  const early = await request.post('/api/auth/register', {
    data: { email: waiting, password: PASSWORD, displayName: 'Quinn', locale: 'en', entry: 'sponsor' } })
  expect(early.status(), await early.text()).toBe(202)
  const first = await mailTo(request, waiting, /verify|confirm|address/i)
  expect(first.subject).not.toContain(marker)
  expect(first.text).not.toContain(marker)
  expect((await english(request, proposer, template)).state).toBe('DEFAULT')

  await run(request, publisher, 'CONTROL_CHANGE_PUBLISH', { changeId: change.changeId })
  try {
    expect((await english(request, proposer, template)).state).toBe('CUSTOM')
    const email = `verify-${Date.now()}@example.com`
    const registered = await request.post('/api/auth/register', {
      data: { email, password: PASSWORD, displayName: 'Rae', locale: 'en', entry: 'sponsor' } })
    expect(registered.status(), await registered.text()).toBe(202)
    const mail = await mailTo(request, email, new RegExp(marker))
    expect(mail.subject).toContain(marker)
  } finally {
    await reset(request, proposer, template)
  }
})

// The platform's page of the mail texts in the administration, in both appearances.
for (const appearance of ['light', 'dark'] as const) {
  test(`the mail texts page passes axe (${appearance})`, async ({ page }) => {
    await setAppearance(page, appearance)
    await page.setViewportSize({ width: 1280, height: 900 })
    await signInAdmin(page)
    await page.goto('/admin/mail/texts')
    await expect(page.getByTestId(/^mail-template-/).first()).toBeVisible()
    await expectAccessible(page, appearance === 'light' ? 'admin mail texts' : 'admin mail texts (dark)')
  })
}
