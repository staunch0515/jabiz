import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { accountsOf, STATEMENTS, sum, type DetailLine, type StatementRow } from './api'
import DashboardPage from './DashboardPage'
import { documentPath } from './format'
import LineDetailPage from './LineDetailPage'
import StatementPage from './StatementPage'
import { renderAt, setUpTexts } from '../journal/testing'

const calls = vi.hoisted(() => ({
  statement: vi.fn(), layout: vi.fn(), issue: vi.fn(), exported: vi.fn(), detail: vi.fn(), dashboard: vi.fn(),
  permissions: new Set<string>(),
}))

vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  runStatement: calls.statement,
  layoutAccounts: calls.layout,
  issue: calls.issue,
  exportStatement: calls.exported,
  runLineDetail: calls.detail,
  loadDashboard: calls.dashboard,
}))

vi.mock('@jabiz/admin', async (original) => ({
  ...(await original<typeof import('@jabiz/admin')>()),
  useAuth: () => ({ can: (p: string) => calls.permissions.has(p) }),
}))

const row = (lineCode: string, label: string, kind: string, month: string | null, extra = {}): StatementRow =>
  ({ seq: 0, lineCode, label, kind, month, quarter: month, yearToDate: month, priorMonth: null,
    priorYearToDate: null, ...extra })

const INCOME: StatementRow[] = [
  row('REVENUE', 'Revenue', 'HEADING', null),
  row('RETURNS', 'Sales returns and allowances', 'LINE', '-2000.00'),
  row('OPERATING_EXPENSES', 'Operating expenses', 'LINE', '-116935.00'),
  row('OPERATING_EXPENSES.6400', 'Professional Fees', 'LINE', '-34000.00'),
  row('NET_INCOME', 'Net income', 'TOTAL', '5127.10'),
]

const LINES: DetailLine[] = [
  { lineKey: '6400:AP-1:1', kind: 'ENTRY', accountCode: '6400', accountName: 'Professional Fees',
    postingDate: '2026-01-20', glNo: 'AP-1', documentNo: 'BILL-3', amount: '7500.00',
    sourceEntity: 'FinBill', sourceId: 'b-1' },
  { lineKey: '6400:MAN-2:1', kind: 'ENTRY', accountCode: '6400', accountName: 'Professional Fees',
    postingDate: '2026-01-31', glNo: 'MAN-2', documentNo: 'JE-0002', amount: '25000.00', sourceEntity: 'FinJournal',
    sourceId: 'j-2' },
  { lineKey: '6400:AP-5:1', kind: 'ENTRY', accountCode: '6400', accountName: 'Professional Fees',
    postingDate: '2026-01-21', glNo: 'AP-5', documentNo: 'BILL-5', amount: '1500.00',
    sourceEntity: 'FinBill', sourceId: 'b-5' },
]

beforeAll(setUpTexts)

beforeEach(() => {
  calls.permissions = new Set(['ledger.read'])
  calls.statement.mockReset().mockResolvedValue({ items: INCOME, offset: 0, limit: 500 })
  calls.layout.mockReset().mockResolvedValue(new Map([['RETURNS', '4900'], ['OPERATING_EXPENSES', '6000-6999'],
    ['NET_INCOME', '4000-9999']]))
  calls.issue.mockReset()
  calls.exported.mockReset().mockResolvedValue(undefined)
  calls.detail.mockReset().mockResolvedValue({ items: LINES, offset: 0, limit: 500 })
  calls.dashboard.mockReset()
})

const routes = [
  { path: '/statements/lines', element: <LineDetailPage /> },
  { path: '/statements/:kind', element: <StatementPage /> },
  { path: '/finance-dashboard', element: <DashboardPage /> },
]

describe('the financial statements', () => {
  it('find the accounts behind a line, a line of each account and an unmapped one', () => {
    const layout = new Map([['OPERATING_EXPENSES', '6000-6999'], ['CASH', '1000-1199']])
    expect(accountsOf(row('CASH', 'Cash', 'LINE', '1'), layout)).toBe('1000-1199')
    expect(accountsOf(row('OPERATING_EXPENSES.6400', 'Fees', 'LINE', '1'), layout)).toBe('6400')
    expect(accountsOf(row('UNMAPPED.2150', '2150 Payroll', 'UNMAPPED', '1'), layout)).toBe('2150')
    expect(accountsOf(row('ASSETS', 'Assets', 'HEADING', null), layout)).toBeUndefined()
    expect(accountsOf(row('NET_CHANGE', 'Net change', 'TOTAL', '1'), layout)).toBeUndefined()
    expect(sum(['7500.00', '25000.00', '1500.00', null, '-0.10'])).toBe('33999.90')
  })

  it('show the figures as stored, negatives in parentheses, and drill from the drillable columns', async () => {
    renderAt('/statements/income-statement?through=2026-01-31&knownAt=2026-02-03T00:00:00Z', routes)
    expect(await screen.findByTestId('cell-RETURNS-month')).toHaveTextContent('(2,000.00)')
    expect(screen.getByTestId('cell-NET_INCOME-month')).toHaveTextContent('5,127.10')
    expect(calls.statement).toHaveBeenCalledWith(STATEMENTS['income-statement'],
      { through: '2026-01-31', knownAt: '2026-02-03T00:00:00Z' })
    const fees = await screen.findByTestId('drill-OPERATING_EXPENSES.6400-month')
    const link = new URL(fees.getAttribute('href')!, 'http://x')
    expect(link.pathname).toBe('/statements/lines')
    expect(Object.fromEntries(link.searchParams)).toEqual({ accounts: '6400', through: '2026-01-31', span: 'MONTH',
      knownAt: '2026-02-03T00:00:00Z', label: 'Professional Fees' })
    expect(new URL(screen.getByTestId('drill-OPERATING_EXPENSES.6400-quarter').getAttribute('href')!, 'http://x')
      .searchParams.get('span')).toBe('QUARTER')
    // Last year's columns and headings do not drill.
    expect(screen.queryByTestId('drill-OPERATING_EXPENSES.6400-priorMonth')).not.toBeInTheDocument()
    expect(screen.queryByTestId('drill-REVENUE-month')).not.toBeInTheDocument()
    expect(screen.queryByTestId('issue')).not.toBeInTheDocument()
  })

  it('run with the parameters given, export through the platform and issue with the permission', async () => {
    calls.permissions.add('report.issue')
    calls.issue.mockResolvedValue({ runId: 'run-1', contentHash: 'h' })
    renderAt('/statements/balance-sheet', routes)
    expect(screen.queryByTestId('statement')).not.toBeInTheDocument()
    await userEvent.type(screen.getByTestId('param-asOf'), '2026-01-31')
    await userEvent.click(screen.getByTestId('run'))
    await waitFor(() => expect(calls.statement).toHaveBeenCalledWith(STATEMENTS['balance-sheet'],
      { asOf: '2026-01-31' }))
    await userEvent.click(screen.getByTestId('export-pdf'))
    expect(calls.exported).toHaveBeenCalledWith('finance.report.balance_sheet', 'pdf', { asOf: '2026-01-31' })
    await userEvent.click(screen.getByTestId('issue'))
    expect(await screen.findByTestId('issued')).toHaveTextContent('Issued as run run-1.')
    expect(calls.issue).toHaveBeenCalledWith(STATEMENTS['balance-sheet'], { asOf: '2026-01-31' }, expect.any(String))
  })

  it('open the entries behind a figure, each with its document', async () => {
    renderAt('/statements/lines?accounts=6400&through=2026-01-31&span=MONTH&label=Professional%20Fees', routes)
    expect(await screen.findByTestId('page-title')).toHaveTextContent('Detail of Professional Fees')
    expect(await screen.findByTestId('account-6400')).toHaveTextContent('34,000.00')
    expect(calls.detail).toHaveBeenCalledWith({ accounts: '6400', through: '2026-01-31', span: 'MONTH' })
    expect(screen.getByTestId('document-6400:AP-1:1')).toHaveAttribute('href', '/payables/bills/b-1')
    expect(screen.getByTestId('document-6400:MAN-2:1')).toHaveAttribute('href', '/gl/journals/j-2')
    expect(within(screen.getByTestId('lines')).getByText('BILL-5')).toBeInTheDocument()
    expect(documentPath({ sourceEntity: 'FinFxRevaluationRun', sourceId: 'r' })).toBeUndefined()
    expect(documentPath({ sourceEntity: 'FinInvoice', sourceId: 'i' })).toBe('/receivables/invoices/i')
  })

  it('show the dashboard figures, each opening its report', async () => {
    calls.dashboard.mockResolvedValue({ cash: '311680.00', receivables: '158735.00', payables: '46300.00',
      revenue: '128035.00', netIncome: '5127.10', periodKey: '2026-01', periodStatus: 'CLOSED' })
    renderAt('/finance-dashboard', routes)
    await userEvent.clear(screen.getByTestId('dashboard-day'))
    await userEvent.type(screen.getByTestId('dashboard-day'), '2026-01-31{Enter}')
    await waitFor(() => expect(calls.dashboard).toHaveBeenCalledWith('2026-01-31'))
    expect(await screen.findByTestId('value-cash')).toHaveTextContent('311,680.00')
    expect(screen.getByTestId('value-receivables')).toHaveTextContent('158,735.00')
    expect(screen.getByTestId('value-payables')).toHaveTextContent('46,300.00')
    expect(screen.getByTestId('value-close')).toHaveTextContent('2026-01 closed')
    expect(screen.getByTestId('tile-cash')).toHaveAttribute('href', '/statements/balance-sheet?asOf=2026-01-31')
    expect(screen.getByTestId('tile-receivables').getAttribute('href')).toContain('finance.ar.aging')
  })

  it('show a dash for a figure the user may not read', async () => {
    calls.dashboard.mockResolvedValue({ cash: null, receivables: '158735.00', payables: null, revenue: null,
      netIncome: '5127.10', periodKey: null, periodStatus: null })
    renderAt('/finance-dashboard', routes)
    expect(await screen.findByTestId('value-receivables')).toHaveTextContent('158,735.00')
    expect(screen.getByTestId('value-cash')).toHaveTextContent('—')
    expect(screen.getByTestId('value-close')).toHaveTextContent('—')
  })
})
