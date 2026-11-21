import type { APIRequestContext, Page } from '@playwright/test'
import { totp } from '../tests/legal'
import { taker } from '../tests/play'
import { expect, linkIn, mailTo, PASSWORD, signIn, test } from '../tests/support'

/*
 * F-LAUNCH Q4 (docs/quizbuks/plans/impl/F-LAUNCH.md section 9): the application started on an empty database with the
 * sample handed to the requester (docs/quizbuks/first-launch-sample/, its end date moved by
 * tools/quizbuks/first-launch-e2e-config.sh) and no first administrator. Its content phase makes the operator, its
 * lead's Sponsor with the package's quiz, graph and publication; a Taker answers that publication. The lead (account-b,
 * whose roles make nobody an administrator, so no operator's confirmation is needed) sets a password from the mailed
 * invitation, signs in at /admin/ and binds a second factor, and once the job jabiz.first-launch has granted the
 * configured roles signs in at /operator/ and sees their operator. Mail is read from the tests' Mailpit.
 *
 * Its own configuration (first-launch.config.ts): the main suite needs a first administrator, this one must have none.
 */

const HANDLE = 'official-sample'
const PUBLICATION = '春季答题'
const OPERATOR = '官方运营'
const LEAD = 'b@example.com'
/** The platform's invitation in the account's language (the sample's accounts are in Chinese). */
const INVITATION = /设定密码/
const MAILPIT = (process.env.E2E_MAILPIT_URL ?? 'http://localhost:8025').replace(/\/+$/, '')
/** The sample's questions and the correct option of each (the questions and options are shown shuffled). */
const ANSWERS: Record<string, string> = {
  '东京的樱花一般几月开？': '三月下旬',
  '下图是什么花？': '樱花',
  '郁金香是春天开的花吗？': '是',
}

/** The publication of the sample, once the content phase has put it live (503 until then). */
async function livePublication(request: APIRequestContext): Promise<string> {
  let id = ''
  await expect.poll(async () => {
    const response = await request.get('/api/public/queries/qb.public.sponsor-page-publications',
      { params: { 'p.handle': HANDLE } })
    if (!response.ok()) return response.status()
    const items = ((await response.json()).items ?? []) as { publicationId: string; name: string }[]
    id = items.find((item) => item.name === PUBLICATION)?.publicationId ?? ''
    return id ? 'live' : 'not yet'
  }, { message: 'the first launch puts the sample publication live', timeout: 300_000, intervals: [2_000] })
    .toBe('live')
  return id
}

/**
 * The TOTP codes this test used, by time step: the server takes a code once, and allows a step of drift, so a new one
 * is taken from a step after every one used.
 */
const used: number[] = []
const step = (time: number) => Math.floor(time / 30_000)

async function freshCode(secret: string): Promise<string> {
  const last = Math.max(-1, ...used)
  const wait = (last + 1) * 30_000 - Date.now() + 500
  if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait))
  used.push(step(Date.now()) + 1)
  return totp(secret)
}

/** Fills in and sends a sign-in page's password form (`/admin/login`, `/operator/login`). */
async function submitPassword(page: Page, userName: string) {
  await page.getByPlaceholder('User name', { exact: true }).fill(userName)
  await page.getByPlaceholder('Password', { exact: true }).fill(PASSWORD)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
}

test('a first launch from the sample: a Taker answers, the lead accepts the invitation and sees the operator', async ({
  browser, page, request,
}) => {
  test.setTimeout(600_000)
  const publicationId = await livePublication(request)

  // A Taker opens the publication the first launch put live and answers it.
  const me = await taker(request, 'Fumi')
  await signIn(page, '/taker/', me.email)
  await page.goto(`/taker/p/${publicationId}`)
  await expect(page.getByTestId('detail-name')).toHaveText(PUBLICATION)
  await page.getByTestId('detail-start').click()
  await expect(page).toHaveURL(/\/play\/[^/]+$/)
  for (let i = 1; i <= 3; i++) {
    await expect(page.getByTestId('play-progress')).toHaveText(`Question ${i} of 3`)
    const stem = (await page.getByTestId('play-question').innerText()).trim()
    const answer = Object.entries(ANSWERS).find(([question]) => stem.includes(question))?.[1]
    expect(answer, `a question of the sample: ${stem}`).toBeDefined()
    const saved = page.waitForResponse((r) => r.url().includes('/api/processes/QB_ATTEMPT_SAVE/') && r.ok())
    await page.getByRole('radio', { name: answer, exact: true }).click()
    await saved
    if (i < 3) await page.getByTestId('play-next').click()
  }
  await page.getByTestId('play-submit').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Submit answers' }).click()
  await expect(page).toHaveURL(/\/result\/[^/]+$/)
  await expect(page.getByTestId('result-score')).toHaveText('3 / 3')
  await expect(page.getByTestId('result-passed')).toHaveText('Passed')

  // The lead: the invitation (sent once the content is in place), a password, the second factor, the roles.
  const lead = await browser.newContext()
  try {
    const desk = await lead.newPage()
    // The job runs every ten seconds here, at most once a lock's 30 seconds, from the end of the content phase.
    await expect.poll(async () => {
      const response = await request.get(`${MAILPIT}/api/v1/search`, { params: { query: `to:"${LEAD}"` } })
      const messages = response.ok() ? ((await response.json()).messages ?? []) as { Subject: string }[] : []
      return messages.some((m) => INVITATION.test(m.Subject))
    }, { message: 'the lead is invited', timeout: 180_000, intervals: [2_000] }).toBe(true)
    const invitation = await mailTo(request, LEAD, INVITATION)
    const link = linkIn(invitation.text, '/auth/invitation')
    expect(link.pathname).toBe('/admin/auth/invitation')
    await desk.goto(link.pathname + link.search)
    await desk.getByPlaceholder('New password', { exact: true }).fill(PASSWORD)
    await desk.getByPlaceholder('Repeat the new password', { exact: true }).fill(PASSWORD)
    await desk.getByTestId('invitation').locator('button[type="submit"]').click()
    await expect(desk.getByTestId('invitation-state')).toBeVisible()
    await expect(desk.getByTestId('invitation-hint')).toHaveCount(0)

    // The waiting role asks for a second factor: the first sign-in binds it.
    await desk.goto('/admin/login')
    await submitPassword(desk, LEAD)
    await expect(desk.getByTestId('mfa-enroll')).toBeVisible()
    await desk.getByTestId('mfa-start').click()
    const secret = (await desk.getByTestId('mfa-secret').innerText()).trim()
    await desk.getByTestId('mfa-code').fill(await freshCode(secret))
    await desk.getByTestId('mfa-confirm').click()
    await desk.getByTestId('mfa-saved').click()
    if (await desk.getByPlaceholder('User name', { exact: true }).isVisible()) await submitPassword(desk, LEAD)
    const code = desk.locator('input[autocomplete="one-time-code"]')
    await code.fill(await freshCode(secret))
    await code.press('Enter')
    await expect(desk).toHaveURL(/\/admin\//)
    await expect(desk).not.toHaveURL(/\/login/)

    // Within a minute the job grants the configured roles: the operators' entry, which takes QB_OPERATOR and not the
    // waiting role, then asks for the second factor instead of refusing the account.
    await expect.poll(async () => {
      const response = await request.post('/api/auth/login', { data: { userName: LEAD, password: PASSWORD,
        entry: 'operator' } })
      return response.ok() ? (await response.json()).status : `HTTP ${response.status()}`
    }, { message: 'the lead holds QB_OPERATOR', timeout: 180_000, intervals: [5_000] }).toBe('MFA_REQUIRED')

    await desk.goto('/operator/login')
    await submitPassword(desk, LEAD)
    const deskCode = desk.locator('input[autocomplete="one-time-code"]')
    await deskCode.fill(await freshCode(secret))
    await deskCode.press('Enter')
    await expect(desk).not.toHaveURL(/\/login/)
    await desk.goto('/operator/qb/operator')
    const home = desk.getByTestId('operator-home')
    await expect(home).toContainText(OPERATOR)
    // The lead is the operator's own Sponsor.
    await expect(home.getByTestId('operator-own-sponsor')).toBeVisible()
  } finally {
    await lead.close()
  }
})
