import { decideReview, publicationOf } from './publishing'
import { fundedSponsor, taker } from './play'
import { query } from './content'
import { adminToken, expect, expectAccessible, prepareQuizbuks, run, setControlledParam, signIn, test, unique } from './support'

/**
 * A sponsor's own certification (docs/quizbuks/plans/Q88-sponsor-certifications-entry-fee.md, Q88a): the sponsor
 * defines one on the console's "Certifications" page, makes a quiz paying nothing give it (the wizard's step "Time
 * and rewards") and submits it; a taker sees what passing gives, passes, holds the certification with the sponsor as
 * its issuer, and finds it on the verify page; the sponsor's list counts the holder and the administration lists the
 * certification.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** The browser's session cleared, so the next sign-in shows the form (as digital-prize.spec.ts does). */
async function signOut(page: import('@playwright/test').Page) {
  await page.context().clearCookies()
  await page.evaluate(() => {
    window.localStorage.clear()
    window.sessionStorage.clear()
  })
}

async function choose(page: import('@playwright/test').Page, text: string) {
  const saved = page.waitForResponse((r) => r.url().includes('/api/processes/QB_ATTEMPT_SAVE/') && r.ok())
  await page.getByRole('radio', { name: text }).click()
  await saved
}

test('a Sponsor defines a certification, a quiz paying nothing gives it, a Taker earns it (Q88a)', async ({ page,
  request }) => {
  test.setTimeout(300_000)
  // The switch is off by default (D-Q88-7); the tests turn it on once (controlled, four eyes).
  await setControlledParam(request, 'qb.certification.sponsor-rounds.enabled', 'true')
  const sponsor = await fundedSponsor(request)
  const quiz = await run(request, sponsor.token, 'QB_QUIZ_SAVE', { title: 'Keyboard basics', intro: null,
    timeLimitSec: 300 })
  const quizId = quiz.quizId as string
  await run(request, sponsor.token, 'QB_QUESTION_SAVE', { quizId, stem: 'Which switch is linear?', points: 1,
    options: [{ text: 'Red', correct: true }, { text: 'Blue' }] })
  await run(request, sponsor.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })

  // The console: the certification defined on its page.
  await signIn(page, '/sponsor/', sponsor.email)
  await expect(page).not.toHaveURL(/\/login/)
  // F-C1c: the certifications are a tab of the asset library.
  await page.goto('/sponsor/assets?tab=certifications')
  await expect(page.getByTestId('certifications-page')).toBeVisible()
  const name = unique('Keyboard expert ')
  await page.getByTestId('certification-add').click()
  await page.getByTestId('certification-name-en').fill(name)
  await page.getByTestId('certification-description-en').fill('Knows the switches')
  await page.getByTestId('certification-validity').fill('365')
  await expectAccessible(page, 'sponsor certification dialog')
  await page.getByTestId('certification-save').click()
  const row = page.getByTestId('certification-row').filter({ hasText: name })
  await expect(row).toBeVisible()
  await expect(row.getByTestId('certification-status')).toHaveAttribute('data-status', 'ACTIVE')
  await expectAccessible(page, 'sponsor certifications page')
  const certificationId = (await row.getAttribute('data-id'))!

  // A draft paying nothing, made a round of the certification in the wizard (step "Time and rewards").
  const version = (await query(request, sponsor.token, 'qb.sponsor.quiz-versions', { quizId }))[0].versionId as string
  const quizName = unique('Keyboard certification ')
  const now = Date.now()
  const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', { versionId: version, name: quizName,
    regions: ['GLOBAL'], startAt: new Date(now - 60_000).toISOString(),
    endAt: new Date(now + 7 * 86_400_000).toISOString(), timeLimitSec: 300, passScore: 1, maxWinners: 100,
    tiers: [{ fromScore: 0, toScore: 1, reward: 0 }] })
  const publicationId = saved.publicationId as string
  await page.goto(`/sponsor/publications/${publicationId}?step=1`)
  await expect(page.getByTestId('publication-step-title')).toHaveText('Time and rewards')
  await page.getByTestId('publication-round').click()
  await page.getByRole('option', { name }).click()
  await page.getByTestId('publication-next').click()
  await page.getByTestId('publication-next').click()
  await expect(page.getByTestId('summary-round')).toHaveText(name)
  // Paying nothing is no problem once it gives a certification (D-Q88-5).
  await expect(page.getByTestId('publication-ready')).toBeVisible()
  const submitted = await run(request, sponsor.token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  if (submitted.approval === 'PENDING') await decideReview(request, sponsor.token, publicationId, 'APPROVE')
  await expect.poll(async () => (await publicationOf(request, sponsor.token, publicationId)).status,
    { message: 'the publication is live', timeout: 60_000 }).toBe('LIVE')
  // The publication's page shows the round, which the sponsor could close.
  await page.goto(`/sponsor/publications/${publicationId}`)
  await expect(page.getByTestId('publication-round-section')).toHaveAttribute('data-closed', 'false')
  await expect(page.getByTestId('publication-round-close')).toBeVisible()

  // A taker: the page says what passing gives, they pass, hold it and can issue the certificate.
  const me = await taker(request, 'Kei')
  await signOut(page)
  await signIn(page, '/taker/', me.email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(`/taker/p/${publicationId}`)
  await expect(page.getByTestId('detail-certification')).toContainText(name)
  await expectAccessible(page, 'app quiz giving a certification')
  await page.getByTestId('detail-start').click()
  await expect(page).toHaveURL(/\/play\/[^/]+$/)
  await choose(page, 'Red')
  await page.getByTestId('play-submit').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Submit answers' }).click()
  await expect(page).toHaveURL(/\/result\/[^/]+$/)
  await expect(page.getByTestId('result-passed')).toHaveText('Passed')

  const key = `sp:${certificationId}`
  await page.goto('/taker/account/certificates')
  const item = page.getByTestId(`certificate-${key}`)
  await expect(item).toBeVisible()
  await expect(item).toContainText(name)
  await expect(item.getByTestId('certificate-issuer')).toBeVisible()
  await item.getByTestId(`certificate-issue-${key}`).click()
  await expect(item.getByTestId(`certificate-download-${key}`)).toBeVisible()
  const held = (await run(request, me.token, 'QB_MY_CERTIFICATIONS', {})).held as { certification: string;
    verifyKey: string | null; sponsorName: string | null }[]
  const mine = held.find((h) => h.certification === key)!
  expect(mine.sponsorName).toBeTruthy()
  await page.goto(`/taker/verify/${mine.verifyKey}`)
  await expect(page.getByTestId('verify-state')).toHaveText('Valid')
  await expect(page.getByTestId('verify-certification')).toHaveText(name)
  await expect(page.getByTestId('verify-issuer')).toHaveText(mine.sponsorName!)
  await expectAccessible(page, 'verify page of a Sponsor certification')

  // The sponsor's list counts the holder.
  await signOut(page)
  await signIn(page, '/sponsor/', sponsor.email)
  await expect(page).not.toHaveURL(/\/login/)
  // The old address leads to the asset library's tab (F-C1c).
  await page.goto('/sponsor/certifications')
  await expect(page).toHaveURL(/\/sponsor\/assets\?tab=certifications$/)
  await expect(row.getByTestId('certification-holders')).toHaveText('1')

  // The administration's template lists it (read only; the page is covered by the extension's tests).
  const admin = await adminToken(request)
  const listed = await query(request, admin, 'qb.admin.sponsor-certifications', { at: new Date().toISOString(),
    q: 'Quiz Co' })
  expect(listed.some((r) => r.certificationId === certificationId)).toBe(true)
})
