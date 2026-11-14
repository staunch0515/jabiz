import type { APIRequestContext } from '@playwright/test'
import { inEffect, legalAdmin, publishVersion } from './legal'
import { adminToken, expect, run, setControlledParam } from './support'
import { fund } from './wallet'

/*
 * AI questions for the end-to-end tests (docs/quizbuks/plans/Q8-ai.md, Q8a): the provider is the local stub
 * (tools/quizbuks/claude-stub, which never calls the network: the job writes as many questions as asked), the AI
 * notice in effect and the switch on, as an operator does before offering AI. F-M9 (#702): the AI fee is on too (the
 * website draft is only offered with it), so each sponsor that asks the AI first buys AI credit (`aiCredit`).
 */

let ready = false

/** The AI notice in effect (published once on a new database) and `qb.ai.enabled` on (four eyes). */
export async function aiReady(request: APIRequestContext) {
  if (ready) return
  if (!(await inEffect(request, 'AI_NOTICE'))) {
    const { token } = await legalAdmin(request)
    await publishVersion(request, token, 'AI_NOTICE', true, `first ${Date.now()}`)
  }
  const settings = await request.post('/api/queries/qb.admin.ai-settings', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` }, data: { limit: 20 } })
  expect(settings.status(), await settings.text()).toBe(200)
  const enabled = ((await settings.json()).items as Record<string, unknown>[])
    .find((row) => row.paramKey === 'qb.ai.enabled')
  if (enabled?.value !== 'true') await setControlledParam(request, 'qb.ai.enabled', 'true')
  // The AI fee (four eyes); the specs that ask the AI run with it on, whatever order they run in.
  await setControlledParam(request, 'qb.ai.fee.enabled', 'true')
  ready = true
}

/**
 * F-M9: AI credit for a sponsor, enough for a few requests of any kind: cash topped up into the platform fee account
 * (the Stripe stub) and bought as AI credit, as the sponsor does under "Money -> AI credit". The amount is the most a
 * new sponsor may top up, not a figure a test reads.
 */
export async function aiCredit(request: APIRequestContext, token: string, amount = 100_000) {
  await fund(request, token, amount)
  await run(request, token, 'QB_AI_CREDIT_BUY', { amount, requestKey: crypto.randomUUID() })
}

/** A quiz with an introduction and an article, through the processes the console calls; returns its key. */
export async function quizForAi(request: APIRequestContext, token: string, title: string): Promise<string> {
  const quiz = await run(request, token, 'QB_QUIZ_SAVE', { title, intro: 'All about **Paris**.' })
  const quizId = quiz.quizId as string
  await run(request, token, 'QB_MATERIAL_SAVE', { quizId, kind: 'ARTICLE', title: 'Paris',
    description: 'The city', body: 'Paris is the capital of France. The Seine runs through it.' })
  return quizId
}
