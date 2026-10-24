import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { todayInJapan } from './api'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

const ACCOUNT = { bank: 'Mizuho Bank (0001)', branch: 'Head office (100)', accountType: 'Ordinary',
  accountNumber: '1234567', accountHolder: 'クイズブックス（カ', min: '1000', max: '1000000' }

const bankTopUp = (status: string, extra: Record<string, unknown> = {}) => ({
  topUpId: 'b1', sponsorId: 'u2', method: 'BANK', transferCode: '1000042', requestedAmount: 50000, amount: 50000,
  status, createdAt: '2026-10-22T01:00:00Z', expiresAt: null, checkoutUrl: null, paidAt: null, payerName: null,
  transferDate: null, receivedOn: null, ...extra,
})

/** The server for a signed-in, approved sponsor with one bank top-up in `state`. Records what was posted. */
function server(state: Record<string, unknown> | null, account: Record<string, unknown> = ACCOUNT) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
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
        return json(200, [{ code: 'AWAITING', label: 'Awaiting transfer' }, { code: 'PAID', label: 'Paid' },
          { code: 'REPORTED', label: 'Transfer reported' }, { code: 'CANCELLED', label: 'Cancelled' }])
      case '/api/queries/qb.sponsor.wallet':
        return json(200, { items: [{ balance: '0', held: '0', pendingCount: 0, pendingAmount: '0', toppedUp: '0',
          takenBack: '0', topUpCount: 0, spent: '0' }], offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.topups':
        return json(200, { items: state ? [{ ...state, refundedAmount: '0' }] : [], offset: 0, limit: 20,
          total: state ? 1 : 0 })
      case '/api/queries/qb.sponsor.topup-limit':
        return json(200, { items: [{ min: '1000', max: '1000000', limited: false }], offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.bank-account':
        return json(200, { items: [account], offset: 0, limit: 1 })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/processes/QB_TOPUP_BANK_START/latest':
        return json(200, { output: { topUpId: 'b1', transferCode: '1000042', amount: 50000, status: 'AWAITING' } })
      case '/api/processes/QB_TOPUP_BANK_REPORT/latest':
        return json(200, { output: { topUpId: 'b1', status: 'REPORTED' } })
      case '/api/processes/QB_TOPUP_BANK_CANCEL/latest':
        return json(200, { output: { topUpId: 'b1', status: 'CANCELLED' } })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbTopUp/entities/b1':
        return state ? json(200, { id: 'b1', version: 1, attributes: state }) : json(404, { detail: 'Not found' })
      default:
        return json(404, {})
    }
  })
  return { processCalls: () => posted.filter((p) => p.path.startsWith('/api/processes/')) }
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
  window.sessionStorage.clear()
})
afterEach(() => {
  vi.restoreAllMocks()
})

describe('top-ups by bank transfer (Q13a)', () => {
  it('asks for one from the wallet and opens its page, never Stripe\'s', async () => {
    const { processCalls } = server(bankTopUp('AWAITING'))
    window.history.replaceState(null, '', '/wallet')
    const user = userEvent.setup()
    render(<App />)
    await user.click(await screen.findByTestId('topup-open'))
    await user.click(screen.getByTestId('topup-method-BANK'))
    expect(await screen.findByTestId('topup-limit')).toHaveTextContent('Each top-up: 1,000 Kudos to 1,000,000 Kudos.')
    await user.type(screen.getByTestId('topup-amount'), '50,000')
    expect(screen.getByTestId('topup-submit')).toHaveTextContent('Get the transfer details')
    await user.click(screen.getByTestId('topup-submit'))
    await waitFor(() => expect(window.location.pathname).toBe('/wallet/bank/b1'))
    expect(processCalls().map((call) => [call.path, call.body])).toEqual([
      ['/api/processes/QB_TOPUP_BANK_START/latest', { amount: 50000 }]])
    expect(await screen.findByTestId('bank-topup-code')).toHaveTextContent('1000042')
  })

  it('offers no bank transfer while it is not set up, and says so (Q18)', async () => {
    server(null, { bank: null, branch: null, accountType: null, accountNumber: null, accountHolder: null })
    window.history.replaceState(null, '', '/wallet')
    const user = userEvent.setup()
    render(<App />)
    await user.click(await screen.findByTestId('topup-open'))
    const bank = screen.getByTestId('topup-method-BANK')
    await waitFor(() => expect(bank).toBeDisabled())
    expect(screen.getByTestId('topup-method')).toHaveTextContent('not available yet. Please top up by card, or contact us.')
    await user.click(bank)
    expect(screen.getByTestId('topup-method-STRIPE')).toBeChecked()
  })

  it('lists a bank top-up with its code and links to its page', async () => {
    server(bankTopUp('AWAITING'))
    window.history.replaceState(null, '', '/wallet')
    render(<App />)
    const row = await screen.findByTestId('topup-row')
    expect(within(row).getByTestId('topup-row-bank')).toHaveTextContent('Bank transfer · 1000042')
    expect(within(row).getByRole('link', { name: /Details/ })).toHaveAttribute('href', '/wallet/bank/b1')
  })

  it('shows where to transfer and with which code, and reports it sent', async () => {
    const { processCalls } = server(bankTopUp('AWAITING'))
    window.history.replaceState(null, '', '/wallet/bank/b1')
    const user = userEvent.setup()
    render(<App />)
    expect(await screen.findByTestId('bank-topup-state')).toHaveTextContent(
      'Transfer 50,000 Kudos to the account below and put the code 1000042 before your name')
    expect(await screen.findByTestId('bank-account-number')).toHaveTextContent('1234567')
    expect(screen.getByTestId('bank-report-name')).toHaveValue('1000042 ')
    expect(screen.getByTestId('bank-report-date')).toHaveValue(todayInJapan())
    await expectAccessible()
    await user.type(screen.getByTestId('bank-report-name'), 'カ）エスバンク')
    await user.click(screen.getByTestId('bank-report-submit'))
    expect(await screen.findByTestId('bank-report-done')).toBeInTheDocument()
    expect(processCalls().map((call) => call.body)).toEqual([
      { topUpId: 'b1', payerName: '1000042 カ）エスバンク', transferDate: todayInJapan() }])
  })

  it('withdraws a request after asking', async () => {
    const { processCalls } = server(bankTopUp('REPORTED', { payerName: '1000042 カ）エス',
      transferDate: '2026-10-22' }))
    window.history.replaceState(null, '', '/wallet/bank/b1')
    const user = userEvent.setup()
    render(<App />)
    expect(await screen.findByTestId('bank-report-submit')).toHaveTextContent('Correct the report')
    await user.click(screen.getByTestId('bank-cancel'))
    expect(await screen.findByTestId('bank-cancel-dialog')).toHaveTextContent('money that arrives is still credited')
    await user.click(screen.getByTestId('bank-cancel-confirm'))
    await waitFor(() => expect(processCalls().map((call) => call.path)).toEqual([
      '/api/processes/QB_TOPUP_BANK_CANCEL/latest']))
    expect(processCalls()[0].body).toEqual({ topUpId: 'b1' })
  })

  it('shows what was credited when less arrived than asked, with nothing more to do', async () => {
    server(bankTopUp('PAID', { amount: 49560, paidAt: '2026-10-23T03:00:00Z', receivedOn: '2026-10-23' }))
    window.history.replaceState(null, '', '/wallet/bank/b1')
    render(<App />)
    const state = await screen.findByTestId('bank-topup-state')
    expect(state).toHaveTextContent('49,560 Kudos were added to your wallet')
    expect(state).toHaveTextContent('You asked for 50,000 Kudos')
    expect(screen.getByTestId('bank-topup-details')).toHaveTextContent('Received on2026-10-23')
    expect(screen.queryByTestId('bank-report')).toBeNull()
    expect(screen.queryByTestId('bank-cancel')).toBeNull()
    expect(screen.queryByTestId('bank-account')).toBeNull()
  })

  it('reads a card top-up\'s address as not found', async () => {
    server({ ...bankTopUp('OPEN'), method: 'STRIPE' })
    window.history.replaceState(null, '', '/wallet/bank/b1')
    render(<App />)
    expect(await screen.findByTestId('topup-missing')).toBeInTheDocument()
  })
})
