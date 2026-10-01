import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { InvoiceRegisterRow } from './api'
import InvoiceListPage from './InvoiceListPage'
import ReceiptListPage from './ReceiptListPage'
import { renderAt, setUpTexts } from '../journal/testing'

const calls = vi.hoisted(() => ({ invoices: vi.fn(), receipts: vi.fn(), permissions: new Set<string>() }))

vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  loadInvoiceRegister: calls.invoices,
  loadReceiptRegister: calls.receipts,
}))

vi.mock('@jabiz/admin', async (original) => ({
  ...(await original<typeof import('@jabiz/admin')>()),
  useAuth: () => ({ can: (p: string) => calls.permissions.has(p) }),
}))

const ROWS: InvoiceRegisterRow[] = [
  { invoiceId: 'a', invoiceNo: 'INV-1004', kind: 'INVOICE', invoiceDate: '2026-01-06', customerCode: 'C100',
    currency: 'USD', total: '53300.00', totalUsd: '53300.00', openAmountUsd: '51135.00', status: 'POSTED' },
  { invoiceId: 'b', invoiceNo: 'CM-2001', kind: 'CREDIT_MEMO', invoiceDate: '2026-01-10', customerCode: 'C100',
    currency: 'USD', total: '2165.00', totalUsd: '-2165.00', openAmountUsd: '0.00', status: 'POSTED' },
  { invoiceId: 'c', invoiceNo: 'INV-1005', kind: 'INVOICE', invoiceDate: '2026-01-12', customerCode: 'C400',
    currency: 'EUR', total: '50000.00', totalUsd: 54250, openAmountUsd: 54250, status: 'POSTED' },
]

beforeAll(setUpTexts)

beforeEach(() => {
  calls.invoices.mockReset().mockResolvedValue({ items: ROWS, offset: 0, limit: 500, total: 3 })
  calls.receipts.mockReset().mockResolvedValue({ items: [
    { receiptId: 'r1', receiptNo: 'RCPT-0001', receiptDate: '2026-01-05', customerCode: 'C100', method: 'ACH',
      bankAccount: '1010', currency: 'USD', amount: '32475.00', unappliedAmount: '0.00', status: 'POSTED' },
    { receiptId: 'r2', receiptNo: 'RCPT-0002', receiptDate: '2026-01-16', customerCode: 'C200', method: 'CHECK',
      bankAccount: '1010', currency: 'USD', amount: 24025, unappliedAmount: 25.5, status: 'POSTED' },
  ], offset: 0, limit: 500, total: 2 })
  calls.permissions = new Set(['fin.ar.read'])
})

describe('the receivables registers', () => {
  it('total the invoices in dollars, credit memos less, and open each document', async () => {
    renderAt('/receivables/invoices', [{ path: '/receivables/invoices', element: <InvoiceListPage /> }])
    expect(await screen.findByRole('link', { name: 'INV-1004' })).toHaveAttribute('href', '/receivables/invoices/a')
    const year = new Date().getFullYear()
    expect(calls.invoices).toHaveBeenCalledWith({ from: `${year}-01-01`, to: `${year}-12-31`, status: null,
      kind: null, customerCode: '' })
    expect(screen.getByTestId('register-total')).toHaveTextContent('105,385.00')
    expect(screen.getByTestId('register-open')).toHaveTextContent('105,385.00')
    expect(screen.queryByTestId('new-invoice')).not.toBeInTheDocument()
    await userEvent.type(screen.getByPlaceholderText('Any customer'), 'c100')
    await waitFor(() => expect(calls.invoices).toHaveBeenLastCalledWith(expect.objectContaining({ customerCode: 'C100' })))
  })

  it('total the receipts and what is still unapplied', async () => {
    calls.permissions.add('fin.receipt.record')
    renderAt('/receivables/receipts', [{ path: '/receivables/receipts', element: <ReceiptListPage /> }])
    expect(await screen.findByRole('link', { name: 'RCPT-0002' })).toHaveAttribute('href', '/receivables/receipts/r2')
    expect(screen.getByTestId('register-total')).toHaveTextContent('56,500.00')
    expect(screen.getByTestId('register-unapplied')).toHaveTextContent('25.50')
    expect(screen.getByTestId('new-receipt')).toBeInTheDocument()
  })
})
