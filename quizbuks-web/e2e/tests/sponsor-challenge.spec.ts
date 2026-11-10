import type { APIRequestContext } from '@playwright/test'
import { query } from './content'
import { contentAdmin } from './legal'
import { decideAsOperator, operatorWithLead, reviewerFor } from './operators'
import { taker } from './play'
import { boundPublication, operatorTree } from './tree'
import { approvedSponsor } from './wallet'
import {
  adminToken, expect, expectAccessible, PASSWORD, prepareQuizbuks, run, setControlledParam, signIn, signOut, test, tokenOf,
  unique,
} from './support'

/*
 * The first launch's loop, step 6 (docs/quizbuks/first-launch-spec.md F-S4, F-S4c): the platform makes a template
 * Sponsors light, with a node that opens digital prizes and grants granted credit; an operator copies it and puts it in
 * effect; its direct Sponsor publishes a challenge on the node; another Sponsor of the operator answers it on the
 * "challenges" tab and passes: the node is lit, digital prizes may be given, the granted credit is in the account. A
 * Sponsor who does not pass is told to wait for the next challenge; a Taker cannot start a challenge at all.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

const GRANT = 1000
const pass = { op: 'ALL', items: [{ passed: true }] }

/** The platform's template Sponsors light, in effect: one node, opening digital prizes and granting credit. */
async function template(request: APIRequestContext) {
  await setControlledParam(request, 'qb.tree.grant.max-per-node', String(GRANT),
    { type: 'monetary', currency: 'JPY', scale: 0 })
  await setControlledParam(request, 'qb.fee-credit.grant.enabled', 'true')
  const writer = await tokenOf(request, await contentAdmin(request, 'Maps'), PASSWORD, 'admin')
  const tree = await run(request, writer, 'QB_TREE_CREATE', { ownerKind: 'PLATFORM', subjectKind: 'SPONSOR',
    name: { en: unique('Sponsor skills ') } })
  const saved = await run(request, writer, 'QB_TREE_DRAFT_SAVE', { treeId: tree.treeId, baseRevision: tree.revision,
    name: { en: 'Sponsor skills' }, nodes: [{ key: 'prizes', name: { en: 'Digital prizes' }, condition: pass,
      grantAmount: GRANT }] })
  expect(saved.problems).toEqual([])
  const node = (saved.nodeIds as Record<string, string>).prizes
  const submitted = await run(request, writer, 'QB_TREE_SUBMIT', { treeId: tree.treeId, baseRevision: saved.revision })
  // Another administrator reviews it (the rule asks for a holder of qb.tree.platform.write who did not prepare it).
  await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId,
    decision: 'APPROVE' })
  await expect.poll(async () => (await query(request, writer, 'qb.admin.trees', {}))
    .find((row) => row.treeId === tree.treeId)?.currentVersionId ?? null,
  { message: 'the template is in effect', timeout: 30_000 }).not.toBeNull()
  // Lighting the node opens digital prizes (F-S4b's gate).
  await setControlledParam(request, 'qb.tree.gate.digital-prize-node', `${tree.treeId}/${node}`,
    { type: 'text', maxLength: 73 })
  return { treeId: String(tree.treeId), node }
}

/** An approved Sponsor moved to the operator (a Sponsor signs up with the default operator). */
async function sponsorOf(request: APIRequestContext, operatorId: string, name: string) {
  const sponsor = await approvedSponsor(request, name)
  const me = await (await request.get('/api/auth/me', { headers: { Authorization: `Bearer ${sponsor.token}` } })).json()
  const hq = await tokenOf(request, await contentAdmin(request), PASSWORD, 'admin')
  await run(request, hq, 'QB_SPONSOR_OPERATOR_ASSIGN', { userId: me.userId, operatorId, reason: 'Challenge test' })
  return { ...sponsor, token: await tokenOf(request, sponsor.email, PASSWORD, 'sponsor') }
}

test('a Sponsor passes a challenge: the node is lit, digital prizes open, the granted credit arrives',
  async ({ page, request }) => {
    test.setTimeout(300_000)
    const made = await template(request)
    const operator = await operatorWithLead(request, 'Challenge')
    // The operator copies the template; another member puts the copy in effect.
    const copy = await run(request, operator.operatorToken, 'QB_TREE_COPY', { templateTreeId: made.treeId })
    const submitted = await run(request, operator.operatorToken, 'QB_TREE_SUBMIT', { treeId: copy.treeId,
      baseRevision: copy.revision })
    await decideAsOperator(request, operator.sponsorToken, submitted.approvalRequestId, 'APPROVE')
    // The copy is in effect once the decision's event is delivered.
    const reviewer = await reviewerFor(request, operator.sponsorToken)
    await expect.poll(async () => (await operatorTree(request, reviewer, String(copy.treeId)))?.currentVersionId ?? null,
    { message: 'the copy is in effect', timeout: 30_000 }).not.toBeNull()
    // The direct Sponsor publishes a challenge on the copy's node (the copy keeps the template's node ids).
    const name = unique('First prizes ')
    const publicationId = await boundPublication(request, operator.sponsorToken, String(copy.treeId), made.node, name)
    const sponsor = await sponsorOf(request, operator.operatorId, 'Chika')

    // A Taker cannot start it.
    const me = await taker(request, 'Mio')
    const refused = await request.post('/api/processes/QB_ATTEMPT_START/latest', {
      headers: { Authorization: `Bearer ${me.token}`, 'Idempotency-Key': crypto.randomUUID() },
      data: { publicationId } })
    expect(refused.status()).toBe(422)

    // The Sponsor finds it on the challenges tab, by the node.
    await signIn(page, '/sponsor/', sponsor.email)
    await page.goto('/sponsor/profile/challenges')
    const map = page.locator(`[data-testid="challenge-map"][data-tree="${copy.treeId}"]`)
    await expect(map).toBeVisible()
    await map.locator(`[data-testid="knowledge-map-node"][data-node-id="${made.node}"]`).click()
    await expect(map.getByTestId('knowledge-map-grant')).toBeVisible()
    await expectAccessible(page, 'sponsor challenges')
    await map.getByTestId('challenge-start').click()

    // Answered with the shared player, from the keyboard.
    await expect(page.getByTestId('play-question')).toContainText('Capital of France?')
    await page.getByTestId('play-option').filter({ hasText: 'Paris' }).click()
    await page.getByTestId('play-submit').focus()
    await page.keyboard.press('Enter')
    await page.getByRole('button', { name: 'Submit answers' }).last().click()

    // Passed: the node is lit (after the answer), the digital prizes and the granted credit are said.
    const result = page.getByTestId('challenge-result')
    await expect(result).toHaveAttribute('data-passed', 'true')
    await expect(result.getByTestId('challenge-grant')).toBeVisible({ timeout: 60_000 })
    await expect(result.getByTestId('challenge-gate')).toContainText('digital prizes')
    await expectAccessible(page, 'sponsor challenge passed')
    // The Sponsor may now give digital prizes, and the granted credit is a lot of their platform fee account.
    await expect.poll(async () => String((await query(request, sponsor.token, 'qb.sponsor.reward-methods', {}))[0]
      ?.methods ?? '').split(','), { timeout: 30_000 }).toContain('DIGITAL_PRIZE')
    await expect.poll(async () => (await query(request, sponsor.token, 'qb.sponsor.fee-account-lots', {}))
      .some((row) => row.subAccount === 'FEE_CREDIT' && Number(row.remaining) === GRANT), { timeout: 30_000 })
      .toBe(true)

    // Another Sponsor who does not pass is told to wait for the next challenge.
    const other = await sponsorOf(request, operator.operatorId, 'Kei')
    const started = await run(request, other.token, 'QB_SPONSOR_ATTEMPT_START', { publicationId })
    const questions = started.questions as { no: number; options: { no: number; text: string }[] }[]
    await run(request, other.token, 'QB_SPONSOR_ATTEMPT_SUBMIT', { attemptId: started.attemptId, publicationId,
      answers: questions.map((q) => ({ questionNo: q.no,
        selected: q.options.filter((o) => o.text !== 'Paris').map((o) => o.no) })) })
    await signOut(page)
    await signIn(page, '/sponsor/', other.email)
    await page.goto(`/sponsor/profile/challenges/results/${started.attemptId}`)
    await expect(page.getByTestId('challenge-result')).toHaveAttribute('data-passed', 'false')
    await expect(page.getByTestId('challenge-next')).toBeVisible()
  })
