import type { APIRequestContext } from '@playwright/test'
import {
  address,
  adminToken,
  expect,
  expectAccessible,
  linkIn,
  mailTo,
  prepareQuizbuks,
  run,
  signIn,
  test,
  unique,
} from './support'
import { fundedSponsor, taker } from './play'
import { publicationOf } from './publishing'

/**
 * Invitation-only publications (docs/quizbuks/plans/Q25-invitations.md): the sponsor publishes for a list of
 * addresses instead of regions, adds an address in the console and sends the invitations; the mail's link signs the
 * invitee in and opens the publication, which nobody else sees or starts; the answer is recorded on the list.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** A live invited publication of one question, without regions. */
async function invitedPublication(request: APIRequestContext) {
  const sponsor = await fundedSponsor(request)
  const quiz = await run(request, sponsor.token, 'QB_QUIZ_SAVE', { title: 'Invited capitals', intro: null,
    timeLimitSec: 300 })
  const quizId = quiz.quizId as string
  await run(request, sponsor.token, 'QB_QUESTION_SAVE', { quizId, stem: 'Capital of France?', points: 1,
    options: [{ text: 'Paris', correct: true }, { text: 'Lyon' }] })
  const version = await run(request, sponsor.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  const name = unique('Invited ')
  const now = Date.now()
  const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', {
    versionId: version.versionId, name, regions: [], audience: 'INVITED',
    startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
    timeLimitSec: 300, passScore: 1, tiers: [{ fromScore: 0, toScore: 0, reward: 0 },
      { fromScore: 1, toScore: 1, reward: Math.min(sponsor.balance, 100) }],
  })
  const publicationId = saved.publicationId as string
  const submitted = await run(request, sponsor.token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  if (submitted.approval === 'PENDING') {
    await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId,
      decision: 'APPROVE' })
  }
  await expect.poll(async () => (await publicationOf(request, sponsor.token, publicationId)).status,
    { timeout: 60_000 }).toBe('LIVE')
  return { sponsor, publicationId, name }
}

/** The invitation mail: the job sends queued mail every minute, so this waits longer than one mail usually takes. */
async function invitationMail(request: APIRequestContext, to: string) {
  let last: unknown
  for (let attempt = 0; attempt < 4; attempt++) {
    try {
      return await mailTo(request, to, /invites you/i)
    } catch (error) {
      last = error
    }
  }
  throw last
}

test('an invited address answers through its mailed link; nobody else sees the publication', async ({ page, request }) => {
  test.setTimeout(300_000)
  const { sponsor, publicationId, name } = await invitedPublication(request)
  const invitee = address('invitee')

  // Not listed, no public page, and a taker who is not invited cannot start it.
  const other = await taker(request, 'Noa')
  const refused = await request.post('/api/processes/QB_ATTEMPT_START/latest', {
    headers: { Authorization: `Bearer ${other.token}` }, data: { publicationId } })
  expect(refused.status()).toBe(422)
  expect(await refused.text()).toContain('QB_NOT_INVITED')
  const publicPage = await request.get('/api/public/queries/qb.public.publication',
    { params: { 'p.publicationId': publicationId } })
  expect(((await publicPage.json()).items ?? []).length).toBe(0)

  // The sponsor adds the address in the console and sends the invitations.
  await signIn(page, '/sponsor/', sponsor.email)
  // The console's session is set once the dashboard shows; a navigation before that lands on the sign-in page.
  await expect(page.getByTestId('dashboard-status')).toBeVisible()
  await page.goto(`/sponsor/publications/${publicationId}`)
  await expect(page.getByTestId('publication-view')).toBeVisible()
  await expect(page.getByTestId('summary-audience')).toHaveText('Invited e-mail addresses only')
  const invitations = page.getByTestId('invitations')
  await invitations.getByTestId('invitations-addresses').fill(`${invitee}\nnot-an-address`)
  await invitations.getByTestId('invitations-add').click()
  // The whole call is refused for the one bad address: nothing is added until it is fixed.
  await expect(invitations.getByRole('alert')).toContainText('not-an-address')
  await invitations.getByTestId('invitations-addresses').fill(invitee)
  await invitations.getByTestId('invitations-add').click()
  await expect(invitations.getByTestId('invitation-row')).toHaveCount(1)
  await expect(invitations.getByTestId('invitation-status')).toHaveAttribute('data-status', 'ADDED')
  await expectAccessible(page, 'sponsor invitations')
  await invitations.getByTestId('invitations-send').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Send the invitations' }).click()
  await expect(invitations.getByTestId('invitation-status')).toHaveAttribute('data-status', /QUEUED|SENT/)

  // The invitee opens the mailed link: signed in with the address, back on the publication, able to start.
  const mail = await invitationMail(request, invitee)
  expect(mail.subject).toContain('Invited capitals')
  const link = linkIn(mail.text, '/auth/email-link')
  expect(link.searchParams.get('to')).toBe(`/taker/p/${publicationId}`)
  await page.context().clearCookies()
  await page.goto(link.pathname + link.search)
  await page.getByTestId('email-link-start').click()
  await expect(page.getByTestId('welcome')).toBeVisible()
  await page.getByTestId('profile-terms').click()
  await page.getByTestId('profile-save').click()
  await expect(page).toHaveURL(new RegExp(`/p/${publicationId}$`))
  await expect(page.getByRole('heading', { name })).toBeVisible()
  await expect(page.getByTestId('detail-invited')).toBeVisible()
  await expectAccessible(page, 'invited publication')
  await page.getByTestId('detail-start').click()
  await expect(page.getByTestId('play-progress')).toHaveText('Question 1 of 1')
  await page.getByRole('checkbox', { name: 'Paris' }).click()
  await page.getByTestId('play-submit').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Submit' }).click()
  await expect(page.getByTestId('result-score')).toHaveText('1 / 1')

  // The sponsor sees the answer on the list.
  await expect.poll(async () => {
    const rows = await request.post('/api/queries/qb.sponsor.invitations', {
      headers: { Authorization: `Bearer ${sponsor.token}` }, data: { params: { publicationId }, limit: 10 } })
    return ((await rows.json()).items ?? [])[0]?.status
  }, { timeout: 30_000 }).toBe('ANSWERED')
})
