import { signInAdmin } from './admin'
import { contentAdmin } from './legal'
import { ensureDefaultOperator } from './operators'
import { fundedSponsor } from './play'
import { expect, expectAccessible, PASSWORD, prepareQuizbuks, run, signIn, test, unique } from './support'

/**
 * Audience rules as a product line (docs/quizbuks/plans/Q47c-audience-billing.md, Q47c-1): headquarters' rule
 * catalog and the operators' price lists in the administration, and the sponsor's wizard, which shows no prices
 * while charging is off (the end-to-end job never turns it on: the other tests' publications stay free).
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('headquarters manage the rules and their prices; the wizard stays free while charging is off', async ({ page,
  request }) => {
  test.setTimeout(180_000)
  const admin = await contentAdmin(request, 'Rina')
  await signInAdmin(page, admin, PASSWORD)

  // The rule catalog: every kind, saved as it is (other runs may have saved it before).
  await page.goto('/admin/qb/audience/rules')
  await expect(page.getByTestId('audience-rule-row')).toHaveCount(4)
  await expectAccessible(page, 'admin audience rules')
  await page.getByTestId('audience-rules-save').click()
  await expect(page.getByText('The rules are saved.')).toBeVisible()

  // An operator's price list, set by headquarters (F-M5 removed headquarters' own `direct` list): nothing is shown
  // until an operator is chosen; a reason is required; the list is saved with it.
  const operatorId = await ensureDefaultOperator(request)
  await page.goto('/admin/qb/audience/prices')
  await expect(page.getByTestId('price-list-pick')).toBeVisible()
  await expect(page.getByTestId('price-list-form')).toHaveCount(0)
  await page.getByTestId('price-list-operator-id').fill(operatorId)
  await expect(page.getByTestId('price-list-form')).toBeVisible()
  await expectAccessible(page, 'admin audience prices')
  await page.getByTestId('price-list-reason').fill(unique('End-to-end prices '))
  await page.getByTestId('price-list-save').click()
  await expect(page.getByText('The prices are saved.')).toBeVisible()
  await expect(page.getByTestId('price-list-last-change')).toBeVisible()

  // The sponsor: the matching step lists every rule without prices, and no fee.
  const sponsor = await fundedSponsor(request)
  const quiz = await run(request, sponsor.token, 'QB_QUIZ_SAVE', { title: 'Priced rules', intro: null,
    timeLimitSec: 300 })
  await run(request, sponsor.token, 'QB_QUESTION_SAVE', { quizId: quiz.quizId, stem: 'Is this free?', points: 1,
    options: [{ text: 'Yes', correct: true }, { text: 'No' }] })
  const version = await run(request, sponsor.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId: quiz.quizId })
  const now = Date.now()
  const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', { versionId: version.versionId,
    name: unique('Priced '), regions: ['GLOBAL'], startAt: new Date(now + 86_400_000).toISOString(),
    endAt: new Date(now + 8 * 86_400_000).toISOString(), timeLimitSec: 300, passScore: 1,
    tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 1, reward: 10 }] })
  const quote = await run(request, sponsor.token, 'QB_AUDIENCE_FEE_QUOTE', { publicationId: saved.publicationId })
  expect(quote.quote).toMatchObject({ enabled: false, fee: 0, days: 7 })
  await signIn(page, '/sponsor/', sponsor.email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(`/sponsor/publications/${saved.publicationId}?step=2`)
  await expect(page.getByTestId('audience-gender')).toBeVisible()
  await expect(page.getByTestId('audience-gender-price')).toHaveCount(0)
  await expect(page.getByTestId('audience-fee')).toHaveCount(0)
})
