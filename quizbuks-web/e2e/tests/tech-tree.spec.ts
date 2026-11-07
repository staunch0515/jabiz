import { decideReview, livePublication, publicationOf } from './publishing'
import { fundedSponsor, taker } from './play'
import { adminToken, expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique } from './support'
import { startAttempt, submitRight } from './transactions'

/**
 * The tree's pictures (docs/quizbuks/plans/Q85-tech-tree.md): a sponsor requires "Basics" before "Advanced"; a taker
 * passes the basics; the sponsor's tree shows who lit the basics and who could start the advanced quiz, and lights
 * the tree for that one customer; the taker sees the sponsor's tree as they stand on it, led there from the result.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('the Sponsor sees who lit which node; the Taker sees where they stand (Q85)', async ({ page, request }) => {
  test.setTimeout(300_000)
  const sponsor = await fundedSponsor(request)
  const quiz = await run(request, sponsor.token, 'QB_QUIZ_SAVE', { title: 'Coffee', intro: null, timeLimitSec: 300 })
  const quizId = quiz.quizId as string
  await run(request, sponsor.token, 'QB_QUESTION_SAVE', { quizId, stem: 'Capital of France?', points: 1,
    options: [{ text: 'Paris', correct: true }, { text: 'Lyon' }] })
  await run(request, sponsor.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  const basicsName = unique('Tree basics ')
  const basics = await livePublication(request, sponsor.token, quizId, basicsName, 1)
  const versionId = (await publicationOf(request, sponsor.token, basics)).versionId as string
  const advancedName = unique('Tree advanced ')
  const now = Date.now()
  const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', { versionId, name: advancedName,
    regions: ['GLOBAL'], startAt: new Date(now - 60_000).toISOString(),
    endAt: new Date(now + 7 * 86_400_000).toISOString(), timeLimitSec: 300, passScore: 1,
    tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 1, reward: 10 }] })
  const advanced = saved.publicationId as string
  await run(request, sponsor.token, 'QB_PUBLICATION_PREREQUISITES_SAVE', { publicationId: advanced,
    prerequisites: [{ requiredPublicationId: basics, requirement: 'PASSED' }] })
  const submitted = await run(request, sponsor.token, 'QB_PUBLICATION_SUBMIT', { publicationId: advanced })
  if (submitted.approval === 'PENDING') await decideReview(request, sponsor.token, advanced, 'APPROVE')
  await expect.poll(async () => (await publicationOf(request, sponsor.token, advanced)).status,
    { message: 'the advanced publication is live', timeout: 60_000 }).toBe('LIVE')

  // The sponsor's page, published: the taker's tree lives on it.
  const handle = unique('tree-').toLowerCase().replace(/[^a-z0-9-]/g, '')
  await run(request, sponsor.token, 'QB_SPONSOR_PAGE_SAVE', { handle, language: 'en', headline: 'Our coffee quizzes',
    website: 'https://example.com/coffee' })
  const page$ = await run(request, sponsor.token, 'QB_SPONSOR_PAGE_SUBMIT', {})
  if (page$.approvalRequestId) {
    await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: page$.approvalRequestId,
      decision: 'APPROVE', reason: null })
  }
  await expect.poll(async () => {
    const answer = await request.get(`/api/public/queries/qb.public.sponsor-page?p.handle=${handle}`)
    return answer.ok() ? ((await answer.json()).items?.length ?? 0) : 0
  }, { message: 'the page is published', timeout: 60_000 }).toBe(1)

  // A taker passes the basics.
  const bea = await taker(request, 'Bea')
  const attempt = await startAttempt(request, bea.token, basics)
  expect(await submitRight(request, bea.token, attempt)).toBe('PAID')

  // The sponsor's tree: both nodes, the basics lit once, the advanced one startable by one; who, on the node picked.
  await signIn(page, '/sponsor/', sponsor.email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(`/sponsor/tree?node=${advanced}`)
  const tree = page.getByTestId('tree-page')
  const nodes = tree.getByTestId('tech-tree-node')
  await expect(nodes).toHaveCount(2)
  // The advanced node names its prerequisite ("After: Tree basics …"), so nodes are picked by id, not by text.
  const basicsNode = nodes.and(page.locator(`[data-id="${basics}"]`))
  const advancedNode = nodes.and(page.locator(`[data-id="${advanced}"]`))
  await expect(basicsNode.getByTestId('tree-counts')).toContainText('✓ 1')
  await expect(advancedNode.getByTestId('tree-counts')).toContainText('▷ 1')
  await expect(tree.getByTestId('tech-tree-edge')).toHaveCount(1)
  const panel = tree.getByTestId('tree-node-panel')
  await expect(panel.getByTestId('tree-node-name')).toHaveText(advancedName)
  await expectAccessible(page, 'sponsor tree')
  await panel.getByRole('radio', { name: 'Ready 1' }).click()
  const person = panel.getByTestId('tree-person')
  await expect(person).toHaveCount(1)
  await expect(person).toContainText('Bea')
  // The tree as Bea sees it.
  await person.getByTestId('tree-person-light').click()
  await expect(tree.getByTestId('tree-customer-shown')).toContainText('Showing Bea')
  await expect(basicsNode).toHaveAttribute('data-state', 'LIT')
  await expect(advancedNode).toHaveAttribute('data-state', 'UNLOCKED')
  await expectAccessible(page, 'sponsor tree of one customer')

  // The taker: from the result to the sponsor's tree, where the next step is marked.
  await signIn(page, '/taker/', bea.email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(`/taker/result/${attempt.attemptId}`)
  await page.getByTestId('result-next-step').click()
  await expect(page).toHaveURL(new RegExp(`/taker/s/${handle}\\?node=${basics}$`))
  const mine = page.getByTestId('sponsor-page-tree')
  await expect(mine.getByTestId('tree-progress')).toContainText(`Lit 1 of 2 · Next: ${advancedName}`)
  const myNodes = mine.getByTestId('tech-tree-node')
  await expect(myNodes.and(page.locator(`[data-id="${basics}"]`))).toHaveAttribute('data-state', 'LIT')
  await expect(myNodes.and(page.locator(`[data-id="${advanced}"]`))).toHaveAttribute('data-state', 'UNLOCKED')
  await expectAccessible(page, 'taker tree')
  // Into the next quiz from its node.
  await myNodes.and(page.locator(`[data-id="${advanced}"]`)).click()
  await expect(page).toHaveURL(new RegExp(`/taker/p/${advanced}$`))
  await expect(page.getByTestId('detail-start')).toBeVisible()
})
