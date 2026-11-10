import type { APIRequestContext } from '@playwright/test'
import { query } from './content'
import { contentAdmin } from './legal'
import { decideReview, publicationOf } from './publishing'
import { adminToken, expect, PASSWORD, run, tokenOf, unique } from './support'

/*
 * What the knowledge map tests prepare through the API (tree-visibility.spec, tree-graph.spec): a live publication
 * bound to a node, and a Taker passing it (the answering in the page is play.spec's).
 */

/** A live publication of one question ("Paris" right), bound to the node, paying nothing (a bound one may not). */
export async function boundPublication(request: APIRequestContext, token: string, treeId: string, nodeId: string,
  name: string) {
  const quiz = await run(request, token, 'QB_QUIZ_SAVE', { title: name, intro: null, timeLimitSec: 300 })
  await run(request, token, 'QB_QUESTION_SAVE', { quizId: quiz.quizId, stem: 'Capital of France?', points: 1,
    options: [{ text: 'Paris', correct: true }, { text: 'Lyon' }] })
  const version = await run(request, token, 'QB_QUIZ_PUBLISH_VERSION', { quizId: quiz.quizId })
  const now = Date.now()
  const saved = await run(request, token, 'QB_PUBLICATION_SAVE', { versionId: version.versionId, name,
    regions: ['GLOBAL'], startAt: new Date(now - 60_000).toISOString(),
    endAt: new Date(now + 7 * 86_400_000).toISOString(), passScore: 1, budgetCap: 500, maxWinners: 1000, treeId, treeNodeId: nodeId,
    tiers: [{ fromScore: 0, toScore: 1, reward: 0 }] })
  expect(saved.problems).toEqual([])
  const publicationId = saved.publicationId as string
  await run(request, token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  await decideReview(request, token, publicationId, 'APPROVE')
  await expect.poll(async () => (await publicationOf(request, token, publicationId)).status,
    { message: 'the publication is live', timeout: 30_000 }).toBe('LIVE')
  return publicationId
}

/** The taker passes the publication through the API (the answering is play.spec's). */
export async function pass(request: APIRequestContext, token: string, publicationId: string) {
  const started = await run(request, token, 'QB_ATTEMPT_START', { publicationId })
  const questions = started.questions as { no: number; options: { no: number; text: string }[] }[]
  const submitted = await run(request, token, 'QB_ATTEMPT_SUBMIT', { attemptId: started.attemptId, publicationId,
    answers: questions.map((q) => ({ questionNo: q.no,
      selected: q.options.filter((o) => o.text === 'Paris').map((o) => o.no) })) })
  expect(submitted.passed).toBe(true)
}

/**
 * The operator's row of one map (`qb.operator.trees`, which filters by no id: an operator looks after many maps, so
 * the newest first, page by page, until the map is found; ids are time-ordered, so a new map is on the first page).
 */
export async function operatorTree(request: APIRequestContext, token: string, treeId: string) {
  for (let offset = 0; offset < 1000; offset += 100) {
    const response = await request.post('/api/queries/qb.operator.trees', {
      headers: { Authorization: `Bearer ${token}` },
      data: { params: {}, sorts: [{ field: 'treeId', asc: false }], offset, limit: 100 },
    })
    expect(response.status(), await response.text()).toBe(200)
    const items = ((await response.json()).items ?? []) as Record<string, unknown>[]
    const found = items.find((row) => row.treeId === treeId)
    if (found || items.length < 100) return found ?? null
  }
  return null
}

const passed = { op: 'ALL', items: [{ passed: true }] }

/** A platform map in effect with one node that names a certificate; the other administrator reviews it. */
export async function platformMap(request: APIRequestContext, subjectKind: 'TAKER' | 'SPONSOR', certificate: string) {
  const writer = await tokenOf(request, await contentAdmin(request, 'Maps'), PASSWORD, 'admin')
  const tree = await run(request, writer, 'QB_TREE_CREATE', { ownerKind: 'PLATFORM', subjectKind,
    name: { en: unique('Certificates ') } })
  const saved = await run(request, writer, 'QB_TREE_DRAFT_SAVE', { treeId: tree.treeId, baseRevision: tree.revision,
    name: { en: 'Certificates' }, nodes: [{ key: 'node', name: { en: 'Fair dealing' }, condition: passed,
      certificateNames: { en: certificate } }] })
  expect(saved.problems).toEqual([])
  const submitted = await run(request, writer, 'QB_TREE_SUBMIT', { treeId: tree.treeId, baseRevision: saved.revision })
  await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId,
    decision: 'APPROVE' })
  await expect.poll(async () => (await query(request, writer, 'qb.admin.trees', {}))
    .find((row) => row.treeId === tree.treeId)?.currentVersionId ?? null,
  { message: 'the map is in effect', timeout: 30_000 }).not.toBeNull()
  return { treeId: String(tree.treeId), node: (saved.nodeIds as Record<string, string>).node }
}
