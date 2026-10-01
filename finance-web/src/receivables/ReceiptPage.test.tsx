import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LoadedReceipt, Suggestion } from './api'
import ReceiptRoute from './ReceiptRoute'
import { renderAt, setUpTexts } from '../journal/testing'

const calls = vi.hoisted(() => ({
  recordReceipt: vi.fn(),
  loadReceipt: vi.fn(),
  loadSuggestions: vi.fn(),
  runProcess: vi.fn(),
  permissions: new Set<string>(),
}))

vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  loadCustomers: async () => [
    { customerCode: 'C300', legalName: 'Lone Star Distributors, Inc.', currency: 'USD', termsCode: 'NET30',
      taxCode: 'TX-RESALE', status: 'ACTIVE' },
  ],
  loadReceipt: calls.loadReceipt,
  loadSuggestions: calls.loadSuggestions,
  loadInvoiceNumbers: async () => new Map([['inv-1003', 'INV-1003'], ['inv-1007', 'INV-1007']]),
  recordReceipt: calls.recordReceipt,
}))

vi.mock('../journal/api', async (original) => ({
  ...(await original<typeof import('../journal/api')>()),
  loadAccounts: async () => new Map([
    ['1010', { accountCode: '1010', accountName: 'Cash - Operating', active: true, summary: false, controlClass: 'BANK' }],
    ['1200', { accountCode: '1200', accountName: 'Accounts Receivable', active: true, summary: false, controlClass: 'AR' }],
  ]),
}))

vi.mock('@jabiz/admin', async (original) => ({
  ...(await original<typeof import('@jabiz/admin')>()),
  runProcess: calls.runProcess,
  useAuth: () => ({ can: (p: string) => calls.permissions.has(p) }),
}))

const ROUTES = [
  { path: '/receivables/receipts/new', element: <ReceiptRoute /> },
  { path: '/receivables/receipts/:receiptId', element: <ReceiptRoute /> },
]

const OPEN: Suggestion[] = [
  { rank: 1, matched: 'REFERENCE', invoiceId: 'inv-1003', invoiceNo: 'INV-1003', invoiceDate: '2025-12-15',
    dueDate: '2026-01-14', openAmount: '30000.00' },
  { rank: 3, matched: 'OLDEST', invoiceId: 'inv-1007', invoiceNo: 'INV-1007', invoiceDate: '2026-01-15',
    dueDate: '2026-02-14', openAmount: '25000.00' },
]

beforeAll(setUpTexts)

beforeEach(() => {
  for (const fn of [calls.recordReceipt, calls.loadReceipt, calls.loadSuggestions, calls.runProcess]) fn.mockReset()
  calls.loadSuggestions.mockResolvedValue(OPEN)
  calls.permissions = new Set(['fin.ar.read', 'fin.receipt.record'])
})

describe('ReceiptPage', () => {
  it('records a partial receipt applied as suggested, by keyboard, to the invoice its reference names', async () => {
    calls.recordReceipt.mockResolvedValue({ receiptId: 'r-3', receiptNo: 'RCPT-0003', status: 'POSTED',
      amount: '20000.00', unappliedAmount: '0.00' })
    renderAt('/receivables/receipts/new', ROUTES)
    const customer = await screen.findByTestId('receipt-customer')
    expect(customer).toHaveFocus()
    await userEvent.type(customer, 'c300')
    await userEvent.type(screen.getByTestId('receipt-date'), '2026-01-25')
    await userEvent.type(screen.getByTestId('receipt-amount'), '20,000')
    await waitFor(() => expect(calls.loadSuggestions).toHaveBeenLastCalledWith({ customerCode: 'C300',
      onDate: '2026-01-25', amount: '20000', reference: '' }))
    await userEvent.click(await screen.findByTestId('apply-suggested'))
    expect(screen.getByTestId('apply-INV-1003')).toHaveValue('20000.00')
    expect(screen.getByTestId('apply-INV-1007')).toHaveValue('')
    expect(screen.getByTestId('left-unapplied')).toHaveTextContent('0.00')
    await userEvent.keyboard('{Control>}{Enter}{/Control}')
    await waitFor(() => expect(calls.recordReceipt).toHaveBeenCalledTimes(1))
    expect(calls.recordReceipt.mock.calls[0][0]).toEqual({ customerCode: 'C300', receiptDate: '2026-01-25',
      amount: '20000', method: 'CHECK', reference: null, bankAccount: '1010', description: null,
      applications: [{ invoiceId: 'inv-1003', amount: '20000.00', discount: null }] })
  })

  it('does not record more applied than was received', async () => {
    renderAt('/receivables/receipts/new', ROUTES)
    await userEvent.type(await screen.findByTestId('receipt-customer'), 'C300')
    await userEvent.type(screen.getByTestId('receipt-date'), '2026-01-25')
    await userEvent.type(screen.getByTestId('receipt-amount'), '100')
    await userEvent.type(await screen.findByTestId('apply-INV-1003'), '100.01')
    expect(screen.getByTestId('over-applied')).toHaveTextContent('0.01')
    expect(screen.getByTestId('receipt-record')).toBeDisabled()
  })

  it('shows an existing receipt and lets the controller take an application back', async () => {
    calls.permissions.add('fin.receipt.adjust')
    const loaded: LoadedReceipt = {
      receipt: { receiptId: 'r-3', receiptNo: 'RCPT-0003', customerCode: 'C300', receiptDate: '2026-01-25',
        amount: '20000.00', currency: 'USD', method: 'ACH', bankAccount: '1010', unappliedAmount: '0.00',
        status: 'POSTED' },
      applications: [{ applicationId: 'a-1', sourceKind: 'RECEIPT', sourceId: 'r-3', invoiceId: 'inv-1007',
        customerCode: 'C300', applicationDate: '2026-01-25', amount: '20000.00', amountUsd: '20000.00' }],
    }
    calls.loadReceipt.mockResolvedValue(loaded)
    calls.runProcess.mockResolvedValue({})
    renderAt('/receivables/receipts/r-3', ROUTES)
    expect(await screen.findByTestId('page-title')).toHaveTextContent('Receipt RCPT-0003')
    expect(await screen.findByText('INV-1007')).toBeInTheDocument()
    await userEvent.click(screen.getByTestId('reverse-a-1'))
    await userEvent.type(screen.getByLabelText('Date'), '2026-01-25')
    await userEvent.type(screen.getByLabelText('Reason'), 'Wrong invoice')
    await userEvent.click(screen.getByTestId('reverse-confirm'))
    await waitFor(() => expect(calls.runProcess).toHaveBeenCalledWith('FIN_APPLICATION_REVERSE', {
      applicationId: 'a-1', reverseDate: '2026-01-25', reason: 'Wrong invoice' }))
  })

  it('does not count or send what was typed for an invoice no longer listed', async () => {
    renderAt('/receivables/receipts/new', ROUTES)
    await userEvent.type(await screen.findByTestId('receipt-customer'), 'C300')
    await userEvent.type(screen.getByTestId('receipt-date'), '2026-01-25')
    await userEvent.type(screen.getByTestId('receipt-amount'), '100')
    await userEvent.type(await screen.findByTestId('apply-INV-1007'), '100')
    expect(screen.getByTestId('applied-total')).toHaveTextContent('100.00')
    // On an earlier day INV-1007 is not open yet.
    calls.loadSuggestions.mockResolvedValue([OPEN[0]])
    const date = screen.getByTestId('receipt-date')
    await userEvent.clear(date)
    await userEvent.type(date, '2026-01-10')
    await waitFor(() => expect(screen.queryByTestId('apply-INV-1007')).not.toBeInTheDocument())
    expect(screen.getByTestId('applied-total')).toHaveTextContent('0.00')
    calls.recordReceipt.mockResolvedValue({ receiptId: 'r-4', receiptNo: 'RCPT-0004', status: 'POSTED',
      amount: '100.00', unappliedAmount: '100.00' })
    await userEvent.click(screen.getByTestId('receipt-record'))
    await waitFor(() => expect(calls.recordReceipt).toHaveBeenCalled())
    expect(calls.recordReceipt.mock.calls[0][0].applications).toEqual([])
  })
})
