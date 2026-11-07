import type { APIRequestContext } from '@playwright/test'
import { completeQuiz, query } from './content'
import { decideReview, publicationOf } from './publishing'
import { fundedSponsor, taker } from './play'
import { expect, expectAccessible, prepareQuizbuks, run, setControlledParam, signIn, signOut, test, unique } from './support'

/**
 * A quiz's entry fee (docs/quizbuks/plans/Q88-sponsor-certifications-entry-fee.md, Q88b): the sponsor sets it in the
 * wizard's step "Time and rewards" while the switch is on; a taker who cannot pay is told how much is missing; one who
 * won Kudos elsewhere confirms and starts, and their transactions list the fee; the sponsor's wallet shows the fees
 * waiting, and the daily job (every minute here) settles them into the cash account.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** Saves a draft of the quiz's newest version through the API, as the wizard does, without submitting it. */
async function draft(request: APIRequestContext, token: string, quizId: string, name: string,
  tiers: { fromScore: number; toScore: number; reward: number }[]) {
  const versionId = (await query(request, token, 'qb.sponsor.quiz-versions', { quizId }))[0].versionId
  const now = Date.now()
  const saved = await run(request, token, 'QB_PUBLICATION_SAVE', { versionId, name, regions: ['GLOBAL'],
    startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
    passScore: 0, budgetCap: 2000, maxWinners: 100, tiers })
  return saved.publicationId as string
}

async function goLive(request: APIRequestContext, token: string, publicationId: string) {
  const submitted = await run(request, token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  if (submitted.approval === 'PENDING') await decideReview(request, token, publicationId, 'APPROVE')
  await expect.poll(async () => (await publicationOf(request, token, publicationId)).status,
    { message: 'the publication is live', timeout: 60_000 }).toBe('LIVE')
}

test('a quiz charges an entry fee from transferable Kudos, settled into the Sponsor\'s cash (Q88b)', async ({ page,
  request }) => {
  test.setTimeout(300_000)
  // Off by default (D-Q88-14); rewards are not held back, so one just won can pay (a test cannot wait 7 days).
  await setControlledParam(request, 'qb.play.entry-fee.enabled', 'true')
  await setControlledParam(request, 'qb.transfer.hold-days', '0')
  const sponsor = await fundedSponsor(request)
  const quiz = await completeQuiz(request, sponsor.token, { title: unique('Fee quiz ') })
  // A quiz everybody wins 50 Kudos on, to earn the fee with.
  const rewarding = await draft(request, sponsor.token, quiz.quizId, unique('Fee rewarding '),
    [{ fromScore: 0, toScore: 2, reward: 50 }])
  await goLive(request, sponsor.token, rewarding)

  // The console: the fee set in the wizard and shown in the summary.
  const name = unique('Fee exam ')
  const charging = await draft(request, sponsor.token, quiz.quizId, name, [{ fromScore: 0, toScore: 2, reward: 10 }])
  await signIn(page, '/sponsor/', sponsor.email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(`/sponsor/publications/${charging}?step=1`)
  await expect(page.getByTestId('publication-step-title')).toHaveText('Time and rewards')
  await page.getByTestId('publication-entry-fee').fill('30')
  await expectAccessible(page, 'publication wizard with an entry fee')
  await page.getByTestId('publication-next').click()
  await page.getByTestId('publication-next').click()
  await expect(page.getByTestId('summary-entry-fee')).toHaveText('30 Kudos')
  await goLive(request, sponsor.token, charging)

  // A taker with nothing: told how much is missing, and where to earn it; no start offered.
  const poor = await taker(request, 'Pia')
  await signIn(page, '/taker/', poor.email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(`/taker/p/${charging}`)
  await expect(page.getByTestId('detail-entry-fee')).toContainText('30 Kudos')
  await expect(page.getByTestId('detail-entry-fee-short')).toContainText('30 Kudos more needed')
  await expect(page.getByTestId('detail-start')).toHaveCount(0)
  await expectAccessible(page, 'app quiz with an entry fee not covered')

  // A taker who won 50 elsewhere: confirms, pays and starts.
  const earner = await taker(request, 'Eli')
  const won = await run(request, earner.token, 'QB_ATTEMPT_START', { publicationId: rewarding })
  const result = await run(request, earner.token, 'QB_ATTEMPT_SUBMIT', { attemptId: won.attemptId,
    publicationId: rewarding, answers: [] })
  expect(result.rewardStatus).toBe('PAID')
  await signOut(page)
  await signIn(page, '/taker/', earner.email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(`/taker/p/${charging}`)
  const start = page.getByTestId('detail-start')
  await expect(start).toBeEnabled()
  await expect(start).toContainText('Pay 30 Kudos and start')
  await start.click()
  const dialog = page.getByRole('alertdialog')
  await expect(dialog).toContainText('not refunded')
  await expectAccessible(page, 'entry fee confirmation')
  await dialog.getByRole('button', { name: /^Pay 30\sKudos and start$/ }).click()
  await expect(page).toHaveURL(/\/play\/[^/]+$/)

  // The taker's transactions list the fee.
  await page.goto('/taker/wallet/history')
  const fee = page.getByTestId('ledger-row').filter({ hasText: name })
  await expect(fee).toHaveAttribute('data-type', 'ENTRY_FEE')
  await expect(fee.getByTestId('ledger-amount')).toHaveText('−30 Kudos')

  // The sponsor: the fee waits for the daily settlement, then is in the cash account.
  await signIn(page, '/sponsor/', sponsor.email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto('/sponsor/wallet')
  await expect.poll(async () => {
    await page.reload()
    // The amount's formatting keeps the number and the unit together with a no-break space.
    return (await page.getByTestId('wallet-cash-balance').textContent())?.replace(/\s+/g, ' ').trim()
  }, { message: 'the fee is settled into the cash account', timeout: 120_000, intervals: [5_000] }).toBe('30 Kudos')
  await expect(page.getByTestId('wallet-entry-fees')).toHaveCount(0)
  await page.goto(`/sponsor/publications/${charging}`)
  await expect(page.getByTestId('publication-entry-fees')).toContainText('collected so far 30 Kudos')
})
