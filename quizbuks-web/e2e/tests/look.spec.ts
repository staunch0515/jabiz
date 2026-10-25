import { expect, prepareQuizbuks, signIn, test, unique } from './support'
import { fundedSponsor, taker } from './play'
import { livePublication } from './publishing'
import { setAppearance } from '@jabiz/ui/testing/playwright'

const OUT = process.env.LOOK_OUT ?? '/tmp/look'

/** Screenshots of the refreshed pages (not a test of behaviour; local only, never committed). */
test('screenshots of the app', async ({ page, request }) => {
  test.setTimeout(600_000)
  await prepareQuizbuks(request)
  const sponsor = await fundedSponsor(request)
  for (const title of ['World geography', 'Coffee basics at Quiz Co', 'Science of sleep', 'Tokyo street food', 'Cycling safety', 'Home energy saving']) {
    const quiz = await request.post('/api/processes/QB_QUIZ_SAVE/latest', { headers: { Authorization: `Bearer ${sponsor.token}` },
      data: { title, intro: null, timeLimitSec: 300 } }).then((r) => r.json())
    const quizId = quiz.output.quizId as string
    await request.post('/api/processes/QB_QUESTION_SAVE/latest', { headers: { Authorization: `Bearer ${sponsor.token}` },
      data: { quizId, stem: 'Capital of France?', points: 1, options: [{ text: 'Paris', correct: true }, { text: 'Lyon' }] } })
    await request.post('/api/processes/QB_QUESTION_SAVE/latest', { headers: { Authorization: `Bearer ${sponsor.token}` },
      data: { quizId, stem: 'Largest ocean?', points: 1, options: [{ text: 'Pacific', correct: true }, { text: 'Atlantic' }] } })
    await request.post('/api/processes/QB_QUIZ_PUBLISH_VERSION/latest', { headers: { Authorization: `Bearer ${sponsor.token}` }, data: { quizId } })
    await livePublication(request, sponsor.token, quizId, unique(`${title} `).slice(0, 40))
  }
  const me = await taker(request, 'Mei')
  for (const appearance of ['light', 'dark'] as const) {
    await setAppearance(page, appearance)
    await page.setViewportSize({ width: 390, height: 844 })
    await page.context().clearCookies()
    await page.goto('/taker/')
    await page.evaluate(() => { window.localStorage.removeItem('qb.intro.seen'); window.sessionStorage.clear() })
    await page.goto('/taker/')
    await expect(page.getByTestId('intro')).toBeVisible()
    await page.screenshot({ path: `${OUT}/intro-1-${appearance}.png` })
    await page.getByTestId('intro-next').click()
    await page.screenshot({ path: `${OUT}/intro-2-${appearance}.png` })
    await page.getByTestId('intro-next').click()
    await page.screenshot({ path: `${OUT}/intro-3-${appearance}.png` })
    await page.getByTestId('intro-next').click()
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible()
    await page.screenshot({ path: `${OUT}/login-${appearance}.png` })
    await signIn(page, '/taker/', me.email)
    await expect(page.getByTestId('feed-card').first()).toBeVisible()
    await page.waitForTimeout(500)
    await page.screenshot({ path: `${OUT}/home-${appearance}.png` })
    await page.getByTestId('feed-card').first().click()
    await expect(page.getByTestId('detail')).toBeVisible()
    await page.screenshot({ path: `${OUT}/detail-${appearance}.png` })
    await page.getByTestId('detail-start').click()
    await expect(page.getByTestId('play-progress')).toBeVisible()
    await page.getByRole('checkbox', { name: 'Paris' }).click()
    await page.getByTestId('play-next').click().catch(() => {})
    await page.getByRole('checkbox', { name: 'Pacific' }).click().catch(() => {})
    await page.getByTestId('play-submit').click()
    await page.getByRole('alertdialog').getByRole('button', { name: 'Submit' }).click()
    await expect(page.getByTestId('result-score')).toBeVisible()
    await page.screenshot({ path: `${OUT}/result-${appearance}.png` })
    await page.goto('/taker/history')
    await expect(page.getByTestId('history')).toBeVisible()
    await page.waitForTimeout(500)
    await page.screenshot({ path: `${OUT}/history-${appearance}.png` })
    await page.goto('/taker/account')
    await expect(page.getByTestId('account-title')).toBeVisible().catch(() => {})
    await page.waitForTimeout(500)
    await page.screenshot({ path: `${OUT}/account-${appearance}.png` })
    await page.goto('/sponsor/login')
    await signIn(page, '/sponsor/', sponsor.email)
    await expect(page.getByTestId('dashboard-status')).toBeVisible()
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.waitForTimeout(500)
    await page.screenshot({ path: `${OUT}/sponsor-dashboard-${appearance}.png` })
    await page.context().clearCookies()
    await page.evaluate(() => window.sessionStorage.clear())
  }
})
