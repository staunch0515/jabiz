import type { APIRequestContext } from '@playwright/test'
import { query } from './content'
import { reviewerFor } from './operators'
import { fundedSponsor, searchHome, taker } from './play'
import { boundPublication, operatorTree, pass } from './tree'
import { expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique } from './support'

/*
 * A node's three states for a taker (F-S2d, docs/quizbuks/first-launch-spec.md F-S2): a publication bound to a node
 * whose prerequisites the taker has not lit is as if it did not exist (not on the home, its page not found); once the
 * prerequisite is lit it appears, open to be lit by passing; once passed, its page says the node is lit.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** A Sponsor's map in effect: the basics (a pass) and the advanced node (the basics lit before answering, a pass). */
async function sponsorMap(request: APIRequestContext, token: string) {
  const tree = await run(request, token, 'QB_TREE_CREATE', { ownerKind: 'SPONSOR', name: { en: 'Coffee' } })
  const saved = await run(request, token, 'QB_TREE_DRAFT_SAVE', { treeId: tree.treeId, baseRevision: tree.revision,
    name: { en: 'Coffee' }, nodes: [
      { key: 'basics', name: { en: 'Coffee basics' }, condition: { op: 'ALL', items: [{ passed: true }] } },
      { key: 'advanced', name: { en: 'Latte art' }, condition: { op: 'ALL', items: [
        { node: 'basics', beforeAnswer: true }, { passed: true }] } },
    ] })
  expect(saved.problems).toEqual([])
  const ids = saved.nodeIds as Record<string, string>
  const submitted = await run(request, token, 'QB_TREE_SUBMIT', { treeId: tree.treeId, baseRevision: saved.revision })
  // The Sponsor's operator reviews it (another member, as in production); the map is in effect once the decision's
  // event is delivered.
  const reviewer = await reviewerFor(request, token)
  await run(request, reviewer, 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId, decision: 'APPROVE' })
  await expect.poll(async () => (await operatorTree(request, reviewer, String(tree.treeId)))?.currentVersionId ?? null,
  { message: 'the map is in effect', timeout: 30_000 }).not.toBeNull()
  return { treeId: String(tree.treeId), basics: ids.basics, advanced: ids.advanced }
}

/** Whether the taker's page of the publication exists (the template gives no row for an invisible one). */
async function seen(request: APIRequestContext, token: string, publicationId: string) {
  return (await query(request, token, 'qb.app.publication', { publicationId, at: new Date().toISOString(),
    country: 'JP' })).length > 0
}

test('a publication is invisible until its prerequisites are lit, then open, then lit', async ({ page, request }) => {
  test.setTimeout(180_000)
  const sponsor = await fundedSponsor(request)
  const map = await sponsorMap(request, sponsor.token)
  const basicsName = unique('Coffee basics ')
  const advancedName = unique('Latte art ')
  const basics = await boundPublication(request, sponsor.token, map.treeId, map.basics, basicsName)
  const advanced = await boundPublication(request, sponsor.token, map.treeId, map.advanced, advancedName)
  const me = await taker(request, 'Mio')

  // 1. Prerequisite not lit: the advanced publication is not on the home, and its page is not found.
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()
  await searchHome(page, advancedName)
  await expect(page.getByTestId('feed-card').filter({ hasText: advancedName })).toHaveCount(0)
  await page.goto(`/taker/p/${advanced}`)
  await expect(page.getByTestId('detail-not-found')).toBeVisible()
  await expect(page.getByTestId('detail-name')).toHaveCount(0)
  const refused = await request.post('/api/processes/QB_ATTEMPT_START/latest', {
    headers: { Authorization: `Bearer ${me.token}`, 'Idempotency-Key': crypto.randomUUID() },
    data: { publicationId: advanced } })
  expect(refused.status()).toBe(404)
  // The basics (no prerequisite) is there, open to be lit.
  await page.goto(`/taker/p/${basics}`)
  await expect(page.getByTestId('detail-node-state')).toHaveAttribute('data-lit', 'false')

  // 2. The basics passed and lit (the lighting follows the answering): the advanced publication appears.
  await pass(request, me.token, basics)
  await expect.poll(() => seen(request, me.token, advanced), { message: 'the basics are lit', timeout: 30_000 })
    .toBe(true)
  await page.goto('/taker/')
  await searchHome(page, advancedName)
  await expect(page.getByTestId('feed-card').filter({ hasText: advancedName })).toHaveCount(1)
  await page.goto(`/taker/p/${advanced}`)
  await expect(page.getByTestId('detail-name')).toHaveText(advancedName)
  await expect(page.getByTestId('detail-node-state')).toHaveAttribute('data-lit', 'false')
  await expect(page.getByTestId('detail-node-state')).toContainText('Latte art')
  await expect(page.getByTestId('detail-start')).toBeVisible()
  await expectAccessible(page, 'app publication bound to a node open to be lit')

  // 3. The advanced one passed: its page says the node is lit.
  await pass(request, me.token, advanced)
  await expect.poll(async () => {
    await page.goto(`/taker/p/${advanced}`)
    return page.getByTestId('detail-node-state').getAttribute('data-lit')
  }, { message: 'the node is lit', timeout: 30_000 }).toBe('true')
  await expect(page.getByTestId('detail-node-state')).toContainText('lit')
  await expectAccessible(page, 'app publication bound to a node lit')
})
