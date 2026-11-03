import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'

/*
 * The cash account (Q87, docs/quizbuks/plans/Q87-fee-account.md): the figures of `qb.sponsor.wallet`, buying and
 * selling Kudos by the administrators' switches, the platform's invoices paid from the cash and their documents, and
 * where a top-up goes when both targets are allowed. The page records nothing; it shows what the server answers.
 */

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

interface Server {
  /** The row of `qb.sponsor.wallet` over the usual one. */
  wallet?: Record<string, unknown>
  /** The row of `qb.sponsor.topup-settings` over the usual one (both targets, buying and selling on). */
  settings?: Record<string, unknown>
  invoices?: Record<string, unknown>[]
  exchanges?: Record<string, unknown>[]
  buy?: { status: number; body: unknown }
  pay?: { status: number; body: unknown }
}

const WALLET = { balance: '2000', held: '0', pendingCount: 0, pendingAmount: '0', toppedUp: '6000', takenBack: '0',
  topUpCount: 1, spent: '1000', cashBalance: '4000', cashHeld: '0', unpaidTotal: '1100', unpaidCount: 1,
  overdueCount: 0, billingMode: 'PREPAID', creditLimit: '0' }
const SETTINGS = { targets: 'KUDOS,CASH', defaultTarget: 'KUDOS', buyEnabled: 'true', sellEnabled: 'true',
  feeBlock: 'true' }
const INVOICE = { invoiceId: 'i1', invoiceNo: 'QBF-2026-00001', period: '2026-01', billingMode: 'PREPAID',
  amount: '1000', tax: '100', total: '1100', status: 'OPEN', issuedAt: '2026-01-02T00:00:00Z', dueOn: '2026-02-01',
  paidAt: null, documentRunId: null }

function server(options: Server = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 'u2', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.topup', 'qb.sponsor.finance.read'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: [{ id: 's', version: 1, attributes: { reviewStatus: 'APPROVED' } }] })
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:topup-status':
        return json(200, [{ code: 'OPEN', label: 'Awaiting payment' }, { code: 'PAID', label: 'Paid' }])
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:fee-invoice-status':
        return json(200, [{ code: 'OPEN', label: 'Open' }, { code: 'OVERDUE', label: 'Overdue' },
          { code: 'PAID', label: 'Paid' }, { code: 'VOID', label: 'Void' }])
      case '/api/queries/qb.sponsor.wallet':
        return json(200, { items: [options.wallet ?? WALLET], offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.topup-settings':
        return json(200, { items: [options.settings ?? SETTINGS], offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.kudos-exchanges':
        return json(200, { items: options.exchanges ?? [{ exchangeId: 'x1', direction: 'BUY', amount: '500',
          exchangedAt: '2026-01-03T00:00:00Z' }], offset: 0, limit: 10, total: options.exchanges?.length ?? 1 })
      case '/api/queries/qb.sponsor.fee-invoices':
        return json(200, { items: options.invoices ?? [INVOICE], offset: 0, limit: 10, total: options.invoices?.length ?? 1 })
      case '/api/queries/qb.sponsor.topups':
        return json(200, { items: [], offset: 0, limit: 20, total: 0 })
      case '/api/queries/qb.sponsor.bank-account':
        return json(200, { items: [{ bank: 'Bank', branch: 'Main', accountType: 'ORDINARY', accountNumber: '1',
          accountHolder: 'QuizBuks' }], offset: 0, limit: 1 })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/processes/QB_KUDOS_BUY/latest':
        return json(options.buy?.status ?? 200, options.buy?.body
          ?? { output: { exchangeId: 'x2', direction: 'BUY', amount: 1500, kudosBalance: 3500, cashBalance: 2500 } })
      case '/api/processes/QB_KUDOS_SELL/latest':
        return json(200, { output: { exchangeId: 'x3', direction: 'SELL', amount: 200, kudosBalance: 1800, cashBalance: 4200 } })
      case '/api/processes/QB_FEE_INVOICE_PAY/latest':
        return json(options.pay?.status ?? 200, options.pay?.body
          ?? { output: { invoiceId: 'i1', invoiceNo: 'QBF-2026-00001', status: 'PAID', total: 1100, cashBalance: 2900 } })
      case '/api/processes/QB_FEE_INVOICE_DOCUMENT/latest':
        return json(200, { output: { documentRunId: 'd1', documentNo: 'QBF-2026-00001' } })
      case '/api/documents/runs/d1/pdf':
        return new Response('%PDF-1.7', { status: 200, headers: { 'Content-Type': 'application/pdf',
          'Content-Disposition': 'attachment; filename="QBF-2026-00001.pdf"' } })
      case '/api/processes/QB_TOPUP_START/latest':
        return json(200, { output: { topUpId: 't9', status: 'OPENING', jobId: 'j1' } })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbTopUp/entities/t9':
        return json(200, { id: 't9', version: 1, attributes: { topUpId: 't9', amount: 1500, status: 'OPENING',
          createdAt: '2026-01-03T00:00:00Z', target: 'CASH' } })
      default:
        return json(404, {})
    }
  })
  return { posted, processCalls: () => posted.filter((p) => p.path.startsWith('/api/processes/')) }
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
  window.sessionStorage.clear()
  window.localStorage.clear()
})
afterEach(() => vi.restoreAllMocks())

describe('the cash account', () => {
  it('shows the cash, the invoices unpaid, how the platform bills and the exchanges, all through formatKudos', async () => {
    server()
    window.history.replaceState(null, '', '/wallet')
    const { container } = render(<App />)
    const cash = await screen.findByTestId('wallet-cash')
    expect(within(cash).getByTestId('wallet-cash-balance')).toHaveTextContent('4,000 Kudos')
    expect(within(cash).getByTestId('wallet-cash-figures')).toHaveTextContent('Unpaid invoices1,100 Kudos (invoices: 1)')
    expect(within(cash).getByTestId('wallet-billing')).toHaveAttribute('data-mode', 'PREPAID')
    expect(screen.getByTestId('wallet-unpaid')).toHaveTextContent('unpaid invoices of 1,100 Kudos')
    expect(screen.queryByTestId('wallet-overdue')).not.toBeInTheDocument()
    const exchanges = await screen.findAllByTestId('exchange-row')
    expect(exchanges).toHaveLength(1)
    expect(within(exchanges[0]).getByTestId('exchange-row-direction')).toHaveTextContent('Bought Kudos')
    expect(within(exchanges[0]).getByTestId('exchange-row-amount')).toHaveTextContent('500 Kudos')
    const invoices = await screen.findAllByTestId('invoice-row')
    expect(invoices).toHaveLength(1)
    expect(within(invoices[0]).getByTestId('invoice-row-status')).toHaveTextContent('Open')
    expect(within(invoices[0]).getByTestId('invoice-row-total')).toHaveTextContent('1,100 Kudos')
    expect(screen.getByTestId('wallet')).not.toHaveTextContent('JPY')
    await expectAccessible(container)
  })

  it('warns of an overdue invoice and shows the credit of a monthly-billed sponsor', async () => {
    server({ wallet: { ...WALLET, overdueCount: 1, billingMode: 'POSTPAID', creditLimit: '20000' },
      invoices: [{ ...INVOICE, status: 'OVERDUE' }] })
    window.history.replaceState(null, '', '/wallet')
    render(<App />)
    expect(await screen.findByTestId('wallet-overdue')).toHaveTextContent('An invoice is overdue (overdue: 1)')
    expect(screen.getByTestId('wallet-billing')).toHaveAttribute('data-mode', 'POSTPAID')
    expect(screen.getByTestId('wallet-cash-figures')).toHaveTextContent('Credit limit20,000 Kudos')
    const row = (await screen.findAllByTestId('invoice-row'))[0]
    expect(within(row).getByTestId('invoice-row-status')).toHaveAttribute('data-status', 'OVERDUE')
    expect(within(row).getByTestId('invoice-row-pay')).toBeEnabled()
  })

  it('buys Kudos from the cash account and tells both balances as the server answered', async () => {
    const { processCalls } = server()
    window.history.replaceState(null, '', '/wallet')
    const user = userEvent.setup()
    render(<App />)
    await user.click(await screen.findByTestId('kudos-buy'))
    const dialog = screen.getByTestId('exchange-dialog')
    expect(dialog).toHaveAttribute('data-direction', 'BUY')
    await user.type(screen.getByTestId('exchange-amount'), '1,500')
    await user.click(screen.getByTestId('exchange-submit'))
    expect(await screen.findByTestId('exchange-done')).toHaveTextContent('Bought 1,500 Kudos. Kudos: 3,500 Kudos, cash: 2,500 Kudos.')
    expect(processCalls()).toEqual([{ path: '/api/processes/QB_KUDOS_BUY/latest', body: { amount: 1500 } }])
    expect(screen.queryByTestId('exchange-dialog')).not.toBeInTheDocument()
  })

  it('sells Kudos back only when the administrators allow it, and shows the server\'s refusal of a purchase', async () => {
    server({ settings: { ...SETTINGS, sellEnabled: 'false' }, buy: { status: 422, body: { status: 422,
      code: 'QB_KUDOS_EXCHANGE_SHORT', detail: 'Refused', violations: [{ field: 'amount',
        ruleCode: 'QB_KUDOS_EXCHANGE_SHORT', message: 'The cash account has 4000; you asked for 6000.' }] } } })
    window.history.replaceState(null, '', '/wallet')
    const user = userEvent.setup()
    render(<App />)
    await screen.findByTestId('kudos-buy')
    expect(screen.queryByTestId('kudos-sell')).not.toBeInTheDocument()
    await user.click(screen.getByTestId('kudos-buy'))
    await user.type(screen.getByTestId('exchange-amount'), '6000')
    await user.click(screen.getByTestId('exchange-submit'))
    expect(await screen.findByText('The cash account has 4000; you asked for 6000.')).toBeInTheDocument()
    expect(screen.getByTestId('exchange-dialog')).toBeInTheDocument()
  })

  it('says buying is paused when the switch is off, and offers no button', async () => {
    server({ settings: { ...SETTINGS, buyEnabled: 'false' } })
    window.history.replaceState(null, '', '/wallet')
    render(<App />)
    expect(await screen.findByTestId('kudos-buy-off')).toBeInTheDocument()
    expect(screen.queryByTestId('kudos-buy')).not.toBeInTheDocument()
  })

  it('pays an open invoice from the cash account, and downloads its document as kept', async () => {
    const { processCalls } = server()
    URL.createObjectURL = vi.fn(() => 'blob:invoice')
    URL.revokeObjectURL = vi.fn()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    window.history.replaceState(null, '', '/wallet')
    const user = userEvent.setup()
    render(<App />)
    const row = (await screen.findAllByTestId('invoice-row'))[0]
    await user.click(within(row).getByTestId('invoice-row-pay'))
    expect(await screen.findByTestId('invoice-notice')).toHaveTextContent('Invoice QBF-2026-00001 paid. Cash: 2,900 Kudos.')
    expect(processCalls()[0]).toEqual({ path: '/api/processes/QB_FEE_INVOICE_PAY/latest', body: { invoiceId: 'i1' } })
    await user.click(within((await screen.findAllByTestId('invoice-row'))[0]).getByTestId('invoice-row-document'))
    await waitFor(() => expect(click).toHaveBeenCalled())
    expect(processCalls()[1]).toEqual({ path: '/api/processes/QB_FEE_INVOICE_DOCUMENT/latest',
      body: { invoiceId: 'i1', language: 'en' } })
    expect(click.mock.contexts[0]).toHaveProperty('download', 'QBF-2026-00001.pdf')
  })

  it('cannot pay an invoice the cash does not cover', async () => {
    server({ wallet: { ...WALLET, cashBalance: '500' } })
    window.history.replaceState(null, '', '/wallet')
    render(<App />)
    const row = (await screen.findAllByTestId('invoice-row'))[0]
    expect(within(row).getByTestId('invoice-row-pay')).toBeDisabled()
    expect(within(row).getByTestId('invoice-row-pay')).toHaveTextContent('cash 500 Kudos is not enough')
  })
})

describe('where a top-up goes', () => {
  it('offers the cash account when both targets are allowed, and sends the choice', async () => {
    const { processCalls } = server()
    window.history.replaceState(null, '', '/wallet')
    const user = userEvent.setup()
    render(<App />)
    await user.click(await screen.findByTestId('topup-open'))
    const target = await screen.findByTestId('topup-target')
    expect(within(target).getByTestId('topup-target-KUDOS')).toHaveAttribute('data-state', 'checked')
    await user.click(within(target).getByTestId('topup-target-CASH'))
    await user.type(screen.getByTestId('topup-amount'), '1500')
    await user.click(screen.getByTestId('topup-submit'))
    await waitFor(() => expect(processCalls()).toHaveLength(1))
    expect(processCalls()[0]).toEqual({ path: '/api/processes/QB_TOPUP_START/latest', body: { amount: 1500, target: 'CASH' } })
  })

  it('asks nothing when only the Kudos wallet is allowed, and sends no target', async () => {
    const { processCalls } = server({ settings: { ...SETTINGS, targets: 'KUDOS' } })
    window.history.replaceState(null, '', '/wallet')
    const user = userEvent.setup()
    render(<App />)
    await user.click(await screen.findByTestId('topup-open'))
    await screen.findByTestId('topup-amount')
    expect(screen.queryByTestId('topup-target')).not.toBeInTheDocument()
    await user.type(screen.getByTestId('topup-amount'), '1500')
    await user.click(screen.getByTestId('topup-submit'))
    await waitFor(() => expect(processCalls()).toHaveLength(1))
    expect(processCalls()[0].body).toEqual({ amount: 1500 })
  })
})
