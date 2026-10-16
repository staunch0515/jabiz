import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import type { ReactNode } from 'react'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE, Toaster } from '@jabiz/admin'
import FinanceOverviewPage from './FinanceOverviewPage'
import { byCheck, transferFilterOf } from './finance'
import { monthPeriod, previousMonth, type ReconItem } from './finance-api'
import { TEMPLATE } from './ids'
import { messages } from './messages'
import ReconciliationPage from './ReconciliationPage'
import ReportsPage from './ReportsPage'
import TransferReviewPage from './TransferReviewPage'
import TransfersPage from './TransfersPage'

type Row = Record<string, unknown>

/** The server as the finance pages see it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  templates: new Map<string, Row[] | Error>(),
  datasets: new Map<string, Row[]>(),
  runQuery: vi.fn(),
  runProcess: vi.fn(),
  sessionFetch: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { GET: server.get, POST: server.post },
    unwrap: async (call: Promise<unknown>) => call,
    runQuery: server.runQuery,
    runProcess: server.runProcess,
    sessionFetch: server.sessionFetch,
    useAuth: () => ({ can: (p: string) => server.permissions.has(p) }),
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
    ApprovalPanel: ({ requestId }: { requestId: string }) => (
      <div data-testid="approval-panel" data-request={requestId}>decide</div>
    ),
  }
})

const TAKER = '0192f0a0-0000-7000-8000-00000000aa01'
const TRANSFER = '0192f0a0-0000-7000-8000-00000000bb01'
const REQUEST = '0192f0a0-0000-7000-8000-00000000cc01'
const RUN = '0192f0a0-0000-7000-8000-00000000dd01'
const TOPUP = '0192f0a0-0000-7000-8000-00000000ee01'

const FINANCE = ['qb.admin.finance.read', 'qb.admin.users.read', 'qb.reconcile', 'ledger.read', 'report.issue',
  'report.archive.read']

function transfer(change: Row = {}): Row {
  return { transferId: TRANSFER, userId: TAKER, externalAccountId: 'acct_1234567890WXYZ', amount: 60000, fee: 100,
    net: 59900, status: 'REVIEW', approvalRequestId: REQUEST, stripeTransferId: null, reversedAmount: 0,
    createdAt: '2026-10-15T01:07:00Z', stale: false, held: false, ...change }
}

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set(FINANCE)
  server.templates = new Map<string, Row[] | Error>([
    [TEMPLATE.financeSummary, [{ sponsorBalance: 9000, sponsorHeld: 0, sponsorPendingCount: 2, sponsorPendingAmount: 200,
      takerBalance: 1500, takerPendingOut: 0, transfersInReview: 3, transfersInDoubt: 1, heldTakers: 2,
      lastRunId: RUN, lastRunAt: '2026-10-15T18:37:00Z', lastRunStatus: 'DIFF', lastRunItems: 4, openTasks: 5 }]],
    [TEMPLATE.accountBalances, [{ accountCode: '2110', accountName: 'Sponsor prepayments', balance: -9000 },
      { accountCode: '1110', accountName: 'Stripe balance', balance: 10500 }]],
    [TEMPLATE.transfers, [transfer()]],
    [TEMPLATE.users, [{ userId: TAKER, userName: 'aki@example.com', email: 'aki@example.com', displayName: 'Aki',
      banned: false }]],
    [TEMPLATE.wallets, [{ ownerId: TAKER, balance: 60000, pendingOut: 0 }]],
    [TEMPLATE.payoutAccounts, [{ status: 'ENABLED', payoutsEnabled: true, externalAccountId: 'acct_1234567890WXYZ' }]],
    [TEMPLATE.attemptRewards, [{ attemptId: 'a1', name: 'Capitals', submittedAt: '2026-10-14T00:00:00Z', reward: 100,
      fee: 20, rewardStatus: 'PAID' }]],
    [TEMPLATE.reconRuns, [{ runId: RUN, scheduledTime: '2026-10-15T18:37:00Z', status: 'DIFF', itemCount: 3 }]],
    [TEMPLATE.reconItems, [
      { itemId: 'i1', checkCode: 'TOPUP_REFUNDS', partyId: TAKER, reference: TOPUP, expected: 100, actual: 150,
        difference: 50 },
      { itemId: 'i2', checkCode: 'TOPUP_REFUNDS', partyId: TAKER, reference: TOPUP, expected: 0, actual: 10,
        difference: 10 },
      { itemId: 'i3', checkCode: 'TRANSFER_LEDGER', partyId: TAKER, reference: TRANSFER, expected: 1, actual: 0,
        difference: -1 },
      { itemId: 'i4', checkCode: 'LEDGER_ZERO', partyId: null, reference: null, expected: 0, actual: 1,
        difference: 1 },
    ]],
    [TEMPLATE.monthly, [{ seq: 1, category: 'TOPUP', transactionCount: 1, amount: 10000, stripeBalance: 10000,
      sponsorWallets: -10000 }]],
    [TEMPLATE.balances, [{ seq: 1, rowKind: 'ACCOUNT', code: '1110', name: 'Stripe balance', opening: 0, debit: 10000,
      credit: 0, closing: 10000 }, { seq: 901, rowKind: 'CHECK', code: 'SPONSOR_WALLETS', name: 'Sponsor wallets',
      opening: 0, debit: 0, credit: 10000, closing: -10000, partyTotal: -10000, matches: true }]],
  ])
  server.datasets = new Map([['urn:jabiz:dataset:default:QbUserBan', []]])
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { offset?: number; limit?: number;
    count?: boolean } = {}) => {
    const rows = server.templates.get(id)
    if (rows instanceof Error) throw rows
    const all = rows ?? []
    const offset = options.offset ?? 0
    const limit = options.limit ?? 100
    return { items: all.slice(offset, offset + limit), offset, limit, ...(options.count ? { total: all.length } : {}) }
  })
  server.runProcess.mockReset().mockResolvedValue({ runId: 'run-1', contentHash: 'f'.repeat(64), rowCount: 12 })
  server.sessionFetch.mockReset().mockResolvedValue(new Response('kind,count\r\n', {
    status: 200, headers: { 'Content-Disposition': 'attachment; filename="qb.admin.monthly-20261101.csv"' } }))
  server.get.mockReset().mockImplementation(async (path: string) => {
    if (path === '/api/dictionaries/{urn}') return []
    if (path === '/api/reports/runs') return [{ runId: 'run-0', period: '2026-10-01 – 2026-11-01', rowCount: 12,
      contentHash: 'a'.repeat(64), issuedTime: '2026-11-01T02:00:00Z' }]
    throw new ApiError(404, { detail: 'Not found' })
  })
  server.post.mockReset().mockImplementation(async (_path: string,
    options: { params: { path: { resourceId: string } } }) => {
    const rows = server.datasets.get(options.params.path.resourceId)
    if (!rows) throw new ApiError(403, { detail: 'Forbidden' })
    return { items: rows.map((attributes) => ({ attributes })) }
  })
  URL.createObjectURL = vi.fn(() => 'blob:report')
  URL.revokeObjectURL = vi.fn()
  // The browser would save the file; jsdom cannot navigate.
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
})

function renderAt(path: string, route: string, element: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path={route} element={element} />
            <Route path="*" element={<p data-testid="elsewhere">elsewhere</p>} />
          </Routes>
        </MemoryRouter>
        <Toaster />
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

const calledWith = (id: string) => server.runQuery.mock.calls.filter(([template]) => template === id)

describe('FinanceOverviewPage', () => {
  it('links every number to the list that holds it', async () => {
    renderAt('/qb/finance', '/qb/finance', <FinanceOverviewPage />)
    expect(await screen.findByTestId('finance-in-review')).toHaveAttribute('href', '/qb/finance/transfers?status=REVIEW')
    expect(screen.getByTestId('finance-in-review')).toHaveTextContent('3')
    expect(screen.getByTestId('finance-in-doubt')).toHaveAttribute('href', '/qb/finance/transfers?stale=1')
    expect(screen.getByTestId('finance-held')).toHaveAttribute('href', '/qb/finance/transfers?held=1')
    expect(screen.getByTestId('finance-last-run')).toHaveAttribute('href', `/qb/finance/reconciliation?run=${RUN}`)
    expect(screen.getByTestId('finance-last-run')).toHaveTextContent('Differences, 4 differences')
    expect(screen.getByTestId('finance-tasks')).toHaveAttribute('href', '/tasks')
    // The staleness is the server's: the page only says since when.
    expect(calledWith(TEMPLATE.financeSummary)[0][1].params.staleBefore).toMatch(/^\d{4}-\d{2}-\d{2}T/)
    const accounts = await screen.findAllByTestId('finance-account')
    expect(accounts.map((row) => row.dataset.code)).toEqual(['2110', '2120', '2130', '1110', '1120', '4110', '5110'])
    expect(accounts[3]).toHaveTextContent('1110 Stripe balance')
  })

  it('does not ask for the ledger without the permission to read it', async () => {
    server.permissions = new Set(['qb.admin.finance.read'])
    renderAt('/qb/finance', '/qb/finance', <FinanceOverviewPage />)
    await screen.findByTestId('finance-in-review')
    expect(screen.queryByTestId('finance-accounts')).not.toBeInTheDocument()
    expect(calledWith(TEMPLATE.accountBalances)).toHaveLength(0)
  })
})

describe('TransfersPage', () => {
  it('filters as the address says and marks transfers in doubt', async () => {
    server.templates.set(TEMPLATE.transfers, [transfer({ status: 'CHECK', stale: true, approvalRequestId: null })])
    renderAt('/qb/finance/transfers?stale=1&status=CHECK', '/qb/finance/transfers', <TransfersPage />)
    const row = await screen.findByTestId('transfer-row')
    expect(row).toHaveAttribute('data-stale', 'true')
    expect(within(row).getByTestId('transfer-stale')).toHaveTextContent('In doubt')
    expect(row).toHaveTextContent('…WXYZ')
    expect(row).not.toHaveTextContent('acct_1234567890WXYZ')
    const [, options] = calledWith(TEMPLATE.transfers)[0]
    expect(options.filters).toEqual([{ field: 'status', op: 'eq', value: 'CHECK' },
      { field: 'stale', op: 'eq', value: true }])
    expect(options.count).toBe(true)
  })

  it('offers the release of a held user to those who review payouts, and runs it after asking', async () => {
    server.templates.set(TEMPLATE.transfers, [transfer({ status: 'REJECTED', held: true, approvalRequestId: null })])
    server.permissions = new Set([...FINANCE, 'qb.payout.review'])
    renderAt('/qb/finance/transfers?held=1', '/qb/finance/transfers', <TransfersPage />)
    await userEvent.click(await screen.findByTestId('transfer-release'))
    await userEvent.click(await screen.findByRole('button', { name: 'Release' }))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith('QB_TRANSFER_HOLD_RELEASE', { userId: TAKER }))
  })

  it('does not offer the release without the permission', async () => {
    server.templates.set(TEMPLATE.transfers, [transfer({ status: 'REJECTED', held: true, approvalRequestId: null })])
    renderAt('/qb/finance/transfers', '/qb/finance/transfers', <TransfersPage />)
    expect(await screen.findByTestId('transfer-held')).toBeInTheDocument()
    expect(screen.queryByTestId('transfer-release')).not.toBeInTheDocument()
  })

  it('leads a transfer waiting for review to its review', async () => {
    renderAt('/qb/finance/transfers', '/qb/finance/transfers', <TransfersPage />)
    expect(await screen.findByTestId('transfer-review-link'))
      .toHaveAttribute('href', `/qb/finance/transfers/${TRANSFER}/review`)
  })
})

describe('TransferReviewPage', () => {
  const renderReview = () => renderAt(`/qb/finance/transfers/${TRANSFER}/review`,
    '/qb/finance/transfers/:transferId/review', <TransferReviewPage />)

  it('offers the decision once the transfer and the payee are read for its request', async () => {
    server.permissions = new Set([...FINANCE, 'qb.payout.review'])
    renderReview()
    expect(await screen.findByTestId('approval-panel')).toHaveAttribute('data-request', REQUEST)
    expect(screen.getByTestId('review-amount')).toHaveTextContent('60,000')
    expect(screen.getByTestId('review-payee')).toHaveTextContent('Aki')
    expect(screen.getAllByTestId('review-reward')).toHaveLength(1)
    expect(calledWith(TEMPLATE.transfers)[0][1].filters).toEqual([{ field: 'transferId', op: 'eq', value: TRANSFER }])
  })

  it('does not offer it when anything about the payee could not be read', async () => {
    server.permissions = new Set([...FINANCE, 'qb.payout.review'])
    server.templates.set(TEMPLATE.attemptRewards, new ApiError(403, { detail: 'Forbidden' }))
    renderReview()
    expect(await within(await screen.findByTestId('review-payee-section')).findByText('Forbidden'))
      .toBeInTheDocument()
    expect(screen.getByTestId('review-needs-data')).toBeInTheDocument()
    expect(screen.queryByTestId('approval-panel')).not.toBeInTheDocument()
  })

  it('says a decided transfer waits no more', async () => {
    server.permissions = new Set([...FINANCE, 'qb.payout.review'])
    server.templates.set(TEMPLATE.transfers, [transfer({ status: 'SENT' })])
    renderReview()
    expect(await screen.findByTestId('review-not-pending')).toBeInTheDocument()
    expect(screen.queryByTestId('approval-panel')).not.toBeInTheDocument()
  })

  it('asks for nothing when the address names no transfer', async () => {
    renderAt('/qb/finance/transfers/nope/review', '/qb/finance/transfers/:transferId/review', <TransferReviewPage />)
    expect(await screen.findByText('There is no such transfer.')).toBeInTheDocument()
    expect(server.runQuery).not.toHaveBeenCalled()
  })
})

describe('ReconciliationPage', () => {
  it("groups a run's differences by check, each leading to what it is about", async () => {
    renderAt(`/qb/finance/reconciliation?run=${RUN}`, '/qb/finance/reconciliation', <ReconciliationPage />)
    const groups = await screen.findAllByTestId('recon-group')
    expect(groups.map((group) => group.dataset.check)).toEqual(['TOPUP_REFUNDS', 'TRANSFER_LEDGER', 'LEDGER_ZERO'])
    expect(within(groups[0]).getAllByTestId('recon-item')).toHaveLength(2)
    expect(within(groups[0]).getAllByTestId('recon-reference')[0])
      .toHaveAttribute('href', `/qb/finance/topups?topUp=${TOPUP}`)
    expect(within(groups[1]).getByTestId('recon-reference'))
      .toHaveAttribute('href', `/qb/finance/transfers?transfer=${TRANSFER}`)
    expect(within(groups[1]).getByTestId('recon-party')).toHaveAttribute('href', `/qb/users/${TAKER}`)
    expect(within(groups[2]).queryByTestId('recon-reference')).not.toBeInTheDocument()
    expect(calledWith(TEMPLATE.reconItems)[0][1].params).toEqual({ runId: RUN })
  })
})

describe('ReportsPage', () => {
  it('exports and issues the chosen month through the platform', async () => {
    renderAt('/qb/finance/reports', '/qb/finance/reports', <ReportsPage />)
    const month = await screen.findByTestId('report-month')
    fireEvent.change(month, { target: { value: '2026-10' } })
    const monthly = screen.getByTestId('report-monthly')
    expect(await within(monthly).findByTestId('monthly-row')).toHaveAttribute('data-category', 'TOPUP')
    expect(calledWith(TEMPLATE.monthly).at(-1)![1].params).toEqual({ from: '2026-10-01T00:00:00.000Z',
      to: '2026-11-01T00:00:00.000Z' })

    await userEvent.click(within(monthly).getByTestId('export-csv'))
    await waitFor(() => expect(server.sessionFetch).toHaveBeenCalled())
    const [url, init] = server.sessionFetch.mock.calls[0]
    expect(url).toBe('/api/queries/qb.admin.monthly/export?format=csv')
    expect(JSON.parse(init.body)).toEqual({ params: { from: '2026-10-01T00:00:00.000Z',
      to: '2026-11-01T00:00:00.000Z' } })

    await userEvent.click(within(monthly).getByTestId('report-issue'))
    await userEvent.click(await screen.findByRole('button', { name: 'Issue' }))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith('REPORT_ISSUE', { templateId: TEMPLATE.monthly,
      params: { from: '2026-10-01T00:00:00.000Z', to: '2026-11-01T00:00:00.000Z' } }))
    expect(within(monthly).getAllByTestId('issued-run')[0]).toHaveAttribute('data-hash', 'a'.repeat(64))

    const balances = screen.getByTestId('report-balances')
    const check = await within(balances).findByText('Sponsor wallets (2110)')
    expect(check.closest('tr')).toHaveAttribute('data-matches', 'true')
  })

  it('offers no issue without the permission, and no archive without its own', async () => {
    server.permissions = new Set(['qb.admin.finance.read'])
    renderAt('/qb/finance/reports', '/qb/finance/reports', <ReportsPage />)
    await screen.findAllByTestId('export-pdf')
    expect(screen.queryByTestId('report-issue')).not.toBeInTheDocument()
    expect(screen.queryByTestId('issued-runs')).not.toBeInTheDocument()
    expect(server.get).not.toHaveBeenCalledWith('/api/reports/runs', expect.anything())
  })
})

describe('finance helpers', () => {
  it('makes a month UTC, the next month excluded', () => {
    expect(monthPeriod('2026-12')).toEqual({ from: '2026-12-01T00:00:00.000Z', to: '2027-01-01T00:00:00.000Z' })
    expect(monthPeriod('2026-13')).toBeNull()
    expect(monthPeriod('')).toBeNull()
    expect(previousMonth(new Date('2026-01-15T00:00:00Z'))).toBe('2025-12')
  })

  it('reads only keys, states and days from an address', () => {
    expect(transferFilterOf(new URLSearchParams(`user=${TAKER}&transfer=x&status=<b>&from=2026-10-01&to=oops`)))
      .toEqual({ status: null, userId: TAKER, transferId: null, from: '2026-10-01', to: null, stale: false,
        held: false })
  })

  it('groups differences by check in their order', () => {
    const item = (itemId: string, checkCode: string): ReconItem => ({ itemId, checkCode, partyId: null,
      reference: null, expected: '0', actual: '0', difference: '0' })
    expect(byCheck([item('1', 'B'), item('2', 'A'), item('3', 'B')]).map(([check, items]) => [check, items.length]))
      .toEqual([['B', 2], ['A', 1]])
  })
})
