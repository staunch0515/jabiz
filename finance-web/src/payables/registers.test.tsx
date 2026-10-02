import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BillRegisterRow } from './api'
import BillListPage from './BillListPage'
import PaymentListPage from './PaymentListPage'
import RunListPage from './RunListPage'
import { renderAt, setUpTexts } from '../journal/testing'

const calls = vi.hoisted(() => ({ bills: vi.fn(), runs: vi.fn(), payments: vi.fn(), permissions: new Set<string>() }))

vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  loadBillRegister: calls.bills,
  loadRunRegister: calls.runs,
  loadPaymentRegister: calls.payments,
}))

vi.mock('@jabiz/admin', async (original) => ({
  ...(await original<typeof import('@jabiz/admin')>()),
  useAuth: () => ({ can: (p: string) => calls.permissions.has(p) }),
}))

const BILLS: BillRegisterRow[] = [
  { billId: 'a', billNo: 'BILL-4', kind: 'BILL', vendorCode: 'V200', vendorInvoiceNo: 'DC-2026-01',
    invoiceDate: '2026-01-20', dueDate: '2026-02-19', total: '7500.00', openAmount: '7500.00', status: 'POSTED',
    approval: 'NOT_REQUIRED' },
  { billId: 'b', billNo: 'BILL-2', kind: 'BILL', vendorCode: 'V100', vendorInvoiceNo: 'P-7902',
    invoiceDate: '2026-01-09', total: 22000, openAmount: 22000, status: 'POSTED', approval: 'PENDING' },
  { billId: 'c', billNo: 'VC-1', kind: 'CREDIT', vendorCode: 'V400', vendorInvoiceNo: 'CR-1',
    invoiceDate: '2026-01-25', total: '-500.00', openAmount: '-500.00', status: 'POSTED' },
]

beforeAll(setUpTexts)

beforeEach(() => {
  calls.bills.mockReset().mockResolvedValue({ items: BILLS, offset: 0, limit: 500, total: 3 })
  calls.runs.mockReset().mockResolvedValue({ items: [
    { runId: 'r1', runNo: 'PAY-RUN-01', paymentDate: '2026-01-08', method: 'ACH', bankCode: 'OPERATING',
      status: 'RELEASED', total: '32300.00', lineCount: 2 },
    { runId: 'r2', runNo: 'PAY-RUN-05', paymentDate: '2026-01-30', method: 'ACH', bankCode: 'OPERATING',
      status: 'CANCELLED', total: '7500.00', lineCount: 1 },
  ], offset: 0, limit: 500, total: 2 })
  calls.payments.mockReset().mockResolvedValue({ items: [
    { paymentId: 'p1', paymentNo: 'PMT-0001', paymentDate: '2026-01-08', runId: 'r1', runNo: 'PAY-RUN-01',
      kind: 'BILL', vendorCode: 'V100', payee: 'Precision Parts Co.', method: 'ACH', amount: '28300.00',
      status: 'POSTED' },
    { paymentId: 'p2', paymentNo: 'PMT-0005', paymentDate: '2026-02-05', runId: 'r3', runNo: 'PAY-RUN-04',
      kind: 'BILL', vendorCode: 'V400', payee: 'CloudStack, Inc.', method: 'CHECK', checkNo: '10001',
      amount: '1200.00', status: 'VOID', voidDate: '2026-02-10' },
  ], offset: 0, limit: 500, total: 2 })
  calls.permissions = new Set(['fin.ap.read'])
})

describe('the payables registers', () => {
  it('total the bills, credits less, show what waits for approval and open each document', async () => {
    renderAt('/payables/bills', [{ path: '/payables/bills', element: <BillListPage /> }])
    expect(await screen.findByRole('link', { name: 'BILL-4' })).toHaveAttribute('href', '/payables/bills/a')
    expect(screen.getByTestId('register-total')).toHaveTextContent('29,000.00')
    expect(screen.getByTestId('register-open')).toHaveTextContent('29,000.00')
    expect(screen.getByText('Waiting for approval')).toBeInTheDocument()
    expect(screen.queryByTestId('new-bill')).not.toBeInTheDocument()
    await userEvent.type(screen.getByPlaceholderText('Any vendor'), 'v200')
    await waitFor(() => expect(calls.bills).toHaveBeenLastCalledWith(expect.objectContaining({ vendorCode: 'V200' })))
  })

  it('total the runs not cancelled and open each', async () => {
    calls.permissions.add('fin.payment.prepare')
    renderAt('/payables/runs', [{ path: '/payables/runs', element: <RunListPage /> }])
    expect(await screen.findByRole('link', { name: 'PAY-RUN-01' })).toHaveAttribute('href', '/payables/runs/r1')
    expect(screen.getByTestId('register-total')).toHaveTextContent('32,300.00')
    expect(screen.getByTestId('new-run')).toBeInTheDocument()
  })

  it('total the payments not voided and show the voided ones as such', async () => {
    renderAt('/payables/payments', [{ path: '/payables/payments', element: <PaymentListPage /> }])
    expect(await screen.findByRole('link', { name: 'PAY-RUN-04' })).toHaveAttribute('href', '/payables/runs/r3')
    expect(screen.getByTestId('register-total')).toHaveTextContent('28,300.00')
    expect(screen.getByText(/^Void /)).toBeInTheDocument()
  })
})
