import type { APIRequestContext } from '@playwright/test'
import { adminToken, expect, PASSWORD, rows, run, tokenOf, unique, verifiedUser } from './support'

/*
 * Data for the wallet tests (docs/quizbuks/plans/QP-pages.md, QP4), made through the processes the pages call: an
 * approved sponsor, a sponsor funded through the Stripe stub's checkout, a live publication and a taker in Japan (the
 * deployment's payout country) who earned a reward.
 */

const SPONSOR_DATASET = 'urn:jabiz:dataset:sponsor:QbSponsor'
const SPONSOR_WALLETS = 'urn:jabiz:dataset:sponsor:QbWallet'
const SPONSOR_TOPUPS = 'urn:jabiz:dataset:sponsor:QbTopUp'

/** A sponsor whose onboarding an administrator approved; returns the address and a token at the console's entry. */
export async function approvedSponsor(request: APIRequestContext, name = 'Sora', company = unique('Wallet Co ')) {
  const email = await verifiedUser(request, 'sponsor', name)
  const token = await tokenOf(request, email, PASSWORD, 'sponsor')
  const applied = await run(request, token, 'QB_SPONSOR_APPLY', { displayName: name, company, acceptTerms: true })
  if (applied.approval === 'PENDING') {
    await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: applied.approvalRequestId,
      decision: 'APPROVE' })
  }
  await expect.poll(async () => (await rows(request, token, SPONSOR_DATASET, []))[0]?.attributes.reviewStatus,
    { timeout: 30_000 }).toBe('APPROVED')
  return { email, token }
}

/** The sponsor's balance as the wallet records it. */
export async function sponsorBalance(request: APIRequestContext, token: string): Promise<number> {
  return Number((await rows(request, token, SPONSOR_WALLETS, []))[0]?.attributes.balance ?? 0)
}

/** An approved sponsor with `amount` Kudos: a top-up paid at the Stripe stub's checkout through the API. */
export async function fundedSponsor(request: APIRequestContext, amount: number) {
  const sponsor = await approvedSponsor(request)
  const topUp = await run(request, sponsor.token, 'QB_TOPUP_START', { amount })
  let checkoutUrl: string | undefined
  await expect.poll(async () => {
    const found = (await rows(request, sponsor.token, SPONSOR_TOPUPS, [])).find((r) => r.id === topUp.topUpId)
    checkoutUrl = found?.attributes.checkoutUrl as string | undefined
    return !!checkoutUrl
  }, { message: 'the checkout opens', timeout: 30_000 }).toBe(true)
  const paid = await request.post(`${checkoutUrl}/paid`, { maxRedirects: 0 })
  expect(paid.status()).toBeLessThan(400)
  await expect.poll(() => sponsorBalance(request, sponsor.token), { message: 'the top-up is credited',
    timeout: 30_000 }).toBe(amount)
  return sponsor
}

/** A verified taker in Japan; returns the address and a token at the app's entry. */
export async function takerInJapan(request: APIRequestContext, name = 'Aki') {
  const email = await verifiedUser(request, 'app', name)
  const token = await tokenOf(request, email, PASSWORD, 'app')
  await run(request, token, 'QB_PROFILE_SAVE', { displayName: name, countryCode: 'JP', preferredCurrency: 'JPY' })
  return { email, token }
}

/**
 * A reward of `reward` Kudos paid to the taker: a funded sponsor publishes a one-question quiz (one point passes) and
 * the taker answers it right through the processes. Returns the publication's name.
 */
export async function earnReward(request: APIRequestContext, takerToken: string, reward: number): Promise<string> {
  const sponsor = await fundedSponsor(request, Math.max(1000, reward))
  const quiz = await run(request, sponsor.token, 'QB_QUIZ_SAVE', { title: 'Capitals', intro: 'One question.',
    timeLimitSec: 300 })
  const quizId = quiz.quizId as string
  await run(request, sponsor.token, 'QB_MATERIAL_SAVE', { quizId, kind: 'ARTICLE', title: 'Read first',
    body: 'Paris is the capital of France.' })
  await run(request, sponsor.token, 'QB_QUESTION_SAVE', { quizId, stem: 'Capital of France?', points: 1,
    options: [{ text: 'Paris', correct: true }, { text: 'Lyon' }] })
  const version = await run(request, sponsor.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  const name = unique('Wallet quiz ')
  const now = Date.now()
  const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', {
    versionId: version.versionId, name, regions: ['GLOBAL'],
    startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
    timeLimitSec: 300, passScore: 1,
    tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 1, reward }],
  })
  const publicationId = saved.publicationId as string
  const submitted = await run(request, sponsor.token, 'QB_PUBLICATION_SUBMIT', { publicationId })
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
  const started = await run(request, takerToken, 'QB_ATTEMPT_START', { publicationId })
  const questions = started.questions as { no: number; options: { no: number; text: string }[] }[]
  const answers = questions.map((q) => ({ questionNo: q.no,
    selected: q.options.filter((o) => o.text === 'Paris').map((o) => o.no) }))
  const result = await run(request, takerToken, 'QB_ATTEMPT_SUBMIT', { attemptId: started.attemptId, publicationId,
    answers })
  expect(result.rewardStatus).toBe('PAID')
  return name
}
