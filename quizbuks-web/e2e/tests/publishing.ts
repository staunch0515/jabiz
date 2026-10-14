import type { APIRequestContext, Page } from '@playwright/test'
import { query, sponsor } from './content'
import { adminToken, expect, rows, run } from './support'

/*
 * Publishing for the end-to-end tests (docs/quizbuks/plans/Q4-publishing.md): an approved sponsor, a publication's
 * review decided by the administrator as the review queue would, and the times the wizard's fields take.
 */

const SPONSOR_DATASET = 'urn:jabiz:dataset:sponsor:QbSponsor'
const PUBLICATIONS = 'urn:jabiz:dataset:sponsor:QbPublication'

/** A sponsor whose onboarding the administrator approved: they may submit publications. */
export async function approvedSponsor(request: APIRequestContext, name = 'Pia') {
  const made = await sponsor(request, name)
  const applied = await run(request, made.token, 'QB_SPONSOR_APPLY', { displayName: name,
    company: `${name} Co`, acceptTerms: true })
  if (applied.approval === 'PENDING') {
    await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: applied.approvalRequestId,
      decision: 'APPROVE' })
  }
  await expect.poll(async () => (await rows(request, made.token, SPONSOR_DATASET, []))[0]?.attributes.reviewStatus,
    { message: 'the sponsor is approved', timeout: 30_000 }).toBe('APPROVED')
  return made
}

/** A publication of the sponsor's as their dataset has it now. */
export async function publicationOf(request: APIRequestContext, token: string, publicationId: string) {
  const response = await request.get(`/api/datasets/${encodeURIComponent(PUBLICATIONS)}/entities/${publicationId}`,
    { headers: { Authorization: `Bearer ${token}` } })
  expect(response.status(), await response.text()).toBe(200)
  return (await response.json()).attributes as Record<string, unknown>
}

/**
 * The administrator decides the publication's review, as the review queue (the administration's tasks) does: the
 * request the publication waits for, approved or sent back with a reason.
 */
export async function decideReview(request: APIRequestContext, token: string, publicationId: string,
  decision: 'APPROVE' | 'REJECT', reason?: string) {
  const publication = await publicationOf(request, token, publicationId)
  expect(publication.status).toBe('PENDING_REVIEW')
  await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: publication.approvalRequestId,
    decision, reason: reason ?? null })
}

/**
 * A live publication of the quiz's newest version made through the API as the wizard makes it (for tests about what
 * comes after): open to everyone from a minute ago for a week, two tiers, submitted and approved.
 */
export async function livePublication(request: APIRequestContext, token: string, quizId: string, name: string) {
  const versionId = (await query(request, token, 'qb.sponsor.quiz-versions', { quizId }))[0].versionId
  const now = Date.now()
  const saved = await run(request, token, 'QB_PUBLICATION_SAVE', { versionId, name, regions: ['GLOBAL'],
    startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
    passScore: 1, budgetCap: 500, maxWinners: 5,
    tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 2, reward: 20 }] })
  const publicationId = saved.publicationId as string
  await run(request, token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  await decideReview(request, token, publicationId, 'APPROVE')
  await expect.poll(async () => (await publicationOf(request, token, publicationId)).status,
    { message: 'the publication is live', timeout: 30_000 }).toBe('LIVE')
  return publicationId
}

/** The publication's key in the page's address (`/sponsor/publications/<id>`). */
export function publicationIdIn(page: Page): string {
  const match = /\/publications\/([^/?]+)/.exec(new URL(page.url()).pathname)
  expect(match, page.url()).not.toBeNull()
  return decodeURIComponent(match![1])
}

/** A point in time as the date and time fields take it typed: `YYYY-MM-DD HH:mm:ss` in the browser's time zone. */
export function typedTime(at: Date, timeZone = 'Asia/Tokyo'): string {
  return new Intl.DateTimeFormat('sv-SE', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(at)
}

/** Types a time into a date and time field and leaves it, which reads what was typed. */
export async function typeTime(page: Page, testId: string, at: Date) {
  const field = page.getByTestId(testId)
  await field.fill(typedTime(at))
  await field.press('Tab')
}
