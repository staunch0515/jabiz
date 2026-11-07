import { decideReview, livePublication, publicationOf } from './publishing'
import { operatorWithLead } from './operators'
import { fund, fundedSponsor, taker } from './play'
import { expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique } from './support'

/*
 * The agreement quiz (docs/quizbuks/plans/Q86-agreement-quiz-tree.md): headquarters opens a round of "Understood:
 * Contest Rules" on an official account; a taker is offered the quiz on the home, answers it and holds the
 * certification on the account page; a sponsor requires that certification in the wizard's "Customer matching"
 * (a node of the platform's tree); a taker without it is told what to hold first, and the holder can start.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

const CERTIFICATION = 'CONTEST_RULES_UNDERSTOOD'

async function choose(page: import('@playwright/test').Page, text: string) {
  const saved = page.waitForResponse((r) => r.url().includes('/api/processes/QB_ATTEMPT_SAVE/') && r.ok())
  await page.getByRole('radio', { name: text }).click()
  await saved
}

/** A live quiz of one question, "Paris" right, under the sponsor's account; returns the publication's id. */
async function liveQuiz(request: import('@playwright/test').APIRequestContext, token: string, name: string) {
  const quiz = await run(request, token, 'QB_QUIZ_SAVE', { title: name, intro: null, timeLimitSec: 300 })
  const quizId = quiz.quizId as string
  await run(request, token, 'QB_QUESTION_SAVE', { quizId, stem: 'Capital of France?', points: 1,
    options: [{ text: 'Paris', correct: true }, { text: 'Lyon' }] })
  await run(request, token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  return { quizId, publicationId: await livePublication(request, token, quizId, name, 1) }
}

test('a taker answers the contest rules quiz from the home; a sponsor requires the certification (Q86)', async ({ page,
  request }) => {
  test.setTimeout(300_000)
  // An operator: its own sponsor (its lead's account, F-S1) publishes a live round of the certification. Every
  // question of every round reads the same, so the card may lead to a round of an earlier run.
  const operator = await operatorWithLead(request, 'Offi')
  await fund(request, operator.sponsorToken)
  const round = await liveQuiz(request, operator.sponsorToken, unique('Contest rules '))
  await run(request, operator.operatorToken, 'QB_CERTIFICATION_ROUND_ADD', { publicationId: round.publicationId,
    certification: CERTIFICATION })

  // A sponsor requires the certification before their own quiz: in the wizard, as a certification of the platform.
  const sponsor = await fundedSponsor(request)
  const basics = await liveQuiz(request, sponsor.token, unique('Gated basics '))
  const versionId = (await publicationOf(request, sponsor.token, basics.publicationId)).versionId as string
  const gatedName = unique('Gated ')
  const now = Date.now()
  const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', { versionId, name: gatedName,
    regions: ['GLOBAL'], startAt: new Date(now - 60_000).toISOString(),
    endAt: new Date(now + 7 * 86_400_000).toISOString(), timeLimitSec: 300, passScore: 1,
    tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 1, reward: 10 }] })
  const gated = saved.publicationId as string
  await signIn(page, '/sponsor/', sponsor.email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(`/sponsor/publications/${gated}?step=2`)
  await expect(page.getByTestId('publication-step-title')).toHaveText('Customer matching')
  const certification = page.getByTestId('prerequisite-certification').filter({ hasText: 'Understood: Contest Rules' })
  await certification.getByTestId('prerequisite-certification-on').click()
  await expect(certification).toHaveAttribute('data-chosen', 'true')
  await expectAccessible(page, 'sponsor certification prerequisite')
  await page.getByTestId('publication-next').click()
  await expect(page.getByTestId('summary-prerequisites')).toHaveText('Understood: Contest Rules (Held)')
  const submitted = await run(request, sponsor.token, 'QB_PUBLICATION_SUBMIT', { publicationId: gated })
  if (submitted.approval === 'PENDING') await decideReview(request, sponsor.token, gated, 'APPROVE')
  await expect.poll(async () => (await publicationOf(request, sponsor.token, gated)).status,
    { message: 'the gated publication is live', timeout: 60_000 }).toBe('LIVE')
  // The sponsor's tree draws the certification as a platform node the quiz hangs from.
  await page.goto('/sponsor/tree')
  const tree = page.getByTestId('tree-page')
  await expect(tree.getByTestId('tech-tree-node').filter({ hasText: 'Understood: Contest Rules' }))
    .toHaveAttribute('data-kind', 'certification')
  await expect(tree.getByTestId('tech-tree-edge')).toHaveCount(1)

  // A taker without the certification: the gated quiz says what to hold first; the home offers the quiz.
  const me = await taker(request, unique('Cara '))
  await signIn(page, '/taker/', me.email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(`/taker/p/${gated}`)
  const notice = page.getByTestId('detail-prerequisites')
  await expect(notice).toBeVisible()
  await expect(page.getByTestId('detail-start')).toHaveCount(0)
  const first = notice.getByTestId('detail-prerequisite')
  await expect(first).toHaveAttribute('data-met', 'false')
  await expect(first).toHaveAttribute('data-certification', CERTIFICATION)
  await expectAccessible(page, 'app quiz gated by a certification')

  await page.goto('/taker/')
  const card = page.getByTestId('home-understanding')
    .locator(`[data-testid="understanding-card"][data-certification="${CERTIFICATION}"]`)
  await expect(card).toBeVisible()
  await expectAccessible(page, 'app home with the understanding card')
  await card.getByTestId('understanding-start').click()
  await expect(page).toHaveURL(/\/taker\/p\/[^/]+$/)
  await page.getByTestId('detail-start').click()
  await expect(page).toHaveURL(/\/play\/[^/]+$/)
  await choose(page, 'Paris')
  await page.getByTestId('play-submit').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Submit answers' }).click()
  await expect(page).toHaveURL(/\/result\/[^/]+$/)
  await expect(page.getByTestId('result-passed')).toHaveText('Passed')

  // Held: the account says so, the home no longer offers it, and the gated quiz opens.
  await page.goto('/taker/account/certificates')
  const held = page.getByTestId('account-understanding')
    .locator(`[data-testid="understanding-card"][data-certification="${CERTIFICATION}"]`)
  await expect(held).toHaveAttribute('data-held', 'true')
  await expect(page.getByTestId(`certificate-${CERTIFICATION}`)).toBeVisible()
  await expectAccessible(page, 'app account with a certification held')
  await page.goto('/taker/')
  await expect(page.getByTestId('home')).toBeVisible()
  await expect(page.getByTestId('home-understanding')
    .locator(`[data-testid="understanding-card"][data-certification="${CERTIFICATION}"]`)).toHaveCount(0)
  await page.goto(`/taker/p/${gated}`)
  await expect(page.getByTestId('detail-prerequisites')).toHaveCount(0)
  await page.getByTestId('detail-start').click()
  await expect(page).toHaveURL(/\/play\/[^/]+$/)
})
