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
  /** F-M1: the account's lots and payment parts. */
  lots?: Record<string, unknown>[]
  payments?: Record<string, unknown>[]
  /** F-M7: the credit notes. */
  creditNotes?: Record<string, unknown>[]
  /** F-M13: the row of `qb.sponsor.fee-invoice-sources`. */
  sources?: Record<string, unknown>
}

const WALLET = { balance: '2000', held: '0', pendingCount: 0, pendingAmount: '0', toppedUp: '6000', takenBack: '0',
  topUpCount: 1, spent: '1000', cashBalance: '4000', cashHeld: '0', unpaidTotal: '1100', unpaidCount: 1,
  overdueCount: 0, billingMode: 'PREPAID', creditLimit: '0' }
const CREDIT_NOTE = { creditNoteId: 'cn1', creditNoteNo: 'QBC-2026-00001', invoiceNo: 'QBF-2026-00001',
  publicationId: 'p1', reason: 'AUDIENCE_UNUSED', endReason: 'WITHDRAWN', usedDays: 3, amount: '400', tax: '40', total: '440',
  refundedCash: '100', refundedCredit: '340', voidedCredit: '40', used: '0', creditLeft: '340',
  issuedAt: '2026-11-05T00:00:00Z', documentNo: null }
const INVOICE = { invoiceId: 'i1', invoiceNo: 'QBF-2026-00001', period: '2026-01', billingMode: 'PREPAID',
  amount: '1000', tax: '100', total: '1100', paidAmount: '0', creditApplied: '0', status: 'OPEN', issuedAt: '2026-01-02T00:00:00Z', dueOn: '2026-02-01',
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
      case '/api/queries/qb.sponsor.fee-invoice-sources':
        return json(200, { items: [options.sources ?? { sourceGift: '200', sourcePrepayment: '300',
          sourcePrepaymentGift: '100', sourceCash: '0', sourceCreditNote: '0', sourceUnpaid: '600' }], offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.fee-credit-notes':
        return json(200, { items: options.creditNotes ?? [], offset: 0, limit: 10, total: options.creditNotes?.length ?? 0 })
      case '/api/processes/QB_FEE_CREDIT_NOTE_DOCUMENT/latest':
        return json(200, { output: { creditNoteId: 'cn1', creditNoteNo: 'QBC-2026-00001', documentRunId: 'd2',
          documentNo: 'QBC-2026-00001' } })
      case '/api/documents/runs/d2/pdf':
        return new Response('%PDF-1.7', { status: 200, headers: { 'Content-Type': 'application/pdf',
          'Content-Disposition': 'attachment; filename="QBC-2026-00001.pdf"' } })
      case '/api/queries/qb.sponsor.topups':
        return json(200, { items: [], offset: 0, limit: 20, total: 0 })
      case '/api/queries/qb.sponsor.bank-account':
        return json(200, { items: [{ bank: 'Bank', branch: 'Main', accountType: 'ORDINARY', accountNumber: '1',
          accountHolder: 'QuizBuks' }], offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.fee-account-lots':
        return json(200, { items: options.lots ?? [], offset: 0, limit: 10, total: options.lots?.length ?? 0 })
      case '/api/queries/qb.sponsor.fee-payments':
        return json(200, { items: options.payments ?? [], offset: 0, limit: 10, total: options.payments?.length ?? 0 })
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:fee-sub-account':
        return json(200, [{ code: 'FEE_CREDIT', label: 'Granted credit' }, { code: 'CASH', label: 'Cash' }])
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:fee-credit-source':
        return json(200, [{ code: 'MANUAL', label: 'Granted by hand' }])
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:cash-source':
        return json(200, [{ code: 'CARD', label: 'Card top-up' }])
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
    expect(button).toHaveTextContent('the platform fee account has 500 Kudos')
  })

  it('pays in part what the account has (F-M1), and nothing from an empty account', async () => {
    const { processCalls } = server({ wallet: { ...WALLET, cashBalance: '300', feeCreditBalance: '200' },
      pay: { status: 200, body: { output: { invoiceId: 'i1', invoiceNo: 'QBF-2026-00001', status: 'PARTIAL',
        total: 1100, cashBalance: 0, paidAmount: 500, paidNow: 500 } } } })
    window.history.replaceState(null, '', '/money/bills')
    const user = userEvent.setup()
    render(<App />)
    const row = (await screen.findAllByTestId('invoice-row'))[0]
    const pay = within(row).getByTestId('invoice-row-pay')
    expect(pay).toBeEnabled()
    expect(pay).toHaveAttribute('data-covered', 'false')
    expect(pay).toHaveTextContent('Pay 500 Kudos now (in part)')
    await user.click(pay)
    expect(await screen.findByTestId('invoice-notice'))
      .toHaveTextContent('Paid 500 Kudos of invoice QBF-2026-00001; 600 Kudos still to pay.')
    expect(processCalls()[0]).toEqual({ path: '/api/processes/QB_FEE_INVOICE_PAY/latest', body: { invoiceId: 'i1' } })
  })

  it('cannot pay an invoice from an empty account', async () => {
    server({ wallet: { ...WALLET, cashBalance: '0' } })
    window.history.replaceState(null, '', '/money/bills')
    render(<App />)
    const row = (await screen.findAllByTestId('invoice-row'))[0]
    expect(within(row).getByTestId('invoice-row-pay')).toBeDisabled()
    expect(within(row).getByTestId('invoice-row-pay')).toHaveTextContent('the platform fee account has 0 Kudos')
  })

  it('shows a partly paid invoice with what is paid of it', async () => {
    server({ invoices: [{ ...INVOICE, status: 'PARTIAL', paidAmount: '500' }] })
    window.history.replaceState(null, '', '/money/bills')
    render(<App />)
    const row = (await screen.findAllByTestId('invoice-row'))[0]
    expect(row).toHaveAttribute('data-status', 'PARTIAL')
    expect(within(row).getByTestId('invoice-row-paid')).toHaveTextContent('500 Kudos')
  })
})

describe('what an invoice was paid with (F-M13)', () => {
  it('opens under the row, with the prepayment\'s granted credit named and what is still owed', async () => {
    server({ invoices: [{ ...INVOICE, status: 'PARTIAL', paidAmount: '500', issuedOn: '2026-01-02' }] })
    window.history.replaceState(null, '', '/money/bills')
    render(<App />)
    const row = (await screen.findAllByTestId('invoice-row'))[0]
    await userEvent.click(within(row).getByRole('button', { name: /expand row/i }))
    const sources = await screen.findByTestId('invoice-sources')
    expect(sources).toHaveTextContent('Granted credit')
    expect(sources).toHaveTextContent('200 Kudos')
    expect(sources).toHaveTextContent('Prepayment')
    expect(sources).toHaveTextContent('of which granted credit: 100 Kudos')
    expect(sources).toHaveTextContent('Still owed')
    expect(sources).toHaveTextContent('600 Kudos')
    await expectAccessible()
  })

  it('says when a voided invoice was voided, and keeps its original to download once it was issued', async () => {
    server({ invoices: [
      { ...INVOICE, invoiceId: 'v1', invoiceNo: 'QBF-2026-00009', status: 'VOID', voidedAt: '2026-02-03T05:00:00Z',
        documentRunId: 'd1' },
      { ...INVOICE, invoiceId: 'v2', invoiceNo: 'QBF-2026-00010', status: 'VOID', voidedAt: '2026-02-03T05:00:00Z',
        documentRunId: null }] })
    window.history.replaceState(null, '', '/money/bills')
    render(<App />)
    const rows = await screen.findAllByTestId('invoice-row')
    expect(within(rows[0]).getByTestId('invoice-row-voided-on')).toHaveTextContent(/Voided on/)
    expect(within(rows[0]).getByTestId('invoice-row-document')).toBeInTheDocument()
    // None was issued before: none is offered (the server refuses a new one).
    expect(within(rows[1]).queryByTestId('invoice-row-document')).toBeNull()
  })
})

describe('credit notes (F-M7)', () => {
  it('shows what credit notes paid of an invoice, and what is still owed on the button', async () => {
    server({ wallet: { ...WALLET, cashBalance: '300', feeCreditBalance: '0' },
      invoices: [{ ...INVOICE, status: 'PARTIAL', paidAmount: '200', creditApplied: '700' }] })
    window.history.replaceState(null, '', '/money/bills')
    render(<App />)
    const row = (await screen.findAllByTestId('invoice-row'))[0]
    expect(within(row).getByTestId('invoice-row-credit-applied')).toHaveTextContent('Paid by credit notes: 700 Kudos')
    // 1,100 less 700 by credit notes and 200 paid leaves 200: the 300 in the account covers it.
    expect(within(row).getByTestId('invoice-row-pay')).toHaveAttribute('data-covered', 'true')
  })

  it('lists the credit notes and downloads a credit note document as kept', async () => {
    const { processCalls } = server({ creditNotes: [CREDIT_NOTE] })
    URL.createObjectURL = vi.fn(() => 'blob:credit-note')
    URL.revokeObjectURL = vi.fn()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    window.history.replaceState(null, '', '/money/bills')
    const user = userEvent.setup()
    render(<App />)
    const section = await screen.findByTestId('wallet-credit-notes')
    const row = (await within(section).findAllByTestId('credit-note-row'))[0]
    expect(row).toHaveAttribute('data-reason', 'AUDIENCE_UNUSED')
    expect(within(row).getByTestId('credit-note-number')).toHaveTextContent('QBC-2026-00001')
    expect(within(row).getByTestId('credit-note-total')).toHaveTextContent('440 Kudos')
    expect(within(row).getByTestId('credit-note-given-back')).toHaveTextContent(
      'Cash 100 Kudos, credit 340 Kudos · of which 40 Kudos voided, past its date')
    expect(within(row).getByTestId('credit-note-left')).toHaveTextContent('340 Kudos')
    expect(row).toHaveTextContent('Audience fee of unused days')
    expect(section.textContent).not.toMatch(/JPY|yen|円/)
    await user.click(within(row).getByTestId('credit-note-document'))
    await waitFor(() => expect(click).toHaveBeenCalled())
    expect(processCalls()[0]).toEqual({ path: '/api/processes/QB_FEE_CREDIT_NOTE_DOCUMENT/latest',
      body: { creditNoteId: 'cn1', language: 'en' } })
    expect(click.mock.contexts[0]).toHaveProperty('download', 'QBC-2026-00001.pdf')
    await expectAccessible(screen.getByTestId('bills'))
  })

  it('shows the credit of credit notes on the fee account only while there is some', async () => {
    server({ wallet: { ...WALLET, creditNoteCredit: '340' } })
    window.history.replaceState(null, '', '/money/account')
    const view = render(<App />)
    expect(await screen.findByTestId('fee-account-credit-note')).toHaveTextContent('340 Kudos')
    view.unmount()
    vi.restoreAllMocks()
    server()
    render(<App />)
    await screen.findByTestId('fee-account-total')
    expect(screen.queryByTestId('fee-account-credit-note')).toBeNull()
  })
})

describe('the platform fee account (F-M1)', () => {
  it('shows the total, the granted credit and the cash, the lots in their order and how fees were paid', async () => {
    server({ wallet: { ...WALLET, feeCreditBalance: '900', cashBalance: '700' },
      lots: [
        { seq: 1, subAccount: 'FEE_CREDIT', lotId: 'g1', source: 'MANUAL', amount: '900', remaining: '900',
          arrivedAt: '2026-11-01T00:00:00Z', expiresAt: '2027-01-30T00:00:00Z' },
        { seq: 2, subAccount: 'CASH', lotId: 'c1', source: 'CARD', amount: '1000', remaining: '700',
          arrivedAt: '2026-11-02T00:00:00Z', expiresAt: null }],
      payments: [
        { paymentId: 'p1', paidAt: '2026-11-02T01:07:00Z', invoiceNo: 'QBF-2026-00001', subAccount: 'FEE_CREDIT',
          source: 'MANUAL', amount: '800' },
        { paymentId: 'p2', paidAt: '2026-11-02T01:07:00Z', invoiceNo: 'QBF-2026-00001', subAccount: 'CASH',
          source: 'CARD', amount: '300' }] })
    window.history.replaceState(null, '', '/money/account')
    render(<App />)
    const block = await screen.findByTestId('wallet-fee-account')
    expect(within(block).getByTestId('fee-account-total')).toHaveTextContent('1,600 Kudos')
    expect(within(block).getByTestId('fee-account-credit')).toHaveTextContent('900 Kudos')
    const lots = await within(block).findAllByTestId('fee-lot-row')
    expect(lots.map((row) => row.dataset.subAccount)).toEqual(['FEE_CREDIT', 'CASH'])
    expect(lots[1]).toHaveTextContent('700 Kudos')
    // F-M2: what of each lot was spent.
    expect(within(lots[1]).getByTestId('fee-lot-used')).toHaveTextContent('300 Kudos')
    expect(within(lots[0]).getByTestId('fee-lot-used')).toHaveTextContent('0 Kudos')
    expect(await within(block).findAllByTestId('fee-payment-row')).toHaveLength(2)
    expect(block.textContent).not.toMatch(/JPY|yen|円/)
    await expectAccessible(block)
  })
})
