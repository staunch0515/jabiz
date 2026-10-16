import type { APIRequestContext } from '@playwright/test'
import { adminToken, expect, run, unique } from './support'

/*
 * Data for the transactions tests (docs/quizbuks/plans/Q9b-sponsor-transactions.md), made through the processes the
 * pages call: a live one-question publication of a funded sponsor, and takers who start and submit it.
 */

/**
 * A live publication of the sponsor's: one question (Paris), one point pays `reward`, open to every region for a
 * week. Returns its key and name.
 */
export async function rewardingPublication(request: APIRequestContext, sponsorToken: string, reward: number,
  takerToken: string) {
  const quiz = await run(request, sponsorToken, 'QB_QUIZ_SAVE', { title: 'Capitals', intro: 'One question.',
    timeLimitSec: 300 })
  const quizId = quiz.quizId as string
  await run(request, sponsorToken, 'QB_MATERIAL_SAVE', { quizId, kind: 'ARTICLE', title: 'Read first',
    body: 'Paris is the capital of France.' })
  await run(request, sponsorToken, 'QB_QUESTION_SAVE', { quizId, stem: 'Capital of France?', points: 1,
    options: [{ text: 'Paris', correct: true }, { text: 'Lyon' }] })
  const version = await run(request, sponsorToken, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  const name = unique('Ledger quiz ')
  const now = Date.now()
  const saved = await run(request, sponsorToken, 'QB_PUBLICATION_SAVE', {
    versionId: version.versionId, name, regions: ['GLOBAL'],
    startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
    timeLimitSec: 300, passScore: 1,
    tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 1, reward }],
  })
  const publicationId = saved.publicationId as string
  const submitted = await run(request, sponsorToken, 'QB_PUBLICATION_SUBMIT', { publicationId })
  if (submitted.approval === 'PENDING') {
    await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId,
      decision: 'APPROVE' })
  }
  await expect.poll(async () => {
    const response = await request.post('/api/queries/qb.app.publication', {
      headers: { Authorization: `Bearer ${takerToken}` },
      data: { params: { publicationId, at: new Date().toISOString(), country: 'JP' } },
    })
    if (!response.ok()) return false
    return ((await response.json()).items ?? [])[0]?.canStart === true
  }, { message: 'the publication goes live', timeout: 30_000 }).toBe(true)
  return { publicationId, name }
}

/** Starts the publication as the taker; returns the attempt. */
export async function startAttempt(request: APIRequestContext, takerToken: string, publicationId: string) {
  return await run(request, takerToken, 'QB_ATTEMPT_START', { publicationId }) as {
    attemptId: string
    publicationId: string
    questions: { no: number; options: { no: number; text: string }[] }[]
  }
}

/** Submits the attempt answered right; returns the reward's state (PAID, or PENDING when the sponsor is short). */
export async function submitRight(request: APIRequestContext, takerToken: string,
  started: Awaited<ReturnType<typeof startAttempt>>): Promise<string> {
  const answers = started.questions.map((q) => ({ questionNo: q.no,
    selected: q.options.filter((o) => o.text === 'Paris').map((o) => o.no) }))
  // As the App submits: with the attempt's publication (the server's queue key, decision D45).
  const result = await run(request, takerToken, 'QB_ATTEMPT_SUBMIT', { attemptId: started.attemptId,
    publicationId: started.publicationId, answers })
  return result.rewardStatus as string
}
