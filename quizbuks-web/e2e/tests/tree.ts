import type { APIRequestContext } from '@playwright/test'
import { contentAdmin } from './legal'
import { decideReview, publicationOf } from './publishing'
import { adminToken, expect, PASSWORD, run, tokenOf, unique } from './support'

/*
 * What the knowledge map tests prepare through the API (tree-visibility.spec, tree-graph.spec): a live publication
 * bound to a node, and a Taker passing it (the answering in the page is play.spec's).
 */

/** The questions of a bound publication, in the order they are written (shuffled, each Taker sees their own order). */
export const CAPITALS = [
  { stem: 'Capital of France?', right: 'Paris', wrong: 'Lyon' },
  { stem: 'Capital of Japan?', right: 'Tokyo', wrong: 'Osaka' },
  { stem: 'Capital of Italy?', right: 'Rome', wrong: 'Milan' },
] as const

/** What a bound publication may be given besides its node (the defaults are what most specs want: one question, no prize). */
export interface BoundOptions {
  /** How many of {@link CAPITALS} the quiz asks (1 by default); all must be right to pass. */
  questions?: 1 | 2 | 3
  /** The reward of a pass (0 by default: a bound publication pays nothing unless it gives a prize). */
  reward?: number
  /** The way the reward is paid, with what that way needs (the Sponsor is allowed it: `allowRewardMethods`). */
  prize?:
    | { method: 'DIGITAL_PRIZE'; description: string; link: string }
    | { method: 'OFFLINE_REDEEM'; description: string }
  /** How long it runs from now (seven days by default). */
  endInMs?: number
  /** F-M7: the audience rules it uses (none by default), saved before it is submitted. */
  audience?: { key: string; options: string[] }[]
  /** The cap on the winners (5 with a prize, 1,000 without, by default). */
  maxWinners?: number
  /** F-M8: a shared video of another Sponsor's the publication uses, and the exposures bought with it. */
  sharedVideoId?: string
  exposureRequested?: number
}

/** A publication made live by {@link boundPublicationOf}: the quiz and its version are the Sponsor's to read. */
export interface BoundPublication {
  publicationId: string
  quizId: string
  versionId: string
}

/**
 * A live publication bound to the node (the quiz as asked by `options`, "Paris" right when it has one question), made
 * as the wizard makes it: saved, submitted, approved by a member of the Sponsor's operator, and waited for until live.
 * The quiz is saved without a word on the order of its questions: a new quiz shuffles (A2-14).
 */
export async function boundPublicationOf(request: APIRequestContext, token: string, treeId: string, nodeId: string,
  name: string, options: BoundOptions = {}): Promise<BoundPublication> {
  const count = options.questions ?? 1
  const reward = options.reward ?? 0
  const quiz = await run(request, token, 'QB_QUIZ_SAVE', { title: name, intro: null, timeLimitSec: 300 })
  for (const { stem, right, wrong } of CAPITALS.slice(0, count)) {
    await run(request, token, 'QB_QUESTION_SAVE', { quizId: quiz.quizId, stem, points: 1,
      options: [{ text: right, correct: true }, { text: wrong }] })
  }
  const version = await run(request, token, 'QB_QUIZ_PUBLISH_VERSION', { quizId: quiz.quizId })
  const now = Date.now()
  const prize = options.prize
  const saved = await run(request, token, 'QB_PUBLICATION_SAVE', { versionId: version.versionId, name,
    regions: ['GLOBAL'], startAt: new Date(now - 60_000).toISOString(),
    endAt: new Date(now + (options.endInMs ?? 7 * 86_400_000)).toISOString(), passScore: count, budgetCap: 500,
    maxWinners: options.maxWinners ?? (prize ? 5 : 1000), treeId, treeNodeId: nodeId,
    ...(options.exposureRequested ? { exposureRequested: options.exposureRequested } : {}),
    ...(prize?.method === 'DIGITAL_PRIZE' ? { rewardMethod: 'DIGITAL_PRIZE', digitalDelivery: 'SHARED',
      digitalDescription: prize.description, digitalContent: prize.link } : {}),
    ...(prize?.method === 'OFFLINE_REDEEM' ? { rewardMethod: 'OFFLINE_REDEEM', redeemDescription: prize.description }
      : {}),
    tiers: count === 1 && reward === 0 ? [{ fromScore: 0, toScore: 1, reward: 0 }]
      : [{ fromScore: 0, toScore: count - 1, reward: 0 }, { fromScore: count, toScore: count, reward }] })
  expect(saved.problems).toEqual([])
  const publicationId = saved.publicationId as string
  if (options.audience) {
    await run(request, token, 'QB_PUBLICATION_AUDIENCE_SAVE', { publicationId, criteria: options.audience })
  }
  if (options.sharedVideoId) {
    await run(request, token, 'QB_PUBLICATION_SHARED_VIDEO_SET', { publicationId, sharedVideoId: options.sharedVideoId })
  }
  await run(request, token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  await decideReview(request, token, publicationId, 'APPROVE')
  await expect.poll(async () => (await publicationOf(request, token, publicationId)).status,
    { message: 'the publication is live', timeout: 30_000 }).toBe('LIVE')
  return { publicationId, quizId: String(quiz.quizId), versionId: String(version.versionId) }
}

/** A live publication of one question ("Paris" right), bound to the node, paying nothing (a bound one may not). */
export async function boundPublication(request: APIRequestContext, token: string, treeId: string, nodeId: string,
  name: string) {
  return (await boundPublicationOf(request, token, treeId, nodeId, name)).publicationId
}

/** The taker passes the publication through the API (the answering is play.spec's). */
export async function pass(request: APIRequestContext, token: string, publicationId: string) {
  const started = await run(request, token, 'QB_ATTEMPT_START', { publicationId })
  const questions = started.questions as { no: number; options: { no: number; text: string }[] }[]
  const right: string[] = CAPITALS.map((question) => question.right)
  const submitted = await run(request, token, 'QB_ATTEMPT_SUBMIT', { attemptId: started.attemptId, publicationId,
    answers: questions.map((q) => ({ questionNo: q.no,
      selected: q.options.filter((o) => right.includes(o.text)).map((o) => o.no) })) })
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
export async function platformMap(request: APIRequestContext, subjectKind: 'TAKER' | 'SPONSOR', certificate: string,
  grantAmount?: number) {
  const writer = await tokenOf(request, await contentAdmin(request, 'Maps'), PASSWORD, 'admin')
  const tree = await run(request, writer, 'QB_TREE_CREATE', { ownerKind: 'PLATFORM', subjectKind,
    name: { en: unique('Certificates ') } })
  const saved = await run(request, writer, 'QB_TREE_DRAFT_SAVE', { treeId: tree.treeId, baseRevision: tree.revision,
    name: { en: 'Certificates' }, nodes: [{ key: 'node', name: { en: 'Fair dealing' }, condition: passed,
      certificateNames: { en: certificate }, ...(grantAmount ? { grantAmount } : {}) }] })
  expect(saved.problems).toEqual([])
  const submitted = await run(request, writer, 'QB_TREE_SUBMIT', { treeId: tree.treeId, baseRevision: saved.revision })
  await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId,
    decision: 'APPROVE' })
  // Newest first: the list is by id, oldest first, and a database the tests have used for long has more than a page.
  await expect.poll(async () => {
    const response = await request.post('/api/queries/qb.admin.trees', { headers: { Authorization: `Bearer ${writer}` },
      data: { params: {}, sorts: [{ field: 'treeId', asc: false }], limit: 100 } })
    expect(response.status(), await response.text()).toBe(200)
    return (((await response.json()).items ?? []) as Record<string, unknown>[])
      .find((row) => row.treeId === tree.treeId)?.currentVersionId ?? null
  }, { message: 'the map is in effect', timeout: 30_000 }).not.toBeNull()
  return { treeId: String(tree.treeId), node: (saved.nodeIds as Record<string, string>).node }
}
