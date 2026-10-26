import { adminToken, prepareQuizbuks, run, signIn, test, unique, expect } from './support'
import { fundedSponsor, taker, untilLive } from './play'
import { png, POLICY, upload } from './content'

// Temporary (not committed): screenshots of Q34 for the requester.
const OUT = process.env.SHOTS_DIR!

test('q34 shots', async ({ page, request, browser }) => {
  test.setTimeout(300_000)
  await prepareQuizbuks(request)
  const sponsor = await fundedSponsor(request)
  const cover = await upload(request, sponsor.token, POLICY.image, 'cover.png', 'image/png', png(640, [40, 120, 90]))
  const quiz = await run(request, sponsor.token, 'QB_QUIZ_SAVE', { title: 'Coffee origins',
    intro: 'Five short questions about **where coffee grows**, how it is roasted and how to brew it at home. Read the guide first: every answer is in it. Good luck, and enjoy your coffee!',
    cover, timeLimitSec: 300 })
  const quizId = quiz.quizId as string
  await run(request, sponsor.token, 'QB_MATERIAL_SAVE', { quizId, kind: 'ARTICLE', title: 'Bean guide',
    description: 'Arabica, robusta and where they grow', body: '# Beans\n\nArabica is **sweet** and grows high up.\n\nRobusta is strong.' })
  await run(request, sponsor.token, 'QB_MATERIAL_SAVE', { quizId, kind: 'LINK', title: 'Our shop',
    url: 'https://example.com/shop' })
  await run(request, sponsor.token, 'QB_QUESTION_SAVE', { quizId, stem: 'Which bean is sweeter?', points: 1,
    options: [{ text: 'Arabica', correct: true }, { text: 'Robusta' }] })
  await run(request, sponsor.token, 'QB_QUESTION_SAVE', { quizId, stem: 'Where does arabica grow best?', points: 1,
    options: [{ text: 'High up', correct: true }, { text: 'At sea level' }] })
  const version = await run(request, sponsor.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  const now = Date.now()
  const ids: string[] = []
  for (const name of [unique('Coffee week '), unique('Roast quiz ')]) {
    const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', { versionId: version.versionId, name,
      regions: ['GLOBAL'], startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
      timeLimitSec: 300, passScore: 2, tiers: [{ fromScore: 0, toScore: 1, reward: 0 }, { fromScore: 2, toScore: 2, reward: 100 }] })
    const id = saved.publicationId as string
    const submitted = await run(request, sponsor.token, 'QB_PUBLICATION_SUBMIT', { publicationId: id })
    if (submitted.approval === 'PENDING') {
      await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId,
        decision: 'APPROVE' })
    }
    ids.push(id)
  }
  const me = await taker(request, 'Ann')
  for (const id of ids) await untilLive(request, me.token, id)

  const shot = async (p: typeof page, name: string, full = true) => {
    await p.waitForTimeout(700)
    await p.screenshot({ path: `${OUT}/${name}.png`, fullPage: full })
  }
  // Business site.
  await page.setViewportSize({ width: 1366, height: 800 })
  await page.goto('/biz/zh/')
  await shot(page, '01-biz-header', false)
  // Taker app in the phone-sized window.
  await page.setViewportSize({ width: 468, height: 844 })
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home-hero')).toBeVisible()
  await page.getByTestId('feed-card').first().waitFor()
  await shot(page, '02-taker-home-greeting', false)
  await page.waitForTimeout(3000)
  await shot(page, '03-taker-home', false)
  await page.getByTestId('feed-method').click()
  await shot(page, '04-taker-reward-menu', false)
  await page.keyboard.press('Escape')
  await page.getByTestId('feed-filters-open').click()
  await shot(page, '05-taker-filters', false)
  await page.keyboard.press('Escape')
  await page.goto(`/taker/p/${ids[0]}`)
  await page.getByTestId('detail-hero').waitFor()
  await shot(page, '06-taker-quiz-page')
  await page.getByTestId('material-open').first().click()
  await shot(page, '07-taker-material', false)
  // Sponsor console.
  const console = await (await browser.newContext({ viewport: { width: 1366, height: 860 } })).newPage()
  await signIn(console, '/sponsor/', sponsor.email)
  await console.waitForURL(/dashboard|onboarding/)
  await shot(console, '10-sponsor-dashboard')
  await console.goto('/sponsor/quizzes')
  await console.getByTestId('quiz-cards').waitFor()
  await shot(console, '11-sponsor-quizzes')
  await console.goto('/sponsor/publications')
  await console.getByTestId('publication-cards').waitFor()
  await shot(console, '12-sponsor-publications')
  await console.goto(`/sponsor/publications/${ids[0]}`)
  await console.getByTestId('publication-view').waitFor()
  await shot(console, '13-sponsor-publication')
  await console.getByTestId('preview-open').click()
  await console.getByTestId('preview-frame').waitFor()
  await shot(console, '14-sponsor-preview-page')
  await console.getByRole('radio', { name: 'Home card' }).click()
  await shot(console, '15-sponsor-preview-card')
  await console.getByRole('radio', { name: 'Questions' }).click()
  await shot(console, '16-sponsor-preview-questions')
  await console.goto(`/sponsor/quizzes/${quizId}`)
  await console.getByTestId('heading-form').waitFor()
  await shot(console, '17-sponsor-quiz-editor')
})
