import type { APIRequestContext } from '@playwright/test'
import { adminToken, expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique } from './support'
import { fundedSponsor, searchHome, taker, untilLive } from './play'

/*
 * F-S10 (docs/quizbuks/plans/impl/F-S10.md): publications with a YouTube Shorts video appear in the home's shelf of
 * short videos; a tap opens the full-screen vertical mode, which moves one by one (a swipe, the buttons, the keys) and
 * leads to the start page. The search narrows the home to this test's publications, so the shelf (its first place, 3,
 * beyond the two cards) closes the list.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** A live publication of the sponsor's, of a quiz whose video is a YouTube Shorts link. */
async function shortsPublication(request: APIRequestContext, sponsor: { token: string; balance: number }, name: string,
  video: string) {
  const quiz = await run(request, sponsor.token, 'QB_QUIZ_SAVE', { title: `${name} quiz`, timeLimitSec: 300 })
  const quizId = quiz.quizId as string
  await run(request, sponsor.token, 'QB_MATERIAL_SAVE', { quizId, kind: 'VIDEO_LINK', title: 'Short', url: video })
  await run(request, sponsor.token, 'QB_QUESTION_SAVE', { quizId, stem: 'What did the short show?', points: 1,
    options: [{ text: 'A song', correct: true }, { text: 'A map' }] })
  const version = await run(request, sponsor.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  const now = Date.now()
  const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', {
    versionId: version.versionId, name, regions: ['GLOBAL'],
    startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
    timeLimitSec: 300, passScore: 1, tiers: [{ fromScore: 0, toScore: 0, reward: 0 },
      { fromScore: 1, toScore: 1, reward: Math.floor(sponsor.balance / 4) }],
  })
  const publicationId = saved.publicationId as string
  const submitted = await run(request, sponsor.token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  if (submitted.approval === 'PENDING') {
    await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId,
      decision: 'APPROVE' })
  }
  return publicationId
}

test('a Shorts publication is in the shelf; the vertical mode swipes on and leads to answering', async ({ page, request }) => {
  test.setTimeout(180_000)
  // No YouTube here: the player is a blank page.
  await page.route('https://www.youtube-nocookie.com/**', (route) => route.fulfill({ contentType: 'text/html',
    body: '<!doctype html><html lang="en"><title>Player</title></html>' }))
  const sponsor = await fundedSponsor(request)
  const base = unique('Shorts run ')
  const first = await shortsPublication(request, sponsor, `${base} one`, 'https://www.youtube.com/shorts/dQw4w9WgXcQ')
  const second = await shortsPublication(request, sponsor, `${base} two`, 'https://youtube.com/shorts/aaaaaaaaaaa')
  const me = await taker(request, 'Sora')
  await untilLive(request, me.token, first)
  await untilLive(request, me.token, second)

  await page.setViewportSize({ width: 390, height: 844 })
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()
  await searchHome(page, base)
  const shelf = page.getByTestId('shorts-shelf')
  await expect(shelf).toHaveCount(1)
  await expect(shelf.getByTestId('shorts-card')).toHaveCount(2)
  await expectAccessible(page, 'app home with a shelf of short videos')

  // The newest first, as the home: the second one, then the first.
  await shelf.getByTestId('shorts-card-open').first().click()
  const viewer = page.getByTestId('shorts-viewer')
  await expect(viewer).toBeVisible()
  await expect(viewer.getByTestId('shorts-position')).toHaveText(/1/)
  await expect(page.locator('iframe')).toHaveCount(1)
  await expectAccessible(page, 'app vertical mode')
  // A swipe up shows the next one; the keys and the buttons move as well.
  const screens = viewer.getByTestId('shorts-screens')
  await screens.hover()
  await page.mouse.wheel(0, 900)
  await expect(viewer.locator('[data-testid="shorts-screen"][data-current]')).toHaveAttribute('data-publication', first)
  await page.keyboard.press('ArrowUp')
  await expect(viewer.locator('[data-testid="shorts-screen"][data-current]')).toHaveAttribute('data-publication', second)
  await viewer.getByTestId('shorts-next').click()
  const current = viewer.locator('[data-testid="shorts-screen"][data-current]')
  await expect(current).toHaveAttribute('data-publication', first)
  await expect(page.locator('iframe')).toHaveCount(1)

  // Back to the home, where it was; then again, and on to answering.
  await page.keyboard.press('Escape')
  await expect(viewer).toHaveCount(0)
  await expect(shelf).toBeVisible()
  await shelf.getByTestId('shorts-card-open').last().click()
  await page.getByTestId('shorts-viewer').locator('[data-current]').getByTestId('shorts-answer').click()
  await expect(page).toHaveURL(new RegExp(`/taker/p/${first}$`))
  await expect(page.getByTestId('detail-start')).toBeVisible()
})
