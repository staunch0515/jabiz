import type { APIRequestContext, Page } from '@playwright/test'
import { adminToken, expect, PASSWORD, rows, run, tokenOf, unique, verifiedUser } from './support'

/*
 * What the answering tests prepare through the API (play.spec, survey.spec): a funded sponsor, a taker in Japan and
 * the wait until a publication can be started.
 */

const SPONSOR_DATASET = 'urn:jabiz:dataset:sponsor:QbSponsor'
const SPONSOR_WALLETS = 'urn:jabiz:dataset:sponsor:QbWallet'
const SPONSOR_TOPUPS = 'urn:jabiz:dataset:sponsor:QbTopUp'

/** An approved sponsor with a balance: a top-up paid at the Stripe stub's checkout. */
export async function fundedSponsor(request: APIRequestContext) {
  const email = await verifiedUser(request, 'sponsor', 'Sora')
  const token = await tokenOf(request, email, PASSWORD, 'sponsor')
  const applied = await run(request, token, 'QB_SPONSOR_APPLY', { displayName: 'Sora',
    company: unique('Quiz Co '), acceptTerms: true })
  if (applied.approval === 'PENDING') {
    await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: applied.approvalRequestId,
      decision: 'APPROVE' })
  }
  await expect.poll(async () => (await rows(request, token, SPONSOR_DATASET, []))[0]?.attributes.reviewStatus,
    { timeout: 30_000 }).toBe('APPROVED')

  const topUp = await run(request, token, 'QB_TOPUP_START', { amount: 1000 })
  let checkoutUrl: string | undefined
  await expect.poll(async () => {
    const found = (await rows(request, token, SPONSOR_TOPUPS, [])).find((r) => r.id === topUp.topUpId)
    checkoutUrl = found?.attributes.checkoutUrl as string | undefined
    return !!checkoutUrl
  }, { message: 'the checkout opens', timeout: 30_000 }).toBe(true)
  // The stub's "Pay" button: the checkout completes and its signed event is posted to the server.
  const paid = await request.post(`${checkoutUrl}/paid`, { maxRedirects: 0 })
  expect(paid.status()).toBeLessThan(400)
  let balance = 0
  await expect.poll(async () => {
    balance = Number((await rows(request, token, SPONSOR_WALLETS, []))[0]?.attributes.balance ?? 0)
    return balance
  }, { message: 'the top-up is credited', timeout: 30_000 }).toBeGreaterThan(0)
  return { email, token, balance }
}

/** A verified taker in Japan; returns the address and an access token at the app's entry. */
export async function taker(request: APIRequestContext, name: string) {
  const email = await verifiedUser(request, 'app', name)
  const token = await tokenOf(request, email, PASSWORD, 'app')
  await run(request, token, 'QB_PROFILE_SAVE', { displayName: name, countryCode: 'JP', preferredCurrency: 'JPY' })
  return { email, token }
}

/** Waits until the publication is live for takers (its approval is handled by an event). */
export async function untilLive(request: APIRequestContext, token: string, publicationId: string) {
  await expect.poll(async () => {
    const response = await request.post('/api/queries/qb.app.publication', {
      headers: { Authorization: `Bearer ${token}` },
      data: { params: { publicationId, at: new Date().toISOString(), country: 'JP' } },
    })
    if (!response.ok()) return false
    return ((await response.json()).items ?? [])[0]?.canStart === true
  }, { message: 'the publication goes live', timeout: 30_000 }).toBe(true)
}


/** Searches the taker's home from the header's magnifier (Q63) and waits for the results. */
export async function searchHome(page: Page, text: string) {
  await page.getByTestId('search-open').click()
  await page.getByTestId('feed-search').fill(text)
  await page.getByTestId('feed-search-submit').click()
  await expect(page.getByTestId('feed-searched')).toBeVisible()
}
