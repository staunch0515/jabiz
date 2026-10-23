import { configureClient, session } from '@jabiz/client'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { netOf, smallestWithdrawal, usableFrom, type BankSettings } from './api'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device' }

const ACCOUNT = { bankCode: '0001', bankName: 'みずほ銀行', branchCode: '001', branchName: '東京営業部',
  accountType: 'ORDINARY', accountNumber: '****', accountHolder: 'ヤマダ タロウ',
  changedAt: '2026-01-01T00:00:00Z' }

interface Server {
  /** The row of `qb.app.bank-account`. */
  bank?: Record<string, unknown>
  /** Answers of the processes, by name. */
  answers?: Record<string, { status: number; body: unknown }>
}

/** The server as the bank page sees it, for a verified taker in Japan. Records the processes run. */
function server(options: Server = {}) {
  const posted: { path: string; body: Record<string, unknown>; key: string | null }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body, key: request.headers.get('Idempotency-Key') })
    const process = /^\/api\/processes\/([A-Z_]+)\/latest$/.exec(path)?.[1]
    if (process) {
      const answer = options.answers?.[process] ?? { status: 200, body: { output: {} } }
      return json(answer.status, answer.body)
    }
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
      case '/api/queries/qb.app.wallet':
        return json(200, { items: [{ available: '5000', totalEarned: '5000', pendingCount: 0, pendingAmount: '0',
          inTransfer: '0', held: '0', heldOpen: '0', heldUntil: null }], offset: 0, limit: 1 })
      case '/api/queries/qb.app.bank-account':
        return json(200, { items: [options.bank ?? { enabled: true, fee: '200', minimum: '1000', cooldownHours: '72',
          payoutMethod: null }], offset: 0, limit: 1 })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      default:
        return json(404, {})
    }
  })
  const processCalls = () => posted.filter((p) => p.path.startsWith('/api/processes/'))
  return { processCalls }
}

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => vi.restoreAllMocks())

describe('the bank helpers', () => {
  it('counts the cooling-off period from the last change, and the fee from the amount', () => {
    const settings: BankSettings = { enabled: true, fee: 200, minimum: 1000, cooldownHours: 72, method: 'BANK',
      account: { ...ACCOUNT, accountType: 'ORDINARY' } }
    expect(usableFrom(settings)?.toISOString()).toBe('2026-01-04T00:00:00.000Z')
    expect(usableFrom({ ...settings, account: null })).toBeNull()
    expect(netOf(3000, 200)).toBe(2800)
    expect(netOf(100, 200)).toBe(0)
    expect(smallestWithdrawal(settings)).toBe(1000)
    expect(smallestWithdrawal({ ...settings, fee: 1500 })).toBe(1501)
  })
})

describe('the bank page', () => {
  it('says when bank withdrawals are off', async () => {
    server({ bank: { enabled: false, fee: '0', minimum: '1000', cooldownHours: '72' } })
    window.history.replaceState(null, '', '/wallet/bank')
    render(<App />)
    expect(await screen.findByTestId('bank-off')).toBeInTheDocument()
    expect(screen.queryByTestId('bank-account')).toBeNull()
  })

  it('saves an account, and says when it is another user\'s', async () => {
    const { processCalls } = server({ answers: { QB_BANK_ACCOUNT_SAVE: { status: 200, body: { output: {
      bankAccountId: null, saved: false, reason: 'QB_BANK_ACCOUNT_IN_USE', usableFrom: null } } } } })
    window.history.replaceState(null, '', '/wallet/bank')
    const user = userEvent.setup()
    render(<App />)
    // No account yet: paid by bank cannot be chosen, the form is open.
    expect(await screen.findByTestId('bank-account-form')).toBeInTheDocument()
    expect(screen.getByTestId('bank-method-BANK')).toBeDisabled()
    await user.type(screen.getByTestId('bank-bankCode'), '0001')
    await user.type(screen.getByTestId('bank-bankName'), 'Mizuho')
    await user.type(screen.getByTestId('bank-branchCode'), '001')
    await user.type(screen.getByTestId('bank-branchName'), 'Tokyo')
    await user.type(screen.getByTestId('bank-bankAccountNumber'), '1234567')
    await user.type(screen.getByTestId('bank-accountHolder'), 'ヤマダ タロウ')
    await user.click(screen.getByTestId('bank-account-save'))
    expect(await screen.findByTestId('bank-in-use')).toBeInTheDocument()
    expect(processCalls().at(-1)).toMatchObject({ path: '/api/processes/QB_BANK_ACCOUNT_SAVE/latest', body: {
      bankCode: '0001', accountType: 'ORDINARY', bankAccountNumber: '1234567', accountHolder: 'ヤマダ タロウ' } })
  })

  it('shows the saved account masked and asks for a withdrawal with the fee', async () => {
    const { processCalls } = server({
      bank: { enabled: true, fee: '200', minimum: '1000', cooldownHours: '72', payoutMethod: 'BANK', ...ACCOUNT },
      answers: { QB_WITHDRAWAL_REQUEST: { status: 200, body: { output: { transferId: 't1', status: 'REVIEW',
        reason: null, approvalRequestId: 'r1' } } } },
    })
    window.history.replaceState(null, '', '/wallet/bank')
    const user = userEvent.setup()
    render(<App />)
    expect(await screen.findByTestId('bank-account-number')).toHaveTextContent('****')
    expect(screen.queryByTestId('bank-cooling')).toBeNull()
    await user.type(await screen.findByTestId('bank-amount'), '3000')
    expect(screen.getByTestId('bank-net')).toHaveTextContent('2,800 Kudos')
    await user.click(screen.getByTestId('bank-withdraw-submit'))
    expect(await screen.findByTestId('bank-withdraw-done')).toBeInTheDocument()
    const call = processCalls().find((p) => p.path === '/api/processes/QB_WITHDRAWAL_REQUEST/latest')
    expect(call?.body).toEqual({ amount: 3000 })
    expect(call?.key).toBeTruthy()
  })

  it('switches how the taker is paid', async () => {
    const { processCalls } = server({ bank: { enabled: true, fee: '0', minimum: '1000', cooldownHours: '72',
      payoutMethod: 'STRIPE', ...ACCOUNT } })
    window.history.replaceState(null, '', '/wallet/bank')
    const user = userEvent.setup()
    render(<App />)
    await user.click(await screen.findByTestId('bank-method-BANK'))
    await waitFor(() => expect(processCalls().at(-1)).toMatchObject({
      path: '/api/processes/QB_PAYOUT_METHOD_SET/latest', body: { method: 'BANK' } }))
    // Paid by Stripe: no withdrawal form.
    expect(screen.queryByTestId('bank-withdraw')).toBeNull()
  })
})
