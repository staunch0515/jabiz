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
  test,
  unique,
} from './support'
import { fundedSponsor } from './play'

/**
 * A publication's share link (docs/quizbuks/plans/Q15-share-link.md): the sponsor copies it, a visitor opens it
 * without an account, sees the quiz, gives only an e-mail address, opens the mailed link, gives the country (and
 * accepts the terms in effect) and is back on the publication's page, able to start.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** A live publication of one question, open to every region. */
async function sharedPublication(request: APIRequestContext) {
  const sponsor = await fundedSponsor(request)
  const quiz = await run(request, sponsor.token, 'QB_QUIZ_SAVE', { title: 'Shared capitals', intro: null,
    timeLimitSec: 300 })
  const quizId = quiz.quizId as string
  await run(request, sponsor.token, 'QB_QUESTION_SAVE', { quizId, stem: 'Capital of France?', points: 1,
    options: [{ text: 'Paris', correct: true }, { text: 'Lyon' }] })
  const version = await run(request, sponsor.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  const name = unique('Shared ')
  const now = Date.now()
  const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', {
    versionId: version.versionId, name, regions: ['GLOBAL'],
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
  return { publicationId, name }
}

test('a visitor starts from a share link with an e-mail address only', async ({ page, request }) => {
  test.setTimeout(180_000)
  await page.setViewportSize({ width: 390, height: 844 })
  const shared = await sharedPublication(request)
  // The page may wait for the publication to go on.
  await expect.poll(async () => (await request.get('/api/public/queries/qb.public.publication', {
    params: { 'p.publicationId': shared.publicationId, 'p.at': new Date().toISOString() } })).json()
    .then((page) => page.items?.length ?? 0), { timeout: 60_000 }).toBe(1)

  await page.goto(`/p/${shared.publicationId}`)
  await expect(page.getByRole('heading', { name: shared.name })).toBeVisible()
  await expectAccessible(page, 'share page')
  const email = address('shared')
  await page.getByTestId('email-link-email').fill(email)
  await page.getByRole('button', { name: 'Start with my e-mail address' }).click()
  await expect(page.getByTestId('email-link-sent')).toBeVisible()

  const mail = await mailTo(request, email, /sign-in link/i)
  const link = linkIn(mail.text, '/auth/email-link')
  expect(link.searchParams.get('to')).toBe(`/p/${shared.publicationId}`)
  await page.goto(link.pathname + link.search)
  await page.getByTestId('email-link-start').click()

  // A new taker: the short welcome step.
  await expect(page.getByTestId('welcome')).toBeVisible()
  await expect(page.getByTestId('profile-name')).toHaveCount(0)
  // The country is the browser's (en-US); the terms in effect are accepted here.
  await expect(page.getByTestId('profile-country')).toContainText('United States')
  await expect(page.getByTestId('profile-city')).toHaveCount(0)
  await page.getByTestId('profile-terms').click()
  await expectAccessible(page, 'share welcome')
  await page.getByTestId('profile-save').click()

  await expect(page).toHaveURL(new RegExp(`/p/${shared.publicationId}$`))
  await expect(page.getByTestId('detail-start')).toBeVisible()
})
