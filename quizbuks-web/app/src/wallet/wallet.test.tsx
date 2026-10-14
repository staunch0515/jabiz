import { configureClient, session } from '@jabiz/client'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { freshLink, onboardingReturn, type PayoutAccount } from './api'
import { walletMessages } from './messages'
import { APP } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { goToStripe } from './stripePage'

vi.mock('./stripePage', async (original) => ({
  ...(await original<typeof import('./stripePage')>()),
  goToStripe: vi.fn(),
}))

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device' }

const LINK = 'https://connect.stripe.com/setup/e/acct_1/abc'
const LATER = '2099-01-01T00:00:00Z'

const account = (status: string, extra: Partial<PayoutAccount> = {}) => ({
  status, payoutsEnabled: status === 'ENABLED', requirementsDue: null, onboardingUrl: null, linkExpiresAt: null,
  ...extra,
})

interface Server {
  /** The payout account's rows, read in turn (the last one repeats); null for none. */
  accounts?: (Record<string, unknown> | null)[]
  onboard?: { status: number; body: unknown }
  ledger?: Record<string, unknown>[]
}

/** The server as the wallet pages see them, for a verified taker in Japan. Records what was posted. */
function server(options: Server = {}) {
  const posted: { path: string; body: Record<string, unknown>; key: string | null }[] = []
  const accounts = options.accounts ?? [null]
  let accountReads = 0
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body, key: request.headers.get('Idempotency-Key') })
    switch (path) {
      case '/api/auth/entries/app':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 'u1', displayName: 'Mei', email: 'mei@example.com', roles: ['QB_TAKER'],
          permissions: ['qb.me', 'qb.payout.onboard'], emailVerified: true, idleTimeoutSeconds: 0 })
      case '/api/datasets/urn:jabiz:dataset:me:QbProfile/query':
        return json(200, { items: [{ id: 'p1', version: 1, attributes: { displayName: 'Mei', countryCode: 'JP',
          city: null, preferredCurrency: 'JPY', revision: 1 } }], offset: 0, limit: 1 })
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:payout-account-status':
        return json(200, [{ code: 'PENDING', label: 'Being set up' }, { code: 'ENABLED', label: 'Enabled' },
          { code: 'RESTRICTED', label: 'Restricted' }, { code: 'DEAUTHORIZED', label: 'Disconnected' }])
      case '/api/queries/qb.app.wallet':
        return json(200, { items: [{ available: '1250', totalEarned: '1500', pendingCount: 1, pendingAmount: '300',
          inTransfer: '250' }], offset: 0, limit: 1 })
      case '/api/queries/qb.app.ledger':
        return json(200, { items: options.ledger ?? [
          { entryId: 'a1', type: 'REWARD', status: 'COMPLETED', amount: '1350', occurredAt: '2026-10-14T01:00:00Z',
            publicationId: 'p1', name: 'Capitals week', quizTitle: 'World capitals' },
          { entryId: 'a2', type: 'REWARD', status: 'PROCESSING', amount: '300', occurredAt: '2026-10-13T01:00:00Z',
            publicationId: 'p2', name: 'Rivers', quizTitle: 'Rivers' },
          { entryId: 't1', type: 'TRANSFER', status: 'FAILED', amount: '100', occurredAt: '2026-10-12T01:00:00Z',
            publicationId: null, name: null, quizTitle: null },
        ], offset: 0, limit: 20 })
      case '/api/queries/qb.app.payout-account': {
        const row = accounts[Math.min(accountReads++, accounts.length - 1)]
        return json(200, { items: row ? [row] : [], offset: 0, limit: 1 })
      }
      case '/api/processes/QB_PAYOUT_ONBOARD/latest':
        return json(options.onboard?.status ?? 200, options.onboard?.body
          ?? { output: { jobId: 'j1', accountStatus: null } })
      default:
        return json(404, {})
    }
  })
  const processCalls = () => posted.filter((p) => p.path.startsWith('/api/processes/'))
  const queryBodies = (id: string) => posted.filter((p) => p.path === `/api/queries/${id}`).map((p) => p.body)
  return { processCalls, queryBodies }
}

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
  vi.mocked(goToStripe).mockClear()
})
afterEach(() => vi.restoreAllMocks())

describe('the wallet helpers', () => {
  it('waits for the link its own request made', () => {
    const old = account('PENDING', { onboardingUrl: LINK, linkExpiresAt: '2026-10-14T01:05:00Z' }) as PayoutAccount
    const renewed = { ...old, linkExpiresAt: '2026-10-14T01:09:00Z' }
    expect(freshLink(null, old)).toBe(LINK)
    expect(freshLink(old, old)).toBeNull()
    expect(freshLink(old, renewed)).toBe(LINK)
    expect(freshLink(old, { ...old, onboardingUrl: null })).toBeNull()
  })

  it('knows how Stripe sent the taker back', () => {
    expect(onboardingReturn('?onboarding=done')).toBe('done')
    expect(onboardingReturn('?onboarding=refresh')).toBe('refresh')
    expect(onboardingReturn('?onboarding=x')).toBeNull()
  })

  it('has every text in every language, naming no currency but Kudos', () => {
    const english = keyPaths(walletMessages.en)
    for (const lng of LANGUAGES) {
      expect(keyPaths(walletMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(APP, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(APP, lng), lng).toEqual([])
      expect(JSON.stringify(walletMessages[lng]), lng).not.toMatch(/JPY|yen|円|日元|USD|\$/i)
    }
  })
})

describe('the wallet', () => {
  it('shows the Kudos, what waits and the transactions, and searches them', async () => {
    const { queryBodies } = server()
    window.history.replaceState(null, '', '/wallet')
    const user = userEvent.setup()
    render(<App />)
    expect(await screen.findByTestId('wallet-available')).toHaveTextContent('1,250 Kudos')
    expect(screen.getByTestId('wallet-earned')).toHaveTextContent('1,500 Kudos')
    expect(screen.getByTestId('wallet-pending')).toHaveTextContent('300 Kudos (rewards: 1)')
    expect(screen.getByTestId('wallet-in-transfer')).toHaveTextContent('250 Kudos')
    expect(await screen.findByTestId('wallet-payout')).toHaveAttribute('data-status', 'NONE')
    const rows = await screen.findAllByTestId('ledger-row')
    expect(rows).toHaveLength(3)
    expect(rows[0]).toHaveTextContent('Capitals week')
    expect(within(rows[0]).getByTestId('ledger-amount')).toHaveTextContent('+1,350 Kudos')
    expect(within(rows[1]).getByTestId('ledger-status')).toHaveTextContent('Processing')
    expect(rows[2]).toHaveTextContent('Payout to your bank account')
    expect(rows[2]).toHaveTextContent('back in your wallet')
    expect(document.body).not.toHaveTextContent('JPY')
    expect(screen.getByTestId('nav-wallet')).toHaveAttribute('aria-current', 'page')

    await user.click(screen.getByRole('radio', { name: 'Payouts' }))
    await waitFor(() => expect(queryBodies('qb.app.ledger').at(-1)).toMatchObject({
      filters: [{ field: 'type', op: 'eq', value: 'TRANSFER' }] }))
    await user.type(screen.getByTestId('ledger-search'), 'Capitals{Enter}')
    await waitFor(() => expect(queryBodies('qb.app.ledger').at(-1)).toMatchObject({ params: { q: 'Capitals' } }))
  })
})

describe('the payout account', () => {
  it('opens one, waits for Stripe\'s fresh link and goes there', async () => {
    const old = account('PENDING', { onboardingUrl: LINK, linkExpiresAt: '2026-10-14T01:05:00Z' })
    const { processCalls } = server({ accounts: [old, old, old, { ...old, linkExpiresAt: LATER }] })
    window.history.replaceState(null, '', '/wallet/payout')
    const user = userEvent.setup()
    render(<App />)
    expect(await screen.findByTestId('payout-status')).toHaveTextContent('Being set up')
    expect(screen.getByTestId('nav-wallet')).toHaveAttribute('aria-current', 'page')
    await user.click(screen.getByTestId('payout-onboard'))
    expect(screen.getByTestId('payout-state')).toHaveAttribute('data-state', 'preparing')
    await waitFor(() => expect(goToStripe).toHaveBeenCalledWith(LINK), { timeout: 4000 })
    expect(goToStripe).toHaveBeenCalledTimes(1)
    expect(processCalls()).toHaveLength(1)
    expect(processCalls()[0].body).toEqual({})
    expect(processCalls()[0].key).toBeTruthy()
    expect(screen.getByTestId('payout-state')).toHaveAttribute('data-state', 'leaving')
  })

  it('never follows a link that is not Stripe\'s', async () => {
    server({ accounts: [null, null, account('PENDING', { onboardingUrl: 'http://evil.example.com/x',
      linkExpiresAt: LATER })] })
    window.history.replaceState(null, '', '/wallet/payout')
    const user = userEvent.setup()
    render(<App />)
    await user.click(await screen.findByTestId('payout-onboard'))
    await waitFor(() => expect(screen.getByTestId('payout-state')).toHaveAttribute('data-state', 'unsafe'),
      { timeout: 4000 })
    expect(goToStripe).not.toHaveBeenCalled()
  })

  it('shows the server\'s refusal', async () => {
    server({ onboard: { status: 422, body: { status: 422, code: 'QB_PAYOUT_COUNTRY_UNSUPPORTED',
      detail: 'Payout accounts can only be opened in JP.', violations: [] } } })
    window.history.replaceState(null, '', '/wallet/payout')
    const user = userEvent.setup()
    render(<App />)
    await user.click(await screen.findByTestId('payout-onboard'))
    const state = screen.getByTestId('payout-state')
    await waitFor(() => expect(state).toHaveAttribute('data-state', 'failed'))
    expect(state).toHaveTextContent('Payout accounts can only be opened in JP.')
    expect(goToStripe).not.toHaveBeenCalled()
  })

  it('back from Stripe, waits for Stripe\'s report: the return itself enables nothing', async () => {
    const { processCalls } = server({ accounts: [account('PENDING'), account('PENDING'), account('ENABLED')] })
    window.history.replaceState(null, '', '/wallet/payout?onboarding=done')
    render(<App />)
    const state = await screen.findByTestId('payout-state')
    expect(state).toHaveAttribute('data-state', 'checking')
    expect(screen.queryByTestId('payout-onboard')).not.toBeInTheDocument()
    await waitFor(() => expect(state).toHaveAttribute('data-state', 'enabled'), { timeout: 5000 })
    expect(screen.getByTestId('payout-status')).toHaveTextContent('Enabled')
    expect(processCalls()).toHaveLength(0)
  })

  it('after an expired link, offers a new one', async () => {
    server({ accounts: [account('PENDING')] })
    window.history.replaceState(null, '', '/wallet/payout?onboarding=refresh')
    render(<App />)
    expect(await screen.findByTestId('payout-state')).toHaveAttribute('data-state', 'refresh')
    expect(screen.getByTestId('payout-onboard')).toHaveTextContent('Continue with Stripe')
    expect(goToStripe).not.toHaveBeenCalled()
  })

  it('back through the browser\'s page cache, reads the account again and offers the step again', async () => {
    const { queryBodies } = server({ accounts: [account('PENDING'), account('ENABLED')] })
    window.history.replaceState(null, '', '/wallet/payout')
    render(<App />)
    expect(await screen.findByTestId('payout-state')).toHaveAttribute('data-state', 'unfinished')
    const before = queryBodies('qb.app.payout-account').length
    const shown = new Event('pageshow')
    Object.defineProperty(shown, 'persisted', { value: true })
    act(() => {
      window.dispatchEvent(shown)
    })
    await waitFor(() => expect(queryBodies('qb.app.payout-account').length).toBeGreaterThan(before))
    await waitFor(() => expect(screen.getByTestId('payout-state')).toHaveAttribute('data-state', 'enabled'))
  })

  it('a disconnected account cannot be set up again here', async () => {
    server({ accounts: [account('DEAUTHORIZED')] })
    window.history.replaceState(null, '', '/wallet/payout')
    render(<App />)
    expect(await screen.findByTestId('payout-state')).toHaveAttribute('data-state', 'closed')
    expect(screen.queryByTestId('payout-onboard')).not.toBeInTheDocument()
  })
})
