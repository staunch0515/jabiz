import { expectAccessible as expectAccessibleWith, setAppearance } from '@jabiz/ui/testing/playwright'
import { test as base, expect, type APIRequestContext, type Page } from '@playwright/test'

/**
 * Playwright's test, failing any test during which a page reported a Content-Security-Policy violation: the server
 * sends each SPA's policy (docs/design/17-apps-and-branches.md section 3.2) and the pages must work within it.
 */
export const test = base.extend<{ contentSecurityPolicy: void }>({
  contentSecurityPolicy: [
    async ({ page }, use) => {
      const violations: string[] = []
      page.on('console', (message) => {
        if (message.type() === 'error' && message.text().includes('Content Security Policy')) {
          violations.push(message.text())
        }
      })
      await use()
      expect(violations, 'Content-Security-Policy violations').toEqual([])
    },
    { auto: true },
  ],
})

export { expect }

/** The first administrator (JABIZ_BOOTSTRAP_ADMIN_* on the server). */
export const ADMIN = {
  userName: process.env.E2E_ADMIN_USER ?? 'admin',
  password: process.env.E2E_ADMIN_PASSWORD ?? 'admin-password-1',
}

/** Where the server's mail is caught (Mailpit's API). */
const MAILPIT = (process.env.E2E_MAILPIT_URL ?? 'http://localhost:8025').replace(/\/+$/, '')

/**
 * The sponsors' own mail server of the tests (Q29): a Mailpit offering STARTTLS and taking any login, on this machine
 * (the application trusts a server on this machine only, JABIZ_MAIL_SENDERS_TRUST_ALL_FOR_TESTS). The console's form
 * names its SMTP port; the mail it receives is read through its API.
 */
export const MERCHANT_MAIL = {
  smtpPort: Number(process.env.E2E_MERCHANT_SMTP_PORT ?? '1026'),
  api: (process.env.E2E_MERCHANT_MAILPIT_URL ?? 'http://localhost:8026').replace(/\/+$/, ''),
}

/** The password of every account the tests register. */
export const PASSWORD = 'quizbuks-e2e-password-1'

/** A name no earlier run used: the tables only grow. */
export function unique(prefix: string): string {
  return `${prefix}${Date.now().toString(36)}${Math.floor(Math.random() * 36 ** 3).toString(36)}`
}

/** A fresh address of the tests' own. */
export function address(prefix: string): string {
  return `${unique(prefix)}@example.com`.toLowerCase()
}

/** Signs in at an entry through the API; returns the access token. */
export async function tokenOf(request: APIRequestContext, userName: string, password: string, entry = 'admin') {
  const response = await request.post('/api/auth/login', { data: { userName, password, entry } })
  expect(response.status(), await response.text()).toBe(200)
  return (await response.json()).accessToken as string
}

export const adminToken = (request: APIRequestContext) => tokenOf(request, ADMIN.userName, ADMIN.password)

const APP_KINDS = ['TERMS_APP', 'PRIVACY']
const SPONSOR_KINDS = ['TERMS_SPONSOR', 'PRIVACY']

/**
 * What a person accepts with a process (Q7b): registration's second step the takers' terms and the privacy policy,
 * an application the sponsors' terms and the privacy policy, at the versions in effect (as the pages send them).
 */
const ACCEPTED_WITH: Record<string, string[]> = { QB_PROFILE_SAVE: APP_KINDS, QB_SPONSOR_APPLY: SPONSOR_KINDS }

/** The money and contract actions the server refuses until the texts in effect are accepted (Q7b, Q7-2). */
const GATED: Record<string, string[]> = { QB_TOPUP_START: SPONSOR_KINDS, QB_PUBLICATION_SUBMIT: SPONSOR_KINDS,
  QB_PAYOUT_ONBOARD: APP_KINDS }

/** The caller's standing with the kinds (`qb.me.consents`): a row per kind in effect. */
export async function consentsOf(request: APIRequestContext, token: string, kinds: string[]) {
  const response = await request.post('/api/queries/qb.me.consents', {
    headers: { Authorization: `Bearer ${token}` },
    data: { params: { kinds, at: new Date().toISOString() } },
  })
  expect(response.status(), await response.text()).toBe(200)
  return ((await response.json()).items ?? []) as { kind: string; versionNo: number; mustAccept: boolean }[]
}

/**
 * Accepts the texts in effect the caller has not accepted yet, as the prompt after signing in does: the tests that
 * are not about consent act as people who accepted (a run may publish a new version between two tests).
 */
export async function acceptDue(request: APIRequestContext, token: string, kinds: string[]) {
  const due = (await consentsOf(request, token, kinds)).filter((row) => row.mustAccept)
  if (due.length === 0) return
  const response = await request.post('/api/processes/QB_CONSENT_ACCEPT/latest', {
    headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': crypto.randomUUID() },
    data: { items: due.map((row) => ({ kind: row.kind, versionNo: row.versionNo })) },
  })
  expect(response.status(), await response.text()).toBe(200)
}

/** The versions in effect a page shows with registration's second step or an application. */
async function withAcceptance(request: APIRequestContext, token: string, name: string,
  input: Record<string, unknown>): Promise<Record<string, unknown>> {
  const kinds = ACCEPTED_WITH[name]
  if (!kinds || 'termsVersionNo' in input || input.acceptTerms === false) return input
  const rows = await consentsOf(request, token, kinds)
  if (rows.length === 0) return input
  const version = (kind: string) => rows.find((row) => row.kind === kind)?.versionNo ?? null
  return { ...input, acceptTerms: true, termsVersionNo: version(kinds[0]), privacyVersionNo: version('PRIVACY') }
}

/** Runs a process; returns its output. */
export async function run(request: APIRequestContext, token: string, name: string, input: Record<string, unknown>) {
  if (GATED[name]) await acceptDue(request, token, GATED[name])
  const response = await request.post(`/api/processes/${name}/latest`, {
    headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': crypto.randomUUID() },
    data: await withAcceptance(request, token, name, input),
  })
  expect(response.status(), `${name}: ${await response.text()}`).toBe(200)
  return (await response.json()).output as Record<string, unknown>
}

/** The rows of a dataset matching the filters. */
export async function rows(request: APIRequestContext, token: string, dataset: string,
  filters: Record<string, unknown>[], limit = 100) {
  const response = await request.post(`/api/datasets/${encodeURIComponent(dataset)}/query`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { filters, limit },
  })
  expect(response.status(), await response.text()).toBe(200)
  return ((await response.json()).items ?? []) as { id: string; version: number; attributes: Record<string, unknown> }[]
}

export async function insert(request: APIRequestContext, token: string, dataset: string, attributes: Record<string, unknown>) {
  const response = await request.post(`/api/datasets/${encodeURIComponent(dataset)}/commit`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { changes: [{ action: 'INSERT', attributes }] },
  })
  expect(response.status(), await response.text()).toBe(200)
  return (await response.json())[0] as { id: string }
}

const ROLES = 'urn:jabiz:dataset:platform:SecRole'
const CONTROL_CHANGES = 'urn:jabiz:dataset:platform:SysControlChange'

/**
 * The role of the tests' second administrator, who publishes what QB_SETUP proposes (four eyes, decision D40). It
 * holds `control.publish` only and asks for no second factor; the server's administration accepts it because the
 * end-to-end job adds it to QB_ADMIN_ROLES. Test-only (docs/quizbuks/plans/QP-pages.md section 10).
 */
export const PUBLISHER_ROLE = 'QB_E2E_PUBLISHER'

async function publisherToken(request: APIRequestContext, admin: string): Promise<string> {
  const found = await rows(request, admin, ROLES, [{ field: 'roleCode', op: 'eq', value: PUBLISHER_ROLE }], 1)
  let roleId = found[0]?.id
  if (!roleId) {
    roleId = (await insert(request, admin, ROLES, { roleCode: PUBLISHER_ROLE, labels: { en: 'E2E publisher' },
      enabled: true })).id
    await insert(request, admin, 'urn:jabiz:dataset:platform:SecRolePermission', {
      roleId, permission: 'control.publish' })
  }
  const userName = unique('publisher')
  const created = await run(request, admin, 'SEC_USER_CREATE', { userName, displayName: 'E2E publisher',
    password: PASSWORD })
  await insert(request, admin, 'urn:jabiz:dataset:platform:SecUserRole', { userId: created.userId, roleId })
  return tokenOf(request, userName, PASSWORD)
}

/**
 * Sets a controlled business parameter with four eyes (decision D40): the administrator proposes, the tests' second
 * administrator publishes. The change stays (the tables only grow): only set what every later run may live with.
 */
export async function setControlledParam(request: APIRequestContext, paramKey: string, value: string) {
  const admin = await adminToken(request)
  const change = await run(request, admin, 'CONTROL_CHANGE_PROPOSE', { targetEntity: 'SysParam',
    values: { paramKey, value }, reason: 'End-to-end test' })
  await run(request, await publisherToken(request, admin), 'CONTROL_CHANGE_PUBLISH', { changeId: change.changeId })
}

/**
 * Runs a scheduled job now, as an operator re-runs a scheduled time (platform 17zs, D77: `POST /api/jobs/{job}/runs`,
 * the administrator's `job.run`): its latest scheduled time again, waiting for the run to end. For a job of items the
 * items are selected from the data as it is now, so whatever is due now is done (an item done at that time before is
 * replayed); a test need not wait for the schedule (F-F13).
 */
export async function runJobNow(request: APIRequestContext, job: string) {
  const admin = await adminToken(request)
  const headers = { Authorization: `Bearer ${admin}` }
  const listed = await request.get('/api/jobs', { headers })
  expect(listed.status(), await listed.text()).toBe(200)
  const definition = ((await listed.json()) as { name: string; rerunTimes?: string[] }[]).find((j) => j.name === job)
  const scheduledTime = definition?.rerunTimes?.[0]
  expect(scheduledTime, `a time of ${job} to run again`).toBeTruthy()
  let requestSeqId = 0
  // 409 JOB_BUSY while a scheduled run holds the job's lock: asked again until it is free.
  await expect.poll(async () => {
    const asked = await request.post(`/api/jobs/${encodeURIComponent(job)}/runs`, {
      headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() },
      data: { scheduledTime, reason: 'End-to-end test' },
    })
    if (asked.status() === 202) requestSeqId = (await asked.json()).requestSeqId
    else expect(asked.status(), await asked.text()).toBe(409)
    return asked.status()
  }, { message: `${job} accepted to run`, timeout: 30_000, intervals: [1_000] }).toBe(202)
  let outcome: string | undefined
  await expect.poll(async () => {
    const runs = await request.get(`/api/jobs/${encodeURIComponent(job)}/runs?requestSeqId=${requestSeqId}`, { headers })
    expect(runs.status(), await runs.text()).toBe(200)
    outcome = ((await runs.json()) as { outcome?: string }[])[0]?.outcome
    return outcome !== undefined
  }, { message: `${job} ran`, timeout: 60_000, intervals: [500] }).toBe(true)
  expect(outcome, `${job}'s run`).toBe('SUCCEEDED')
}

/** F-S11: the country an address the address database does not know counts as (backend `QbParams`). */
export const REGION_UNKNOWN_IP_COUNTRY = 'qb.region.unknown-ip-country'

let prepared = false

/**
 * QuizBuks's initial data, as an operator sets it up (backend/quizbuks/CLAUDE.md section 7): the administrator runs
 * QB_SETUP (it adds only what never existed, so every run may do it), and a second administrator publishes what it
 * proposed (the business parameters and review rules). Once per test run.
 */
export async function prepareQuizbuks(request: APIRequestContext) {
  if (prepared) return
  const admin = await adminToken(request)
  await run(request, admin, 'QB_SETUP', {})
  // F-S11: the browser is on this machine, an address the platform does not know: it counts as the parameter says (mainland
  // China by default), which would keep every publication with a YouTube video from being answered. The tests' runs
  // count such an address as Japan; foreign-video.spec sets mainland China for its own checks and back.
  await run(request, admin, 'PARAM_SET', { key: REGION_UNKNOWN_IP_COUNTRY, value: 'JP' })
  // Page by page until none is left: QB_SETUP proposes more than one page (Q87 took it past 100), and a published
  // change leaves the filter, so each read starts again from the first page.
  let publisher: string | undefined
  for (let pass = 0; pass < 20; pass++) {
    const proposed = await rows(request, admin, CONTROL_CHANGES, [{ field: 'status', op: 'eq', value: 'PROPOSED' }])
    if (proposed.length === 0) break
    publisher ??= await publisherToken(request, admin)
    for (const change of proposed) {
      await run(request, publisher, 'CONTROL_CHANGE_PUBLISH', { changeId: change.id })
    }
  }
  // F-S4b: the default reward methods are empty after setup (a new Sponsor gets what a lit node opens); the tests
  // publish with the platform's Kudos, as before (backend RewardMethodsIT checks the empty default).
  await setControlledParam(request, 'qb.reward.default-methods', 'PLATFORM')
  // The terms and the privacy policy in effect, as before going live (Q7b); loaded when needed (it uses these helpers).
  await (await import('./legal')).termsInEffect(request)
  // F-S1: the default operator, without which nobody signs up as a sponsor.
  await (await import('./operators')).ensureDefaultOperator(request)
  prepared = true
}

interface MailSummary {
  ID: string
  Subject: string
  To: { Address: string }[]
}

/** The newest mail to an address whose subject matches, waiting for it to arrive (at the platform's Mailpit unless told). */
export async function mailTo(request: APIRequestContext, to: string, subject: RegExp,
  mailpit: string = MAILPIT): Promise<{ subject: string; text: string; from: string }> {
  let found: MailSummary | undefined
  await expect.poll(async () => {
    const response = await request.get(`${mailpit}/api/v1/search`, { params: { query: `to:"${to}"` } })
    if (!response.ok()) return false
    const messages = ((await response.json()).messages ?? []) as MailSummary[]
    found = messages.find((m) => subject.test(m.Subject))
    return !!found
  }, { message: `a mail to ${to} with a subject like ${subject}`, timeout: 30_000 }).toBe(true)
  const message = await request.get(`${mailpit}/api/v1/message/${found!.ID}`)
  expect(message.ok()).toBe(true)
  const body = await message.json()
  const from = body.From as { Name?: string; Address?: string } | undefined
  return { subject: body.Subject as string, text: `${body.Text ?? ''}\n${body.HTML ?? ''}`,
    from: `${from?.Name ?? ''} <${from?.Address ?? ''}>` }
}

/** The first address in a mail that leads to `path` (`/auth/verify-email`), as a URL. */
export function linkIn(text: string, path: string): URL {
  const links = [...text.matchAll(/https?:\/\/[^\s<>"')\]]+/g)].map((m) => m[0].replace(/&amp;/g, '&'))
  const link = links.find((candidate) => new URL(candidate).pathname.endsWith(path))
  expect(link, `a link to ${path} in:\n${text}`).toBeDefined()
  return new URL(link!)
}

/** Registers at an entry's sign-up page (`base` is the SPA's prefix: `/` or `/sponsor/`). */
export async function signUp(page: Page, base: string, email: string, name: string) {
  await page.goto(`${base}signup`)
  await page.getByPlaceholder('E-mail address', { exact: true }).fill(email)
  await page.getByPlaceholder('Your name', { exact: true }).fill(name)
  await page.getByPlaceholder('New password', { exact: true }).fill(PASSWORD)
  await page.getByPlaceholder('Repeat the new password', { exact: true }).fill(PASSWORD)
  await page.getByRole('button', { name: 'Create the account' }).click()
  await expect(page.getByTestId('sign-up-sent')).toBeVisible()
}

/** Opens the mailed verification link (it must lead to the entry's own front end) and verifies the address. */
export async function verifyByMail(page: Page, email: string, base: string) {
  const mail = await mailTo(page.request, email, /verify|confirm|address/i)
  const link = linkIn(mail.text, '/auth/verify-email')
  expect(link.pathname).toBe(`${base}auth/verify-email`)
  await page.goto(link.pathname + link.search)
  await page.getByTestId('verify-start').click()
  // An account that registered itself confirms with its password that whoever reads the mailbox registered it
  // (decision D36 item 3).
  await expect(page.getByTestId('verify-password-hint')).toBeVisible()
  await page.getByPlaceholder('Password', { exact: true }).fill(PASSWORD)
  await page.getByRole('button', { name: 'Verify my address' }).click()
  await expect(page.getByTestId('verify-state').locator('[data-state]')).toHaveAttribute('data-state', 'done')
}

/**
 * A user who registered at an entry and verified the address through the mailed link, both through the API (for
 * tests about what comes after). Returns the address; the password is {@link PASSWORD}.
 */
export async function verifiedUser(request: APIRequestContext, entry: 'app' | 'sponsor', name: string) {
  const email = address(entry)
  const registered = await request.post('/api/auth/register', {
    data: { email, password: PASSWORD, displayName: name, locale: 'en', entry },
  })
  expect(registered.status(), await registered.text()).toBe(202)
  const mail = await mailTo(request, email, /verify|confirm|address/i)
  const token = linkIn(mail.text, '/auth/verify-email').searchParams.get('token')!
  const check = await request.post('/api/auth/verify-email/check', { data: { token } })
  expect(check.status(), await check.text()).toBe(200)
  const body = (await check.json()).passwordRequired ? { token, password: PASSWORD } : { token }
  const verified = await request.post('/api/auth/verify-email', { data: body })
  expect(verified.status(), await verified.text()).toBeLessThan(300)
  return email
}

/** Signs in on an entry's own sign-in page. */
export async function signIn(page: Page, base: string, email: string) {
  await page.goto(`${base}login`)
  // The app and the console ask for the address (Q50); in Chinese a mailed link comes first, so the fields are found
  // by the password form they belong to, in any interface language.
  const name = page.getByTestId('login-user-name')
  const form = page.locator('form').filter({ has: name })
  await name.fill(email)
  await form.locator('input[type="password"]').fill(PASSWORD)
  await form.locator('button[type="submit"]').click()
}

/**
 * Opens an area of the Sponsor app from its menu (F-F3: the home and eight areas, `sponsor/src/shell/areas.ts`), and
 * then one of its tabs (`page-tab-<tab>`). On a phone the menu is behind the bottom menu's "More".
 */
export async function openArea(page: Page, area: string, tab?: string) {
  const entry = page.getByTestId(`menu-${area}`).getByRole('link').first()
  const more = page.getByTestId('bottom-bar-more')
  // Signed in just now, the frame may not be drawn yet: wait for the menu (or, on a phone, "More").
  await expect(entry.or(more).first()).toBeVisible()
  if (!(await entry.isVisible())) await more.click()
  await page.getByTestId(`menu-${area}`).getByRole('link').first().click()
  if (tab) await page.getByTestId(`page-tab-${tab}`).click()
}

/** The five areas of the taker app (F-F4). */
export type TakerArea = 'home' | 'history' | 'scan' | 'messages' | 'me'

/**
 * The frame's entry for an area of the taker app: in the bottom menu on a phone, in the left column on a wide screen
 * (the page draws both and shows one by the width).
 */
export function takerArea(page: Page, area: TakerArea) {
  return page.locator(`[data-testid="bottom-bar-${area}"]:visible, [data-testid="side-rail-${area}"]:visible`)
}

/** Signs out of the taker app: "Me", then the button at the bottom of it (F-F4). */
export async function signOutTaker(page: Page) {
  await takerArea(page, 'me').click()
  await page.getByTestId('sign-out').click()
}

/** Signs out of the Sponsor app from the avatar menu at the top right (F-F3). */
export async function signOutSponsor(page: Page) {
  await page.getByTestId('avatar-menu-open').click()
  await page.getByTestId('sign-out').click()
}

/** Clears the browser's sessions, so that the next {@link signIn} on the same entry shows its form again. */
export async function signOut(page: Page) {
  await page.context().clearCookies()
  await page.evaluate(() => {
    window.localStorage.clear()
    window.sessionStorage.clear()
  })
}

/**
 * No WCAG 2.2 A/AA violations on the page as it is now, once its animations have settled (soft: every page of a test
 * is checked): the platform's check (`@jabiz/ui/testing/playwright`, ROADMAP 16v-1), asserted with this run's
 * `expect`.
 */
export async function expectAccessible(page: Page, name: string) {
  await expectAccessibleWith(page, name, { expect })
}

/** Chooses the appearance before any page loads (the platform keeps it in localStorage as `jabiz.appearance`). */
export { setAppearance }
