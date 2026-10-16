import type { APIRequestContext } from '@playwright/test'
import { adminToken, expect, rows, run, setControlledParam } from './support'
import { earnReward, takerInJapan } from './wallet'

/*
 * Data for the finance pages' tests (docs/quizbuks/plans/Q9c-admin-finance.md): a taker whose large transfer waits for
 * review. The server runs the daily transfers every minute here (QB_TRANSFER_RUN_CRON in the e2e job), transfers are
 * on, and a transfer above 1,000 waits for review; the payout threshold stays 1,000. Rewards of the other tests stay
 * below it, so no other taker is ever transferred to.
 */

const MY_ACCOUNT = 'urn:jabiz:dataset:taker:QbPayoutAccount'
const MY_ONBOARDING = 'urn:jabiz:dataset:taker:QbPayoutOnboarding'

/** The amount a large transfer is above: anything more waits for review. */
export const REVIEW_ABOVE = 1000

let configured = false

/** Transfers on, large ones (above {@link REVIEW_ABOVE}) waiting for review. Once per run. */
export async function transfersWithReview(request: APIRequestContext) {
  if (configured) return
  await setControlledParam(request, 'qb.transfer.review-above', String(REVIEW_ABOVE))
  await setControlledParam(request, 'qb.transfer.enabled', 'true')
  configured = true
}

/** Opens the taker's payout account at the Stripe stub and enables it, through the API. */
export async function enabledPayoutAccount(request: APIRequestContext, token: string) {
  await run(request, token, 'QB_PAYOUT_ONBOARD', {})
  let url: string | undefined
  await expect.poll(async () => {
    url = (await rows(request, token, MY_ONBOARDING, []))[0]?.attributes.url as string | undefined
    return !!url
  }, { message: 'the onboarding link', timeout: 30_000 }).toBe(true)
  const enabled = await request.post(`${url}/enable`, { maxRedirects: 0 })
  expect(enabled.status()).toBeLessThan(400)
  await expect.poll(async () => (await rows(request, token, MY_ACCOUNT, []))[0]?.attributes.status,
    { message: 'the account is enabled', timeout: 30_000 }).toBe('ENABLED')
  return (await rows(request, token, MY_ACCOUNT, []))[0].attributes.userId as string
}

/** A transfer row as `qb.admin.transfers` gives it. */
export interface AdminTransfer {
  transferId: string
  status: string
  amount: number
  approvalRequestId: string | null
}

/** The user's transfers, newest first, as an administrator reads them. */
export async function transfersOf(request: APIRequestContext, userId: string): Promise<AdminTransfer[]> {
  const response = await request.post('/api/queries/qb.admin.transfers', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data: { filters: [{ field: 'userId', op: 'eq', value: userId }], limit: 10 },
  })
  expect(response.status(), await response.text()).toBe(200)
  return ((await response.json()).items ?? []).map((row: Record<string, unknown>) => ({
    transferId: String(row.transferId), status: String(row.status), amount: Number(row.amount),
    approvalRequestId: (row.approvalRequestId as string | null) ?? null,
  }))
}

/**
 * A taker in Japan with an enabled payout account who earned `reward` (above {@link REVIEW_ABOVE}): the next minute's
 * run creates their transfer, which waits for review. Returns the taker and the transfer.
 */
export async function transferInReview(request: APIRequestContext, reward = 2000) {
  await transfersWithReview(request)
  const taker = await takerInJapan(request, 'Ren')
  const userId = await enabledPayoutAccount(request, taker.token)
  const publication = await earnReward(request, taker.token, reward)
  let transfer: AdminTransfer | undefined
  await expect.poll(async () => {
    transfer = (await transfersOf(request, userId))[0]
    return transfer?.status
  }, { message: 'the daily run (every minute here) makes the transfer', timeout: 150_000, intervals: [5_000] })
    .toBe('REVIEW')
  return { ...taker, userId, publication, transfer: transfer! }
}
