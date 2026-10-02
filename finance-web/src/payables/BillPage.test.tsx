import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@jabiz/admin'
import type { LoadedBill } from './api'
import BillRoute from './BillRoute'
import { renderAt, setUpTexts } from '../journal/testing'

const calls = vi.hoisted(() => ({
  saveBill: vi.fn(),
  postBill: vi.fn(),
  loadBill: vi.fn(),
  runProcess: vi.fn(),
  permissions: new Set<string>(),
}))

vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  loadVendors: async () => [
    { vendorCode: 'V200', legalName: 'Delta Consulting LLC', currency: 'USD', termsCode: 'NET30',
      expenseAccount: '6400', form1099: 'NEC', box1099: '1', status: 'ACTIVE' },
  ],
  loadTaxCodes: async () => [{ taxCode: 'TX-AUSTIN', description: 'Austin', kind: 'TAXABLE', active: true }],
  loadBill: calls.loadBill,
  saveBill: calls.saveBill,
  postBill: calls.postBill,
}))

vi.mock('../journal/api', async (original) => ({
  ...(await original<typeof import('../journal/api')>()),
  loadAccounts: async () => new Map([
    ['6400', { accountCode: '6400', accountName: 'Professional Fees', active: true, summary: false }],
    ['6500', { accountCode: '6500', accountName: 'Software', active: true, summary: false }],
  ]),
  loadDimensions: async () => ({ departments: new Set(['ADMIN', 'SALES']), locations: new Set<string>() }),
}))

vi.mock('@jabiz/admin', async (original) => ({
  ...(await original<typeof import('@jabiz/admin')>()),
  runProcess: calls.runProcess,
  useAuth: () => ({ can: (p: string) => calls.permissions.has(p) }),
  ApprovalPanel: ({ requestId }: { requestId: string }) => <div data-testid="approval-panel">{requestId}</div>,
}))

const ROUTES = [
  { path: '/payables/bills/new', element: <BillRoute /> },
  { path: '/payables/bills/:billId', element: <BillRoute /> },
]

const POSTED: LoadedBill = {
  bill: {
    billId: 'b-1', kind: 'BILL', billNo: 'BILL-4', vendorCode: 'V200', vendorInvoiceNo: 'DC-2026-01',
    invoiceDate: '2026-01-20', dueDate: '2026-02-19', currency: 'USD', termsCode: 'NET30', source: 'MANUAL',
    status: 'POSTED', approval: 'PENDING', approvalRequestId: 'r-1', subtotal: '1000.00', useTaxTotal: '82.50',
    total: '1000.00', openAmount: '1000.00', glNo: 'GJ-AP-2026-000004', preparedBy: 'clerk',
  },
  version: 2,
  lines: [{ lineNo: 1, description: 'Office chairs', amount: '1000.00', account: '6400', useTaxCode: 'TX-AUSTIN',
    form1099: 'NEC', box1099: '1' }],
  taxes: [
    { taxId: 't1', lineNo: 1, taxCode: 'TX-AUSTIN', jurisdiction: 'TX', base: '1000.00', ratePercent: '6.2500',
      rateFrom: '2025-01-01', tax: '62.50' },
    { taxId: 't2', lineNo: 1, taxCode: 'TX-AUSTIN', jurisdiction: 'TX-AUSTIN-LOCAL', base: '1000.00',
      ratePercent: '2.0000', rateFrom: '2025-01-01', tax: '20.00' },
  ],
  applications: [{ applicationId: 'a1', sourceKind: 'PAYMENT', sourceId: 'p-1', sourceNo: 'PMT-0003', billId: 'b-1',
    vendorCode: 'V200', applicationDate: '2026-01-22', amount: '400.00' }],
}

const cell = (column: string, line: number) => screen.getByLabelText(`${column}, line ${line}`) as HTMLInputElement

beforeAll(setUpTexts)

beforeEach(() => {
  for (const fn of [calls.saveBill, calls.postBill, calls.loadBill, calls.runProcess]) fn.mockReset()
  calls.permissions = new Set(['fin.ap.read', 'fin.bill.prepare'])
})

describe('BillPage', () => {
  it('takes a five-line bill from the keyboard alone and posts it with Ctrl+Enter', async () => {
    calls.saveBill.mockResolvedValue({ billId: 'b-9', kind: 'BILL', status: 'DRAFT' })
    calls.loadBill.mockResolvedValue({ ...POSTED, bill: { ...POSTED.bill, billId: 'b-9', billNo: null,
      status: 'DRAFT', approval: null }, taxes: [], applications: [] })
    calls.postBill.mockResolvedValue({ billId: 'b-9', billNo: 'BILL-9', kind: 'BILL', status: 'POSTED',
      approval: 'NOT_REQUIRED' })
    renderAt('/payables/bills/new', ROUTES)
    const vendor = await screen.findByTestId('bill-vendor')
    expect(vendor).toHaveFocus()
    await userEvent.type(vendor, 'v200')
    await waitFor(() => expect(screen.getByTestId('bill-vendor-name')).toHaveTextContent('Delta Consulting LLC'))
    await userEvent.tab()
    expect(screen.getByTestId('bill-number')).toHaveFocus()
    await userEvent.keyboard('DC-2026-02')
    await userEvent.type(screen.getByLabelText('Invoice date'), '2026-01-25')
    await userEvent.click(cell('Description', 1))
    // Five lines, each by Tab, ending with Enter on the box; amounts without separators.
    await userEvent.keyboard('Strategy workshop{Tab}1500{Tab}6400{Tab}{Tab}admin{Tab}nec{Tab}1{Enter}')
    expect(cell('Description', 2)).toHaveFocus()
    await userEvent.keyboard('Report{Tab}2500.5{Tab}{Tab}{Tab}{Tab}{Tab}{Enter}')
    await userEvent.keyboard('Software{Tab}300{Tab}6500{Tab}tx-austin{Tab}{Tab}{Tab}{Enter}')
    await userEvent.keyboard('Travel{Tab}120{Tab}6400{Tab}{Tab}sales{Tab}{Tab}{Enter}')
    await userEvent.keyboard('Expenses{Tab}79.5{Tab}6400{Tab}{Tab}{Tab}{Tab}{Enter}')
    expect(cell('Description', 6)).toHaveFocus()
    expect(screen.getByTestId('lines-total')).toHaveTextContent('4,500.00')
    expect(cell('Amount', 1)).toHaveValue('1,500.00')
    await userEvent.keyboard('{Control>}{Enter}{/Control}')
    await waitFor(() => expect(calls.postBill).toHaveBeenCalledWith('b-9', expect.any(String)))
    const input = calls.saveBill.mock.calls[0][0]
    expect(input).toMatchObject({ kind: 'BILL', vendorCode: 'V200', vendorInvoiceNo: 'DC-2026-02',
      invoiceDate: '2026-01-25', duplicateReason: null })
    expect(input.lines).toHaveLength(5)
    expect(input.lines[0]).toEqual({ description: 'Strategy workshop', amount: '1500.00', account: '6400',
      useTaxCode: null, department: 'ADMIN', form1099: 'NEC', box1099: '1', location: null })
    expect(input.lines[1]).toMatchObject({ amount: '2500.50', account: null })
    expect(input.lines[2]).toMatchObject({ useTaxCode: 'TX-AUSTIN' })
  }, 20_000)

  it('shows refusals on their cells, and asks why a bill like another is no duplicate', async () => {
    calls.saveBill.mockRejectedValueOnce(new ApiError(422, {
      title: 'Refused',
      violations: [
        { field: 'lines[0].account', ruleCode: 'FIN_BILL_ACCOUNT', message: 'Not an expense account.' },
        { field: 'vendorInvoiceNo', ruleCode: 'FIN_BILL_POSSIBLE_DUPLICATE', message: 'Like BILL-3 of V200.' },
      ],
    })).mockResolvedValueOnce({ billId: 'b-9', kind: 'BILL', status: 'DRAFT' })
    calls.loadBill.mockResolvedValue({ ...POSTED, bill: { ...POSTED.bill, billId: 'b-9', billNo: null,
      status: 'DRAFT' } })
    renderAt('/payables/bills/new', ROUTES)
    await userEvent.type(await screen.findByTestId('bill-vendor'), 'V200')
    await userEvent.type(cell('Description', 1), 'Fees')
    await userEvent.type(cell('Amount', 1), '10')
    await userEvent.click(screen.getByTestId('bill-save'))
    expect(await screen.findByTestId('bill-problems')).toHaveTextContent('Like BILL-3 of V200.')
    expect(cell('Account', 1)).toHaveAttribute('title', 'Not an expense account.')
    await userEvent.type(screen.getByTestId('bill-duplicate-reason'), 'Second delivery')
    await userEvent.click(screen.getByTestId('bill-save'))
    await waitFor(() => expect(calls.saveBill).toHaveBeenCalledTimes(2))
    expect(calls.saveBill.mock.calls[1][0].duplicateReason).toBe('Second delivery')
  })

  it('checks a line while typing and will not post a bill it can see is wrong', async () => {
    renderAt('/payables/bills/new', ROUTES)
    await userEvent.type(await screen.findByTestId('bill-vendor'), 'V200')
    await userEvent.type(cell('Description', 1), 'Fees')
    await userEvent.type(cell('Amount', 1), '10')
    await userEvent.type(cell('1099 form', 1), 'NEC')
    await userEvent.type(cell('Box', 1), '2')
    expect(cell('Box', 1)).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByTestId('bill-post')).toBeDisabled()
  })

  it('explains a posted bill: its use tax by jurisdiction, what paid it, and the approval it waits for', async () => {
    calls.permissions = new Set(['fin.ap.read', 'approval.decide', 'fin.bill.void'])
    calls.loadBill.mockResolvedValue(POSTED)
    renderAt('/payables/bills/b-1', ROUTES)
    expect(await screen.findByTestId('bill-view')).toBeInTheDocument()
    expect(screen.getByTestId('page-title')).toHaveTextContent('Bill BILL-4')
    expect(screen.getByTestId('bill-approval')).toHaveTextContent('Waiting for approval')
    expect(screen.getByTestId('approval-panel')).toHaveTextContent('r-1')
    expect(screen.getByTestId('bill-use-tax')).toHaveTextContent('82.50')
    const tax = screen.getByTestId('use-tax')
    expect(tax).toHaveTextContent('6.25%')
    expect(tax).toHaveTextContent('2%')
    expect(tax).toHaveTextContent('62.50')
    expect(screen.getByTestId('bill-view-lines')).toHaveTextContent('1099-NEC box 1')
    expect(screen.getByTestId('bill-applications')).toHaveTextContent('Payment PMT-0003')
    expect(screen.getByTestId('bill-void')).toBeDisabled()
  })

  it('voids a posted bill on a day with why, for who may', async () => {
    calls.permissions = new Set(['fin.ap.read', 'fin.bill.void'])
    calls.loadBill.mockResolvedValue({ ...POSTED, bill: { ...POSTED.bill, approval: 'NOT_REQUIRED' } })
    calls.runProcess.mockResolvedValue({})
    renderAt('/payables/bills/b-1', ROUTES)
    await screen.findByTestId('bill-view')
    await userEvent.type(screen.getByLabelText('Void date'), '2026-01-31')
    await userEvent.type(screen.getByLabelText('Reason'), 'Billed twice')
    await userEvent.click(screen.getByTestId('bill-void'))
    await waitFor(() => expect(calls.runProcess).toHaveBeenCalledWith('FIN_BILL_VOID',
      { billId: 'b-1', voidDate: '2026-01-31', reason: 'Billed twice' }))
  })
})
