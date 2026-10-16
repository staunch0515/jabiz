import { configureClient, session } from '@jabiz/client'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { goToStripe } from './stripePage'

vi.mock('./stripePage', async (original) => ({
  ...(await original<typeof import('./stripePage')>()),
  goToStripe: vi.fn(),
}))

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

const CHECKOUT = 'https://checkout.stripe.com/c/pay/cs_test_1'
const LATER = '2099-01-01T00:00:00Z'

const topUp = (status: string, extra: Record<string, unknown> = {}) => ({
  topUpId: 't1', sponsorId: 'u2', amount: 1500, status, createdAt: '2026-10-14T01:00:00Z', expiresAt: LATER,
  checkoutUrl: status === 'OPEN' || status === 'PAID' ? CHECKOUT : null, paidAt: status === 'PAID'
    ? '2026-10-14T01:02:00Z' : null, ...extra,
})

interface Server {
  /** The top-up's states, read in turn (the last one repeats); null for none. */
  topUps?: (Record<string, unknown> | null)[]
  start?: { status: number; body: unknown }
  receipt?: { status: number; body: unknown }
}

/** The server as the console sees it, for a signed-in, approved sponsor. Records what was posted. */
function server(options: Server = {}) {
  const posted: { path: string; body: Record<string, unknown>; key: string | null }[] = []
  const states = options.topUps ?? [topUp('OPEN')]
  let reads = 0
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body, key: request.headers.get('Idempotency-Key') })
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 'u2', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.topup'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: [{ id: 's', version: 1, attributes: { reviewStatus: 'APPROVED' } }] })
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:topup-status':
        return json(200, [{ code: 'OPENING', label: 'Opening' }, { code: 'OPEN', label: 'Awaiting payment' },
          { code: 'PAID', label: 'Paid' }, { code: 'EXPIRED', label: 'Expired' }, { code: 'FAILED', label: 'Failed' }])
      case '/api/queries/qb.sponsor.wallet':
        return json(200, { items: [{ balance: '5000', held: '1000', pendingCount: 2, pendingAmount: '700',
          toppedUp: '6000', takenBack: '0', topUpCount: 2, spent: '1000' }], offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.topups':
        return json(200, { items: [
          { topUpId: 't1', amount: '1500', status: 'OPEN', createdAt: '2026-10-14T01:00:00Z', expiresAt: LATER,
            checkoutUrl: CHECKOUT, paidAt: null, refundedAmount: '0' },
          { topUpId: 't0', amount: '6000', status: 'PAID', createdAt: '2026-10-13T01:00:00Z', expiresAt: LATER,
            checkoutUrl: null, paidAt: '2026-10-13T01:01:00Z', refundedAmount: '0' },
        ], offset: 0, limit: 20, total: 2 })
      case '/api/processes/QB_TOPUP_START/latest':
        return json(options.start?.status ?? 200, options.start?.body
          ?? { output: { topUpId: 't1', status: 'OPENING', jobId: 'j1' } })
      case '/api/processes/QB_TOPUP_RECEIPT_ISSUE/latest':
        return json(options.receipt?.status ?? 200, options.receipt?.body
          ?? { output: { runId: 'r1', documentNo: 't0', issued: true } })
      case '/api/documents/runs/r1/pdf':
        return new Response('%PDF-1.7', { status: 200, headers: { 'Content-Type': 'application/pdf',
          'Content-Disposition': 'attachment; filename="t0.pdf"' } })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbTopUp/entities/t1': {
        const state = states[Math.min(reads++, states.length - 1)]
        return state ? json(200, { id: 't1', version: 1, attributes: state }) : json(404, { detail: 'Not found' })
      }
      default:
        return json(404, {})
    }
  })
  const processCalls = () => posted.filter((p) => p.path.startsWith('/api/processes/'))
  return { posted, processCalls, reads: () => reads }
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
  window.sessionStorage.clear()
  vi.mocked(goToStripe).mockClear()
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('the wallet', () => {
  it('shows what is available, the waiting rewards and the top-ups, all in Kudos', async () => {
    server()
    window.history.replaceState(null, '', '/wallet')
    render(<App />)
    // Available is the balance less what disputes hold.
    expect(await screen.findByTestId('wallet-available')).toHaveTextContent('4,000 Kudos')
    expect(screen.getByTestId('wallet-pending')).toHaveTextContent('700 Kudos')
    expect(screen.getByTestId('wallet-figures')).toHaveTextContent('Held for open disputes1,000 Kudos')
    const rows = await screen.findAllByTestId('topup-row')
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByTestId('topup-row-status')).toHaveTextContent('Awaiting payment')
    expect(within(rows[0]).getByRole('link', { name: /Continue to payment/ })).toHaveAttribute('href',
      '/wallet/topups/t1')
    expect(within(rows[1]).getByRole('link', { name: /Details/ })).toBeInTheDocument()
    expect(document.body).not.toHaveTextContent('JPY')
    expect(screen.getByRole('link', { name: 'Wallet' })).toHaveAttribute('aria-current', 'page')
  })

  it('starts a top-up, waits for its checkout and goes there once', async () => {
    const { processCalls, reads } = server({ topUps: [topUp('OPENING', { checkoutUrl: null }), topUp('OPEN')] })
    window.history.replaceState(null, '', '/wallet')
    const user = userEvent.setup()
    render(<App />)
    await user.click(await screen.findByTestId('topup-open'))
    const amount = screen.getByTestId('topup-amount')
    await user.type(amount, '0')
    await user.click(screen.getByTestId('topup-submit'))
    expect(amount).toHaveAccessibleDescription(/greater than 0/)
    expect(processCalls()).toHaveLength(0)
    await user.clear(amount)
    await user.type(amount, '1,500')
    await user.click(screen.getByTestId('topup-submit'))
    await waitFor(() => expect(window.location.pathname).toBe('/wallet/topups/t1'))
    expect(processCalls()).toHaveLength(1)
    expect(processCalls()[0].body).toEqual({ amount: 1500 })
    expect(processCalls()[0].key).toBeTruthy()
    // Opening first, then open: the page leaves for Stripe's address by itself.
    await waitFor(() => expect(goToStripe).toHaveBeenCalledWith(CHECKOUT), { timeout: 4000 })
    expect(goToStripe).toHaveBeenCalledTimes(1)
    expect(screen.getByTestId('topup-state')).toHaveAttribute('data-state', 'leaving')
    expect(window.sessionStorage.length).toBe(0)
    // Back from Stripe through the browser's page cache: the top-up is read again and the checkout offered, not left
    // for by itself.
    const before = reads()
    const shown = new Event('pageshow')
    Object.defineProperty(shown, 'persisted', { value: true })
    act(() => {
      window.dispatchEvent(shown)
    })
    expect(await screen.findByTestId('topup-pay')).toBeInTheDocument()
    expect(screen.getByTestId('topup-state')).toHaveAttribute('data-state', 'open')
    await waitFor(() => expect(reads()).toBeGreaterThan(before))
    expect(goToStripe).toHaveBeenCalledTimes(1)
  })

  it('forgets the mark of a just-started top-up at once, even when it never opened', async () => {
    window.sessionStorage.setItem('qb.topup.just-started', 't1')
    server({ topUps: [null] })
    window.history.replaceState(null, '', '/wallet/topups/t1')
    render(<App />)
    expect(await screen.findByTestId('topup-missing')).toBeInTheDocument()
    expect(window.sessionStorage.getItem('qb.topup.just-started')).toBeNull()
  })

  it('a top-up left while its checkout was opening does not leave by itself when opened again', async () => {
    window.sessionStorage.setItem('qb.topup.just-started', 't1')
    server({ topUps: [topUp('OPENING', { checkoutUrl: null }), topUp('OPEN')] })
    window.history.replaceState(null, '', '/wallet/topups/t1')
    const first = render(<App />)
    expect(await screen.findByTestId('topup-state')).toHaveAttribute('data-state', 'opening')
    first.unmount()
    expect(window.sessionStorage.getItem('qb.topup.just-started')).toBeNull()
    // Opened later from the list: the checkout is open, and the page offers it.
    render(<App />)
    await waitFor(() => expect(screen.getByTestId('topup-state')).toHaveAttribute('data-state', 'open'))
    expect(screen.getByTestId('topup-pay')).toBeInTheDocument()
    expect(goToStripe).not.toHaveBeenCalled()
  })

  it('shows the server\'s refusal of an amount next to the field', async () => {
    const { processCalls } = server({ start: { status: 422, body: { status: 422, code: 'QB_TOPUP_AMOUNT_RANGE', detail: 'Refused',
      violations: [{ field: 'amount', ruleCode: 'QB_TOPUP_AMOUNT_RANGE',
        message: 'A top-up is a whole number of Kudos from 1000 to 1000000.' }] } } })
    window.history.replaceState(null, '', '/wallet')
    const user = userEvent.setup()
    render(<App />)
    await user.click(await screen.findByTestId('topup-open'))
    await user.type(screen.getByTestId('topup-amount'), '10')
    await user.click(screen.getByTestId('topup-submit'))
    expect(await screen.findByText('A top-up is a whole number of Kudos from 1000 to 1000000.')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/wallet')
    // Cancel starts the next opening afresh: no amount, no refusal, a new idempotency key.
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByTestId('topup-dialog')).not.toBeInTheDocument())
    await user.click(screen.getByTestId('topup-open'))
    expect(screen.getByTestId('topup-amount')).toHaveValue('')
    expect(screen.queryByText('A top-up is a whole number of Kudos from 1000 to 1000000.')).not.toBeInTheDocument()
    await user.type(screen.getByTestId('topup-amount'), '10')
    await user.click(screen.getByTestId('topup-submit'))
    await waitFor(() => expect(processCalls()).toHaveLength(2))
    expect(processCalls()[1].key).not.toBe(processCalls()[0].key)
  })
})

describe('the receipts', () => {
  it('offers the receipt of a paid top-up only, issued in the sponsor\'s language and downloaded as kept', async () => {
    const { processCalls } = server()
    URL.createObjectURL = vi.fn(() => 'blob:receipt')
    URL.revokeObjectURL = vi.fn()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    window.history.replaceState(null, '', '/wallet')
    const user = userEvent.setup()
    render(<App />)
    const rows = await screen.findAllByTestId('topup-row')
    expect(within(rows[0]).queryByTestId('topup-row-receipt')).not.toBeInTheDocument()
    await user.click(within(rows[1]).getByTestId('topup-row-receipt'))
    await waitFor(() => expect(click).toHaveBeenCalled())
    expect(processCalls()).toHaveLength(1)
    expect(processCalls()[0]).toMatchObject({ path: '/api/processes/QB_TOPUP_RECEIPT_ISSUE/latest',
      body: { topUpId: 't0', language: 'en' } })
    expect(click.mock.contexts[0]).toHaveProperty('download', 't0.pdf')
    expect(screen.queryByTestId('topup-receipt-problem')).not.toBeInTheDocument()
  })

  it('shows the server\'s refusal', async () => {
    server({ receipt: { status: 422, body: { status: 422, detail: 'Refused', violations: [{
      ruleCode: 'QB_RECEIPT_NO_ISSUER', message: 'Receipts cannot be issued yet.' }] } } })
    window.history.replaceState(null, '', '/wallet')
    const user = userEvent.setup()
    render(<App />)
    const rows = await screen.findAllByTestId('topup-row')
    await user.click(within(rows[1]).getByTestId('topup-row-receipt'))
    expect(await screen.findByTestId('topup-receipt-problem')).toHaveTextContent('Receipts cannot be issued yet.')
  })

  it('issues receipts in the application\'s languages only', async () => {
    const { receiptLanguage } = await import('./api')
    expect(receiptLanguage('ja')).toBe('ja')
    expect(receiptLanguage('zh-CN')).toBe('zh')
    expect(receiptLanguage('fr')).toBe('en')
    expect(receiptLanguage(undefined)).toBe('en')
  })
})

describe('a top-up\'s page', () => {
  it('never follows an address that is not Stripe\'s', async () => {
    window.sessionStorage.setItem('qb.topup.just-started', 't1')
    server({ topUps: [topUp('OPEN', { checkoutUrl: 'javascript:alert(1)' })] })
    window.history.replaceState(null, '', '/wallet/topups/t1')
    render(<App />)
    expect(await screen.findByTestId('topup-state')).toHaveAttribute('data-state', 'unsafe')
    expect(goToStripe).not.toHaveBeenCalled()
    expect(screen.queryByTestId('topup-pay')).not.toBeInTheDocument()
  })

  it('opened again, offers the checkout instead of leaving by itself', async () => {
    server({ topUps: [topUp('OPEN')] })
    window.history.replaceState(null, '', '/wallet/topups/t1')
    const user = userEvent.setup()
    render(<App />)
    expect(await screen.findByTestId('topup-state')).toHaveAttribute('data-state', 'open')
    expect(screen.getByTestId('topup-amount')).toHaveTextContent('1,500 Kudos')
    expect(goToStripe).not.toHaveBeenCalled()
    await user.click(screen.getByTestId('topup-pay'))
    expect(goToStripe).toHaveBeenCalledWith(CHECKOUT)
  })

  it('back from paying, waits for the server\'s record and starts nothing', async () => {
    const { processCalls } = server({ topUps: [topUp('OPEN'), topUp('OPEN'), topUp('PAID')] })
    window.history.replaceState(null, '', '/wallet/topups/t1?checkout=paid')
    render(<App />)
    const state = await screen.findByTestId('topup-state')
    expect(state).toHaveAttribute('data-state', 'confirming')
    // The browser's return is no proof of payment: no button to pay again, nothing posted.
    expect(screen.queryByTestId('topup-pay')).not.toBeInTheDocument()
    await waitFor(() => expect(state).toHaveAttribute('data-state', 'paid'), { timeout: 5000 })
    expect(state).toHaveTextContent('1,500 Kudos were added')
    expect(processCalls()).toHaveLength(0)
    expect(goToStripe).not.toHaveBeenCalled()
  })

  it('stops asking after a while and says the wallet will update', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const { reads } = server({ topUps: [topUp('OPEN')] })
    window.history.replaceState(null, '', '/wallet/topups/t1?checkout=paid')
    render(<App />)
    const state = await screen.findByTestId('topup-state')
    expect(state).toHaveAttribute('data-state', 'confirming')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(95_000)
    })
    await waitFor(() => expect(state).toHaveAttribute('data-state', 'confirming-late'))
    expect(state).toHaveTextContent('updated shortly')
    const count = reads()
    expect(count).toBeLessThan(25)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000)
    })
    expect(reads()).toBe(count)
    expect(screen.getByTestId('topup-check')).toBeInTheDocument()
  })

  it('back after cancelling, offers the checkout until it expires', async () => {
    server({ topUps: [topUp('OPEN')] })
    window.history.replaceState(null, '', '/wallet/topups/t1?checkout=cancelled')
    render(<App />)
    expect(await screen.findByTestId('topup-state')).toHaveAttribute('data-state', 'cancelled')
    expect(screen.getByTestId('topup-pay')).toBeInTheDocument()
    expect(goToStripe).not.toHaveBeenCalled()
  })

  it('says an expired top-up charged nothing', async () => {
    server({ topUps: [topUp('EXPIRED')] })
    window.history.replaceState(null, '', '/wallet/topups/t1?checkout=cancelled')
    render(<App />)
    expect(await screen.findByTestId('topup-state')).toHaveAttribute('data-state', 'expired')
    expect(screen.queryByTestId('topup-pay')).not.toBeInTheDocument()
  })

  it('does not show another sponsor\'s top-up', async () => {
    server({ topUps: [null] })
    window.history.replaceState(null, '', '/wallet/topups/t1')
    render(<App />)
    expect(await screen.findByTestId('topup-missing')).toBeInTheDocument()
  })
})
