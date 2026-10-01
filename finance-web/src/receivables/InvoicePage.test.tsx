import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@jabiz/admin'
import type { LoadedInvoice } from './api'
import InvoiceRoute from './InvoiceRoute'
import { renderAt, setUpTexts } from '../journal/testing'

const calls = vi.hoisted(() => ({
  saveInvoice: vi.fn(),
  postInvoice: vi.fn(),
  loadInvoice: vi.fn(),
  runProcess: vi.fn(),
  documentPanel: vi.fn(),
  permissions: new Set<string>(),
}))

vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  loadCustomers: async () => [
    { customerCode: 'C100', legalName: 'Acme Robotics, Inc.', currency: 'USD', termsCode: 'NET30', taxCode: 'TX-AUSTIN',
      status: 'ACTIVE' },
  ],
  loadTaxCodes: async () => [
    { taxCode: 'NT', description: 'Non-taxable service', kind: 'NON_TAXABLE', active: true },
    { taxCode: 'TX-AUSTIN', description: 'Austin', kind: 'TAXABLE', active: true },
  ],
  loadInvoice: calls.loadInvoice,
  saveInvoice: calls.saveInvoice,
  postInvoice: calls.postInvoice,
}))

vi.mock('../journal/api', async (original) => ({
  ...(await original<typeof import('../journal/api')>()),
  loadAccounts: async () => new Map([
    ['4000', { accountCode: '4000', accountName: 'Product Sales', active: true, summary: false }],
    ['4100', { accountCode: '4100', accountName: 'Service Revenue', active: true, summary: false }],
  ]),
}))

vi.mock('@jabiz/admin', async (original) => ({
  ...(await original<typeof import('@jabiz/admin')>()),
  runProcess: calls.runProcess,
  useAuth: () => ({ can: (p: string) => calls.permissions.has(p) }),
  ApprovalPanel: ({ requestId }: { requestId: string }) => <div data-testid="approval-panel">{requestId}</div>,
  DocumentPanel: (props: Record<string, unknown>) => {
    calls.documentPanel(props)
    return <div data-testid="document-panel">{String(props.layoutId)}</div>
  },
}))

const ROUTES = [
  { path: '/receivables/invoices/new', element: <InvoiceRoute /> },
  { path: '/receivables/invoices/:invoiceId', element: <InvoiceRoute /> },
]

const POSTED: LoadedInvoice = {
  invoice: {
    invoiceId: 'i-1', kind: 'INVOICE', invoiceNo: 'INV-1004', customerCode: 'C100', invoiceDate: '2026-01-06',
    dueDate: '2026-02-05', currency: 'USD', termsCode: 'NET30', taxCode: 'TX-AUSTIN', source: 'MANUAL',
    status: 'POSTED', subtotal: '50000.00', taxTotal: '3300.00', total: '53300.00', totalUsd: '53300.00',
    openAmount: '51135.00', glNo: 'GJ-AR-2026-000001', preparedBy: 'clerk',
  },
  version: 3,
  lines: [
    { lineNo: 1, description: 'Components', quantity: '100.0000', unitPrice: '400.0000', amount: '40000.00',
      revenueAccount: '4000', taxCode: null },
    { lineNo: 2, description: 'Engineering services', quantity: 1, unitPrice: 10000, amount: 10000,
      revenueAccount: '4100', taxCode: 'NT' },
  ],
  taxes: [
    { taxId: 't1', jurisdiction: 'TX', base: '40000.00', ratePercent: '6.2500', rateFrom: '2025-01-01', tax: '2500.00' },
    { taxId: 't2', jurisdiction: 'TX-AUSTIN-LOCAL', base: '40000.00', ratePercent: '2.0000', rateFrom: '2025-01-01',
      tax: '800.00' },
    { taxId: 't3', lineNo: 1, taxCode: 'TX-AUSTIN', taxKind: 'TAXABLE', base: '40000.00', tax: '3300.00' },
    { taxId: 't4', lineNo: 2, taxCode: 'NT', taxKind: 'NON_TAXABLE', reason: 'NON_TAXABLE_SERVICE', base: '10000.00',
      tax: '0.00' },
  ],
  applications: [
    { applicationId: 'a1', sourceKind: 'CREDIT_MEMO', sourceId: 'cm-1', sourceNo: 'CM-2001', invoiceId: 'i-1',
      customerCode: 'C100', applicationDate: '2026-01-10', amount: '2165.00', amountUsd: '2165.00' },
  ],
}

const cell = (column: string, line: number) => screen.getByLabelText(`${column}, line ${line}`) as HTMLInputElement

beforeAll(setUpTexts)

beforeEach(() => {
  for (const fn of [calls.saveInvoice, calls.postInvoice, calls.loadInvoice, calls.runProcess, calls.documentPanel]) {
    fn.mockReset()
  }
  calls.permissions = new Set(['fin.ar.read', 'fin.invoice.prepare', 'fin.invoice.issue', 'document.archive.read'])
})

describe('InvoicePage', () => {
  it('takes a new invoice from the keyboard alone and saves it with Ctrl+S', async () => {
    calls.saveInvoice.mockResolvedValue({ invoiceId: 'i-9', kind: 'INVOICE', status: 'DRAFT' })
    calls.loadInvoice.mockResolvedValue({ ...POSTED, invoice: { ...POSTED.invoice, invoiceId: 'i-9', invoiceNo: null,
      status: 'DRAFT' } })
    renderAt('/receivables/invoices/new', ROUTES)
    const customer = await screen.findByTestId('invoice-customer')
    expect(customer).toHaveFocus()
    await userEvent.type(customer, 'c100')
    await waitFor(() => expect(screen.getByTestId('invoice-customer-name')).toHaveTextContent('Acme Robotics, Inc.'))
    await userEvent.type(screen.getByLabelText('Invoice date'), '2026-01-06')
    await userEvent.type(cell('Description', 1), 'Components')
    await userEvent.tab()
    await userEvent.keyboard('100')
    await userEvent.tab()
    await userEvent.keyboard('400.00')
    await userEvent.tab()
    await userEvent.keyboard('4000')
    await userEvent.tab()
    expect(cell('Tax code', 1)).toHaveFocus()
    // Enter on the last cell goes to the next line.
    await userEvent.keyboard('{Enter}')
    expect(cell('Description', 2)).toHaveFocus()
    await userEvent.keyboard('Engineering services{Tab}1{Tab}10000{Tab}4100{Tab}nt{Enter}')
    // A third line was added at the end; it stays blank and is not sent.
    expect(cell('Description', 3)).toHaveFocus()
    expect(screen.getByTestId('line-0-amount')).toHaveTextContent('40,000.00')
    expect(screen.getByTestId('lines-subtotal')).toHaveTextContent('50,000.00')
    await userEvent.keyboard('{Control>}s{/Control}')
    await waitFor(() => expect(calls.saveInvoice).toHaveBeenCalledTimes(1))
    expect(calls.saveInvoice.mock.calls[0][0]).toEqual({
      invoiceId: undefined, kind: 'INVOICE', customerCode: 'C100', invoiceDate: '2026-01-06', termsCode: null,
      taxCode: null, currency: null, reference: null, description: null, originalInvoiceId: null,
      lines: [
        { description: 'Components', quantity: '100', unitPrice: '400.00', revenueAccount: '4000', taxCode: null },
        { description: 'Engineering services', quantity: '1', unitPrice: '10000', revenueAccount: '4100', taxCode: 'NT' },
      ],
    })
  })

  it('shows the refusal of a line on its cells and the rest above the lines', async () => {
    calls.saveInvoice.mockRejectedValue(new ApiError(400, {
      title: 'Invalid',
      violations: [
        { field: 'lines[0].revenueAccount', ruleCode: 'FIN_INVOICE_NO_ACCOUNT', message: 'A revenue account is needed.' },
        { field: 'customerCode', ruleCode: 'FIN_INVOICE_UNKNOWN_CUSTOMER', message: 'There is no active customer C9.' },
      ],
    }))
    renderAt('/receivables/invoices/new', ROUTES)
    await userEvent.type(await screen.findByTestId('invoice-customer'), 'C9')
    await userEvent.type(cell('Description', 2), 'Parts')
    await userEvent.type(cell('Quantity', 2), '1')
    await userEvent.type(cell('Unit price', 2), '5')
    await userEvent.click(screen.getByTestId('invoice-save'))
    expect(await screen.findByTestId('invoice-problems')).toHaveTextContent('There is no active customer C9.')
    expect(cell('Revenue account', 2)).toHaveAttribute('title', 'A revenue account is needed.')
  })

  it('posts with Ctrl+Enter after saving what changed', async () => {
    calls.loadInvoice.mockResolvedValue({ ...POSTED, invoice: { ...POSTED.invoice, invoiceNo: null, status: 'DRAFT' } })
    calls.saveInvoice.mockResolvedValue({ invoiceId: 'i-1', kind: 'INVOICE', status: 'DRAFT' })
    calls.postInvoice.mockResolvedValue({ invoiceId: 'i-1', invoiceNo: 'INV-1004', kind: 'INVOICE', status: 'POSTED' })
    renderAt('/receivables/invoices/i-1', ROUTES)
    await waitFor(() => expect(cell('Description', 1).value).toBe('Components'))
    await userEvent.clear(cell('Quantity', 1))
    await userEvent.type(cell('Quantity', 1), '90{Control>}{Enter}{/Control}')
    await waitFor(() => expect(calls.postInvoice).toHaveBeenCalledWith('i-1', expect.any(String)))
    expect(calls.saveInvoice.mock.calls[0][0].lines[0].quantity).toBe('90')
  })

  it('explains a posted invoice: its tax by jurisdiction with the rate in effect, what was applied, its document', async () => {
    calls.loadInvoice.mockResolvedValue(POSTED)
    renderAt('/receivables/invoices/i-1', ROUTES)
    expect(await screen.findByTestId('invoice-view')).toBeInTheDocument()
    expect(screen.getByTestId('page-title')).toHaveTextContent('Invoice INV-1004')
    expect(screen.getByTestId('invoice-tax-total')).toHaveTextContent('3,300.00')
    const jurisdictions = screen.getByTestId('tax-jurisdictions')
    expect(jurisdictions).toHaveTextContent('6.25%')
    expect(jurisdictions).toHaveTextContent('2%')
    expect(jurisdictions).toHaveTextContent('2,500.00')
    expect(screen.getByTestId('tax-lines')).toHaveTextContent('Not taxable')
    expect(screen.getByTestId('invoice-applications')).toHaveTextContent('Credit memo CM-2001')
    expect(screen.getByTestId('invoice-view-lines')).toHaveTextContent('Components')
    expect(calls.documentPanel).toHaveBeenLastCalledWith(expect.objectContaining({
      layoutId: 'finance.ar.invoice', params: { invoiceId: 'i-1' }, subjectId: 'i-1',
      issue: { process: 'FIN_INVOICE_ISSUE', input: { invoiceId: 'i-1' } },
    }))
    expect(screen.getByTestId('new-credit-memo')).toBeInTheDocument()
  })

  it('lets a reader without the issuing permission find the issued copies instead', async () => {
    calls.permissions = new Set(['fin.ar.read', 'document.archive.read'])
    calls.loadInvoice.mockResolvedValue(POSTED)
    renderAt('/receivables/invoices/i-1', ROUTES)
    expect(await screen.findByTestId('issued-documents')).toHaveAttribute('href', '/documents?subject=i-1')
    expect(screen.queryByTestId('document-panel')).not.toBeInTheDocument()
    expect(screen.queryByTestId('new-credit-memo')).not.toBeInTheDocument()
  })

  it('starts a credit memo from the invoice it credits', async () => {
    calls.loadInvoice.mockResolvedValue(POSTED)
    calls.saveInvoice.mockResolvedValue({ invoiceId: 'cm-1', kind: 'CREDIT_MEMO', status: 'DRAFT' })
    renderAt('/receivables/invoices/new?credits=i-1', ROUTES)
    await waitFor(() => expect(cell('Description', 1).value).toBe('Components'))
    expect(screen.getByTestId('page-title')).toHaveTextContent('New credit memo')
    expect(screen.getByTestId('invoice-customer')).toHaveValue('C100')
    expect(screen.getByTestId('invoice-customer')).toBeDisabled()
    await userEvent.clear(cell('Quantity', 1))
    await userEvent.type(cell('Quantity', 1), '5')
    await userEvent.click(screen.getByTestId('invoice-save'))
    await waitFor(() => expect(calls.saveInvoice).toHaveBeenCalled())
    expect(calls.saveInvoice.mock.calls[0][0]).toMatchObject({ kind: 'CREDIT_MEMO', customerCode: 'C100',
      originalInvoiceId: 'i-1', reference: 'INV-1004' })
  })
})
