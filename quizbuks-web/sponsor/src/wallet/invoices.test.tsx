import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'

/*
 * The platform's invoices (Q87b, docs/quizbuks/plans/Q87-fee-account.md): the fees unpaid and how the platform bills
 * (`qb.sponsor.wallet`), the invoices paid from the cash account and their documents. The page records nothing; it
 * shows what the server answers, and every amount goes through formatKudos.
 */

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

interface Server {
  /** The row of `qb.sponsor.wallet` over the usual one. */
  wallet?: Record<string, unknown>
  invoices?: Record<string, unknown>[]
  pay?: { status: number; body: unknown }
}

const WALLET = { balance: '2000', held: '0', pendingCount: 0, pendingAmount: '0', toppedUp: '6000', takenBack: '0',
  topUpCount: 1, spent: '1000', cashBalance: '4000', cashHeld: '0', unpaidTotal: '1100', unpaidCount: 1,
  overdueCount: 0, billingMode: 'PREPAID', creditLimit: '0' }
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
      case '/api/queries/qb.sponsor.cash-settings':
        return json(200, { items: [{ targets: 'KUDOS,CASH', defaultTarget: 'KUDOS', buyEnabled: true,
          sellEnabled: false }], offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.fee-invoices':
        return json(200, { items: options.invoices ?? [INVOICE], offset: 0, limit: 10, total: options.invoices?.length ?? 1 })
      case '/api/queries/qb.sponsor.topups':
        return json(200, { items: [], offset: 0, limit: 20, total: 0 })
      case '/api/queries/qb.sponsor.bank-account':
        return json(200, { items: [{ bank: 'Bank', branch: 'Main', accountType: 'ORDINARY', accountNumber: '1',
          accountHolder: 'QuizBuks' }], offset: 0, limit: 1 })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/processes/QB_FEE_INVOICE_PAY/latest':
        return json(options.pay?.status ?? 200, options.pay?.body
          ?? { output: { invoiceId: 'i1', invoiceNo: 'QBF-2026-00001', status: 'PAID', total: 1100, cashBalance: 2900 } })
      case '/api/processes/QB_FEE_PAY_EARLY/latest':
        return json(200, { output: { count: 2, total: 2200, cashBalance: 1800, invoiceNos: ['QBF-2026-00002', 'QBF-2026-00003'],
          remaining: 0 } })
      case '/api/processes/QB_FEE_INVOICE_DOCUMENT/latest':
        return json(200, { output: { documentRunId: 'd1', documentNo: 'QBF-2026-00001' } })
      case '/api/documents/runs/d1/pdf':
        return new Response('%PDF-1.7', { status: 200, headers: { 'Content-Type': 'application/pdf',
          'Content-Disposition': 'attachment; filename="QBF-2026-00001.pdf"' } })
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

describe("the platform's invoices", () => {
  it('shows the invoices unpaid and how the platform bills, all through formatKudos', async () => {
    server()
    window.history.replaceState(null, '', '/money/bills')
    render(<App />)
    const billing = await screen.findByTestId('wallet-billing')
    expect(billing).toHaveAttribute('data-mode', 'PREPAID')
    expect(billing).toHaveTextContent('1,100 Kudos (invoices: 1)')
    expect(billing).toHaveTextContent('Taken from the cash account when charged')
    expect(screen.getByTestId('wallet-unpaid')).toHaveTextContent('unpaid invoices of 1,100 Kudos')
    const row = (await screen.findAllByTestId('invoice-row'))[0]
    expect(row).toHaveAttribute('data-status', 'OPEN')
    expect(within(row).getByTestId('invoice-row-number')).toHaveTextContent('QBF-2026-00001')
    expect(within(row).getByTestId('invoice-row-total')).toHaveTextContent('1,100 Kudos')
    expect(within(row).getByTestId('invoice-row-status')).toHaveTextContent('Open')
    expect(screen.getByTestId('wallet-invoices').textContent).not.toMatch(/JPY|yen|円/)
    await expectAccessible(screen.getByTestId('bills'))
  })

  it('warns of an overdue invoice and shows the credit of a monthly-billed Sponsor', async () => {
    server({ wallet: { ...WALLET, overdueCount: 1, billingMode: 'POSTPAID', creditLimit: '20000' },
      invoices: [{ ...INVOICE, status: 'OVERDUE', billingMode: 'POSTPAID' }] })
    window.history.replaceState(null, '', '/money/bills')
    render(<App />)
    expect(await screen.findByTestId('wallet-overdue')).toHaveTextContent('overdue: 1')
    const billing = screen.getByTestId('wallet-billing')
    expect(billing).toHaveAttribute('data-mode', 'POSTPAID')
    expect(billing).toHaveTextContent('Credit limit')
    expect(billing).toHaveTextContent('20,000 Kudos')
    expect((await screen.findAllByTestId('invoice-row'))[0]).toHaveAttribute('data-status', 'OVERDUE')
  })

  it('pays an open invoice from the cash account, and downloads its document as kept', async () => {
    const { processCalls } = server()
    URL.createObjectURL = vi.fn(() => 'blob:invoice')
    URL.revokeObjectURL = vi.fn()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    window.history.replaceState(null, '', '/money/bills')
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

  it('pays the fees not invoiced yet early, from the cash account', async () => {
    const { processCalls } = server({ wallet: { ...WALLET, unpaidTotal: '0', unpaidCount: 0, billingMode: 'POSTPAID',
      creditLimit: '20000', uninvoicedTotal: '2200' }, invoices: [] })
    window.history.replaceState(null, '', '/money/bills')
    const user = userEvent.setup()
    render(<App />)
    const billing = await screen.findByTestId('wallet-billing')
    expect(billing).toHaveTextContent('Not invoiced yet')
    expect(billing).toHaveTextContent('2,200 Kudos')
    expect(screen.queryByTestId('wallet-unpaid')).not.toBeInTheDocument()
    await user.click(screen.getByTestId('invoice-pay-early'))
    expect(await screen.findByTestId('invoice-notice')).toHaveTextContent('Paid 2,200 Kudos early (invoices: 2). Cash: 1,800 Kudos.')
    expect(processCalls()[0]).toEqual({ path: '/api/processes/QB_FEE_PAY_EARLY/latest', body: {} })
  })

  it('cannot pay early what the cash does not cover', async () => {
    server({ wallet: { ...WALLET, cashBalance: '500', uninvoicedTotal: '2200' } })
    window.history.replaceState(null, '', '/money/bills')
    render(<App />)
    const button = await screen.findByTestId('invoice-pay-early')
    expect(button).toBeDisabled()
    expect(button).toHaveTextContent('cash 500 Kudos is not enough')
  })

  it('cannot pay an invoice the cash does not cover', async () => {
    server({ wallet: { ...WALLET, cashBalance: '500' } })
    window.history.replaceState(null, '', '/money/bills')
    render(<App />)
    const row = (await screen.findAllByTestId('invoice-row'))[0]
    expect(within(row).getByTestId('invoice-row-pay')).toBeDisabled()
    expect(within(row).getByTestId('invoice-row-pay')).toHaveTextContent('cash 500 Kudos is not enough')
  })
})
