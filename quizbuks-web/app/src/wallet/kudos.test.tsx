import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { kudosMethodOf, periodRange, totalOf, type KudosRow } from './kudos'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ROWS = [
  { method: 'PLATFORM', earned: '1350', settled: '1000', lapsed: 0, balance: '350', rewards: 3, pendingCount: 1,
    pendingAmount: '300' },
  { method: 'SPONSOR_CASH', earned: '500', settled: '200', lapsed: 0, balance: '300', rewards: 2, pendingCount: 0,
    pendingAmount: 0 },
  { method: 'OFFLINE_REDEEM', earned: '100', settled: '0', lapsed: '100', balance: '0', rewards: 1, pendingCount: 0,
    pendingAmount: 0 },
  { method: 'DIGITAL_PRIZE', earned: '0', settled: '0', lapsed: 0, balance: '0', rewards: 0, pendingCount: 0,
    pendingAmount: 0 },
]

const CASH_ROW = { attemptId: 'a7', publicationId: 'p7', name: 'Cash quiz', quizTitle: 'Cash', cover: null,
  kind: 'QUIZ', status: 'SUBMITTED', category: 'PASSED', passed: true, score: 5, fullScore: 5, reward: '200',
  rewardStatus: 'CASH', rewardMethod: 'SPONSOR_CASH', cashRewardId: 'c7', cashStatus: 'CONFIRMED', cashCode: 'C-ABCDEFGH',
  redeemStatus: null, redeemExpiresAt: null, digitalPrizeId: null, digitalStatus: null, digitalSendBy: null,
  startedAt: '2026-10-20T01:00:00Z', submittedAt: '2026-10-20T01:05:00Z', deadline: '2026-10-20T02:00:00Z' }

/** The server as the wallet sees it, for a taker with rewards of every kind. Records the queries' bodies. */
function server() {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    switch (path) {
      case '/api/auth/entries/app':
        return json(200, { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
          sessionPersistence: 'device' })
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 'u1', displayName: 'Mei', email: 'mei@example.com', roles: ['QB_TAKER'],
          permissions: ['qb.me'], emailVerified: true, idleTimeoutSeconds: 0 })
      case '/api/datasets/urn:jabiz:dataset:me:QbProfile/query':
        return json(200, { items: [{ id: 'p1', version: 1, attributes: { displayName: 'Mei', countryCode: 'JP',
          city: null, preferredCurrency: 'JPY', revision: 1 } }], offset: 0, limit: 1 })
      case '/api/queries/qb.app.kudos':
        return json(200, { items: ROWS, offset: 0, limit: 10 })
      case '/api/queries/qb.app.history':
        return json(200, { items: [CASH_ROW], offset: 0, limit: 20 })
      case '/api/queries/qb.app.wallet':
        return json(200, { items: [{ available: '350', totalEarned: '1350', pendingCount: 1, pendingAmount: '300',
          inTransfer: '0', held: '0', heldOpen: '0', heldUntil: null }], offset: 0, limit: 1 })
      case '/api/queries/qb.app.ledger':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      default:
        return json(404, {})
    }
  })
  return { bodies: (id: string) => posted.filter((p) => p.path === `/api/queries/${id}`).map((p) => p.body) }
}

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => vi.restoreAllMocks())

describe('the Kudos by method helpers (Q58)', () => {
  it('bound this year and this month in the device\'s time zone, and all of time by nothing', () => {
    const now = new Date(2026, 9, 30, 15, 0)
    expect(periodRange('all', now)).toEqual({ from: null, to: null })
    expect(periodRange('year', now)).toEqual({ from: new Date(2026, 0, 1).toISOString(),
      to: new Date(2027, 0, 1).toISOString() })
    expect(periodRange('month', new Date(2026, 11, 31))).toEqual({ from: new Date(2026, 11, 1).toISOString(),
      to: new Date(2027, 0, 1).toISOString() })
  })

  it('add the four up and know a method\'s page', () => {
    const row = (earned: number): KudosRow => ({ method: 'PLATFORM', earned, settled: 1, lapsed: 2, balance: 3,
      rewards: 1, pendingCount: 0, pendingAmount: 0 })
    expect(totalOf([row(10), row(20)])).toEqual({ earned: 30, settled: 2, lapsed: 4, balance: 6, rewards: 2 })
    expect(kudosMethodOf('/wallet/kudos/SPONSOR_CASH')).toBe('SPONSOR_CASH')
    expect(kudosMethodOf('/wallet/kudos/NOPE')).toBeNull()
    expect(kudosMethodOf('/wallet')).toBeNull()
  })
})

describe('the wallet\'s Kudos by method (Q58)', () => {
  it('shows the total and a card per method, by period, each opening its rewards', async () => {
    const { bodies } = server()
    window.history.replaceState(null, '', '/wallet/rewards')
    render(<App />)
    const kudos = await screen.findByTestId('kudos')
    // The total is the sum of the four.
    expect(await within(kudos).findByTestId('kudos-total-earned')).toHaveTextContent('1,950')
    expect(within(kudos).getByTestId('kudos-total-settled')).toHaveTextContent('1,200')
    expect(within(kudos).getByTestId('kudos-total-balance')).toHaveTextContent('650')
    expect(within(kudos).getByTestId('kudos-total-lapsed')).toHaveTextContent('100')
    expect(within(kudos).getByTestId('kudos-SPONSOR_CASH')).toHaveTextContent('Cash from the sponsor')
    expect(within(kudos).getByTestId('kudos-SPONSOR_CASH-settled')).toHaveTextContent('200')
    expect(within(kudos).getByTestId('kudos-SPONSOR_CASH')).toHaveTextContent('Received')
    // All of time first; this month asks for its bounds.
    expect(bodies('qb.app.kudos').at(-1)).toMatchObject({ params: { from: null, to: null } })
    await userEvent.click(within(kudos).getByRole('radio', { name: 'This month' }))
    await waitFor(() => expect(bodies('qb.app.kudos').at(-1)).toMatchObject({
      params: { from: expect.any(String), to: expect.any(String) } }))
    await expectAccessible()

    await userEvent.click(within(kudos).getByTestId('kudos-SPONSOR_CASH'))
    expect(window.location.pathname).toBe('/wallet/kudos/SPONSOR_CASH')
    const page = await screen.findByTestId('kudos-method')
    expect(within(page).getByRole('heading', { level: 1 })).toHaveTextContent('Cash from the sponsor')
    expect(await within(page).findByTestId('kudos-reward')).toHaveTextContent('Cash quiz')
    expect(within(page).getByTestId('history-cash')).toHaveAttribute('data-status', 'CONFIRMED')
    // Only this method's rewards, and the period chosen stays.
    expect(bodies('qb.app.history').at(-1)).toMatchObject({
      filters: [{ field: 'rewardStatus', op: 'in', values: ['CASH'] }] })
    expect(within(page).getByRole('radio', { name: 'This month' })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByTestId('nav-wallet')).toHaveAttribute('aria-current', 'page')
    await expectAccessible()
    await userEvent.click(within(page).getByTestId('kudos-back'))
    expect(window.location.pathname).toBe('/wallet/rewards')
  })
})
