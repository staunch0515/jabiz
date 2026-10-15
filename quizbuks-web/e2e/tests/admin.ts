import type { APIRequestContext, Page } from '@playwright/test'
import { query } from './content'
import { ADMIN, expect, run } from './support'

/*
 * The administration's QuizBuks pages for the end-to-end tests (backend/quizbuks/admin-extension,
 * docs/quizbuks/plans/Q9a-admin-extension.md): signing in at /admin/ and a publication submitted for review.
 */

/** Signs the first administrator in at the administration (no second factor in the end-to-end job). */
export async function signInAdmin(page: Page) {
  await page.goto('/admin/login')
  await page.getByPlaceholder('User name', { exact: true }).fill(ADMIN.userName)
  await page.getByPlaceholder('Password', { exact: true }).fill(ADMIN.password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/data/)
}

/**
 * A publication of the quiz's newest version, submitted for review and not yet decided: open to everyone from
 * tomorrow for a week, two tiers. Returns its key.
 */
export async function submittedPublication(request: APIRequestContext, token: string, quizId: string, name: string) {
  const versionId = (await query(request, token, 'qb.sponsor.quiz-versions', { quizId }))[0].versionId
  const now = Date.now()
  const saved = await run(request, token, 'QB_PUBLICATION_SAVE', { versionId, name, regions: ['GLOBAL'],
    startAt: new Date(now + 86_400_000).toISOString(), endAt: new Date(now + 8 * 86_400_000).toISOString(),
    passScore: 1, budgetCap: 500, maxWinners: 5,
    tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 2, reward: 20 }] })
  const publicationId = saved.publicationId as string
  const submitted = await run(request, token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  expect(submitted.status).toBe('PENDING_REVIEW')
  return publicationId
}
