import { decideReview, livePublication, publicationOf } from './publishing'
import { fundedSponsor, taker } from './play'
import { expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique } from './support'

/**
 * Prerequisites (docs/quizbuks/plans/Q76-prerequisites.md): the sponsor requires a pass of "Basics" before "Advanced"
 * in the wizard's step "Customer matching"; a taker who has not answered the basics is shown what to finish first and
 * led to it, passes it, and can then start the advanced quiz.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

async function choose(page: import('@playwright/test').Page, text: string) {
  const saved = page.waitForResponse((r) => r.url().includes('/api/processes/QB_ATTEMPT_SAVE/') && r.ok())
  await page.getByRole('radio', { name: text }).click()
  await saved
}

test('a sponsor requires one quiz before another; a taker passes the first and unlocks the second', async ({ page,
  request }) => {
  test.setTimeout(300_000)
  const sponsor = await fundedSponsor(request)
  const quiz = await run(request, sponsor.token, 'QB_QUIZ_SAVE', { title: 'Coffee basics', intro: null,
    timeLimitSec: 300 })
  const quizId = quiz.quizId as string
  await run(request, sponsor.token, 'QB_QUESTION_SAVE', { quizId, stem: 'Which bean is most grown?', points: 1,
    options: [{ text: 'Arabica', correct: true }, { text: 'Liberica' }] })
  await run(request, sponsor.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  const basicsName = unique('Basics ')
  const basics = await livePublication(request, sponsor.token, quizId, basicsName)
  const versionId = (await publicationOf(request, sponsor.token, basics)).versionId as string
  const advancedName = unique('Advanced ')
  const now = Date.now()
  const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', { versionId, name: advancedName,
    regions: ['GLOBAL'], startAt: new Date(now - 60_000).toISOString(),
    endAt: new Date(now + 7 * 86_400_000).toISOString(), timeLimitSec: 300, passScore: 1,
    tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 1, reward: 10 }] })
  const advanced = saved.publicationId as string

  // The sponsor: the step "Customer matching", the basics ticked as a prerequisite, and the summary.
  await signIn(page, '/sponsor/', sponsor.email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(`/sponsor/publications/${advanced}?step=2`)
  await expect(page.getByTestId('publication-step-title')).toHaveText('Customer matching')
  const row = page.getByTestId('prerequisite').filter({ hasText: basicsName })
  await row.getByTestId('prerequisite-on').click()
  await expect(row.getByTestId('prerequisite-PASSED')).toHaveAttribute('aria-checked', 'true')
  await expectAccessible(page, 'sponsor prerequisites')
  await page.getByTestId('publication-next').click()
  await expect(page.getByTestId('summary-prerequisites')).toHaveText(`${basicsName} (Passed)`)
  await expect(page.getByTestId('publication-ready')).toBeVisible()

  const submitted = await run(request, sponsor.token, 'QB_PUBLICATION_SUBMIT', { publicationId: advanced })
  if (submitted.approval === 'PENDING') await decideReview(request, sponsor.token, advanced, 'APPROVE')
  await expect.poll(async () => (await publicationOf(request, sponsor.token, advanced)).status,
    { message: 'the publication is live', timeout: 60_000 }).toBe('LIVE')

  // A taker who has not answered the basics: what to finish first, leading to it; no start.
  const me = await taker(request, 'Bea')
  await signIn(page, '/taker/', me.email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(`/taker/p/${advanced}`)
  const notice = page.getByTestId('detail-prerequisites')
  await expect(notice).toBeVisible()
  await expect(page.getByTestId('detail-start')).toHaveCount(0)
  const first = notice.getByTestId('detail-prerequisite')
  await expect(first).toHaveAttribute('data-met', 'false')
  await expectAccessible(page, 'app quiz with prerequisites')
  await first.getByRole('link').click()
  await expect(page).toHaveURL(new RegExp(`/taker/p/${basics}$`))

  // Passing the basics.
  await page.getByTestId('detail-start').click()
  await expect(page).toHaveURL(/\/play\/[^/]+$/)
  await choose(page, 'Arabica')
  await page.getByTestId('play-submit').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Submit answers' }).click()
  await expect(page).toHaveURL(/\/result\/[^/]+$/)
  await expect(page.getByTestId('result-passed')).toHaveText('Passed')

  // The advanced quiz opens.
  await page.goto(`/taker/p/${advanced}`)
  await expect(page.getByTestId('detail-prerequisites')).toHaveCount(0)
  await page.getByTestId('detail-start').click()
  await expect(page).toHaveURL(/\/play\/[^/]+$/)
})
