import { announceVersion, FAQ, legalAdmin, publishVersion, withdrawVersion } from './legal'
import {
  adminToken,
  consentsOf,
  expect,
  insert,
  PASSWORD,
  prepareQuizbuks,
  run,
  signIn,
  test,
  tokenOf,
  unique,
  verifiedUser,
} from './support'

/*
 * Terms, privacy and help (docs/quizbuks/plans/Q7-messages-legal.md, Q7b): anybody reads the help centre and the texts
 * in effect; a new taker accepts the terms on the welcome step; after a change of substance a taker accepts again on
 * signing in (a rewording asks nothing), and the server refuses money and contract actions until then.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('anybody reads the help centre and the texts in effect, never a question not published', async ({ page,
  request }) => {
  const admin = await adminToken(request)
  const shown = unique('How do I earn Kudos ')
  const hidden = unique('Draft question ')
  await insert(request, admin, FAQ, { audience: 'APP', category: 'REWARDS', question: { en: shown },
    answer: { en: 'Answer quizzes **right**.' }, seq: 1, published: true })
  await insert(request, admin, FAQ, { audience: 'ALL', category: 'REWARDS', question: { en: hidden },
    answer: { en: 'Not yet.' }, seq: 2, published: false })
  const sponsorOnly = unique('How do I top up ')
  await insert(request, admin, FAQ, { audience: 'SPONSOR', category: 'BILLING', question: { en: sponsorOnly },
    answer: { en: 'From the wallet.' }, seq: 1, published: true })

  await page.goto('/taker/help')
  await page.getByTestId('help-search').fill(shown)
  const entry = page.getByTestId('faq-entry').filter({ hasText: shown })
  await expect(entry).toBeVisible()
  await entry.locator('summary').click()
  await expect(entry.getByTestId('faq-answer').locator('strong')).toHaveText('right')
  await page.getByTestId('help-search').fill(hidden)
  await expect(page.getByTestId('help-empty')).toBeVisible()
  await expect(page.getByText(hidden)).toHaveCount(0)
  await page.getByTestId('help-search').fill(sponsorOnly)
  await expect(page.getByTestId('help-empty')).toBeVisible()
  await expect(page.getByTestId('help-contact')).toContainText('Sign in')

  await page.getByTestId('legal-links').getByRole('link', { name: 'Terms of use' }).click()
  await expect(page).toHaveURL(/\/legal\/terms$/)
  await expect(page.getByTestId('legal-text')).toBeVisible()
  await expect(page.getByTestId('legal-body')).toContainText('TEST PLACEHOLDER')
  await expect(page.getByTestId('legal-governing')).toContainText('Japanese')

  await page.goto('/sponsor/help')
  await page.getByTestId('help-search').fill(sponsorOnly)
  await expect(page.getByTestId('faq-entry').filter({ hasText: sponsorOnly })).toBeVisible()
  await page.goto('/sponsor/legal/sponsor-terms')
  await expect(page.getByTestId('legal-text')).toBeVisible()

  // The drafts are never public: only the four public templates answer without a token.
  const draft = await request.post('/api/datasets/urn:jabiz:dataset:default:QbLegalDoc/query', { data: {} })
  expect(draft.status()).toBe(401)
  expect((await request.get('/api/public/queries/qb.admin.consents')).status()).toBe(404)
})

test('a new taker accepts the terms in effect on the welcome step', async ({ page, request }) => {
  const email = await verifiedUser(request, 'app', 'Nao')
  await signIn(page, '/taker/', email)
  await expect(page).toHaveURL(/\/welcome$/)
  await expect(page.getByTestId('profile-save')).toBeDisabled()
  await page.getByTestId('profile-terms').click()
  await page.getByTestId('profile-save').click()
  await expect(page.getByTestId('home')).toBeVisible()
  const token = await tokenOf(request, email, PASSWORD, 'app')
  expect((await consentsOf(request, token, ['TERMS_APP', 'PRIVACY'])).map((row) => row.mustAccept))
    .toEqual([false, false])
})

test('after a change of substance a taker accepts again on signing in; a rewording asks nothing', async ({ page,
  request }) => {
  const email = await verifiedUser(request, 'app', 'Rio')
  const taker = await tokenOf(request, email, PASSWORD, 'app')
  // Registration's second step, accepting the texts in effect (the helper sends their versions).
  await run(request, taker, 'QB_PROFILE_SAVE', { displayName: 'Rio', countryCode: 'JP', preferredCurrency: 'JPY' })
  const { token: content } = await legalAdmin(request)

  await publishVersion(request, content, 'TERMS_APP', false, unique('wording '))
  await signIn(page, '/taker/', email)
  await expect(page.getByTestId('home')).toBeVisible()
  await expect(page.getByTestId('consent-gate')).toHaveCount(0)

  const version = await publishVersion(request, content, 'TERMS_APP', true, unique('substance '))
  // Money and contracts wait (opening a payout account here), whatever the pages do.
  const refused = await request.post('/api/processes/QB_PAYOUT_ONBOARD/latest', {
    headers: { Authorization: `Bearer ${taker}`, 'Idempotency-Key': crypto.randomUUID() }, data: {} })
  expect(refused.status()).toBe(422)
  expect(await refused.text()).toContain('QB_CONSENT_REQUIRED')

  // The page read the texts before the change: the server's refusal brings the prompt up at once (review M3).
  await page.getByTestId('nav-wallet').click()
  await page.getByTestId('wallet-payout').click()
  await page.getByTestId('payout-onboard').click()
  const gate = page.getByTestId('consent-gate')
  await expect(gate).toBeVisible()
  await expect(gate.getByTestId('consent-item-TERMS_APP')).toContainText(`Version ${version}`)
  await expect(gate.getByTestId('consent-item-TERMS_APP')).toContainText('Changed for the tests')
  await expect(page.getByTestId('nav-home')).toHaveCount(0)
  await expect(page.getByTestId('consent-accept')).toBeDisabled()
  await page.getByTestId('consent-agree').click()
  await page.getByTestId('consent-accept').click()
  await expect(gate).toHaveCount(0)
  await expect(page.getByTestId('payout-onboard')).toBeVisible()
  const after = await request.post('/api/processes/QB_PAYOUT_ONBOARD/latest', {
    headers: { Authorization: `Bearer ${taker}`, 'Idempotency-Key': crypto.randomUUID() }, data: {} })
  expect(await after.text()).not.toContain('QB_CONSENT_REQUIRED')
})

test('a version announced by mistake is withdrawn before it takes effect, and the pages say so', async ({ page,
  request }) => {
  const { token: content } = await legalAdmin(request)
  // A month ahead: well within the announcement horizon (qb.legal.max-announce-days, 180 by default).
  const later = new Date(Date.now() + 30 * 24 * 3600_000).toISOString()
  const announced = await announceVersion(request, content, 'COMMERCE_NOTICE', unique('mistake '), later)
  const withdrawn = await withdrawVersion(request, content, String(announced.versionId), 'Announced by mistake')
  expect(withdrawn.versionNo).toBe(announced.versionNo)

  // Read afterwards (the public answers are cached by the browser for a few minutes).
  await page.goto('/sponsor/legal/commerce')
  await expect(page.getByTestId('legal-history')).toBeVisible()
  await expect(page.getByTestId('legal-upcoming')).toHaveCount(0)
  const row = page.getByTestId(`legal-history-${announced.versionNo}`)
  await expect(row.getByTestId('legal-withdrawn')).toBeVisible()
  await row.getByRole('link').click()
  await expect(page.getByTestId('legal-withdrawn-note')).toBeVisible()
})
