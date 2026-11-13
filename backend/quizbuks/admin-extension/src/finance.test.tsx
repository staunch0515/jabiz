import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import type { ReactNode } from 'react'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE, Toaster } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import FinanceOverviewPage from './FinanceOverviewPage'
import { byCheck, levelCounts, transferFilterOf } from './finance'
import { monthPeriod, monthRange, previousMonth, type ReconItem } from './finance-api'
import { DICTIONARY, TEMPLATE } from './ids'
import { messages } from './messages'
import ReconciliationPage from './ReconciliationPage'
import ReportsPage from './ReportsPage'
import InvestorMetricsPage from './InvestorMetricsPage'
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

const FINANCE = ['qb.admin.finance.read', 'qb.admin.users.read', 'qb.reconcile', 'task.read', 'ledger.read',
  'report.issue', 'report.archive.read']

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
      entryFeesUnsettled: 120 }]],
    [TEMPLATE.reconSummary, [{ lastRunId: RUN, lastRunAt: '2026-10-15T18:37:00Z', lastRunStatus: 'DIFF',
      lastRunItems: 4, openTasks: 5,
      lastRunSummary: '{"LEDGER_ZERO":1,"TOPUP_REFUNDS":2,"DISTRIBUTION_PRICE_RANGE":3,"CREDIT_UNUSED_WITH_DUE":1,'
        + '"DISTRIBUTION_AT_FLOOR":4,"TAKER_WALLET_LAG":1,"SPONSOR_WALLET":0}' }]],
    [TEMPLATE.accountBalances, [{ accountCode: '2110', accountName: 'Sponsor prepayments', balance: -9000 },
      { accountCode: '1110', accountName: 'Stripe balance', balance: 10500 }]],
    [TEMPLATE.transfers, [transfer()]],
    [TEMPLATE.users, [{ userId: TAKER, userName: 'aki@example.com', email: 'aki@example.com', displayName: 'Aki',
      banned: false }]],
    [TEMPLATE.wallets, [{ ownerId: TAKER, balance: 60000, pendingOut: 0 }]],
    [TEMPLATE.payoutAccounts, [{ status: 'ENABLED', payoutsEnabled: true, externalAccountId: 'acct_1234567890WXYZ' }]],
    [TEMPLATE.attemptRewards, [{ attemptId: 'a1', name: 'Capitals', submittedAt: '2026-10-14T00:00:00Z', reward: 100,
      fee: 20, rewardStatus: 'PAID' }]],
    [TEMPLATE.feeAccountTotals, [
      { subAccount: 'FEE_CREDIT', balance: 900, lotsRemaining: 900, openLots: 1, sponsors: 1, paidFees: 800 },
      { subAccount: 'CASH', balance: 1700, lotsRemaining: 1700, openLots: 3, sponsors: 2, paidFees: 300 }]],
    [TEMPLATE.reconRuns, [{ runId: RUN, scheduledTime: '2026-10-15T18:37:00Z', status: 'DIFF', itemCount: 5,
      summary: '{"DISTRIBUTION_AT_FLOOR":1,"DISTRIBUTION_PRICE_RANGE":1,"TOPUP_REFUNDS":2,"TRANSFER_LEDGER":1}' }]],
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
    [TEMPLATE.reconAttentionDays, [{ checkCode: 'DISTRIBUTION_PRICE_RANGE', objectKey: 'op-1', days: 2 },
      { checkCode: 'CREDIT_UNUSED_WITH_DUE', objectKey: '-', days: 0 }]],
    [TEMPLATE.monthly, [{ seq: 1, category: 'TOPUP', transactionCount: 1, amount: 10000, stripeBalance: 10000,
      sponsorWallets: -10000 }]],
    [TEMPLATE.investorMetrics, [
      { month: '2026-09', newSponsors: 2, activeSponsors: 1, retainedSponsors: 0, publicationsWentOn: 1, newTakers: 5,
        activeTakers: 4, attempts: 10, completions: 8, passes: 4, prizesWon: 4, prizesClaimed: 3, prizesRedeemed: 2,
        invitationsSent: 6, invitationsAnswered: 3, topUps: 0, topUpAmount: 0, rewardsPaid: 0, commission: 0 },
      { month: '2026-10', newSponsors: 1, activeSponsors: 3, retainedSponsors: 1, publicationsWentOn: 2, newTakers: 7,
        activeTakers: 9, attempts: 20, completions: 12, passes: 6, prizesWon: 6, prizesClaimed: 5, prizesRedeemed: 4,
        invitationsSent: 0, invitationsAnswered: 0, topUps: 1, topUpAmount: 10000, rewardsPaid: 1000, commission: 200 }]],
    [TEMPLATE.investorSponsors, [{ publisherId: 's-1', company: 'Kissa Kudos', takers: 9, attempts: 20, completions: 12,
      passes: 6, prizesWon: 6, prizesRedeemed: 4, invitationsSent: 6, invitationsAnswered: 3 }]],
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
  server.get.mockReset().mockImplementation(async (path: string, options?: { params?: { path?: { runId?: string; urn?: string } } }) => {
    if (path === '/api/dictionaries/{urn}') {
      return options?.params?.path?.urn === DICTIONARY.reconLevel ? [
        { code: 'LEDGER_ZERO', label: 'Difference' }, { code: 'TOPUP_REFUNDS', label: 'Difference' },
        { code: 'DISTRIBUTION_PRICE_RANGE', label: 'Needs attention' }, { code: 'CREDIT_UNUSED_WITH_DUE', label: 'Needs attention' },
        { code: 'TAKER_WALLET_LAG', label: 'Note' }] : []
    }
    // Two runs of the monthly report: October's (current) and September's; only the period shown is listed.
    if (path === '/api/reports/runs') return [
      { runId: 'run-0', period: '2026-10-01 – 2026-11-01', rowCount: 12, contentHash: 'a'.repeat(64),
        issuedTime: '2026-11-01T02:00:00Z' },
      { runId: 'run-9', period: '2026-09-01 – 2026-10-01', rowCount: 12, contentHash: 'b'.repeat(64),
        issuedTime: '2026-10-01T02:00:00Z' }]
    if (path === '/api/reports/runs/{runId}') {
      const october = options?.params?.path?.runId === 'run-0'
      return { params: october ? { from: '2026-10-01T00:00:00Z', to: '2026-11-01T00:00:00Z' }
        : { from: '2026-09-01T00:00:00Z', to: '2026-10-01T00:00:00Z' } }
    }
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

function renderAt(path: string, route: string, element: ReactNode,
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
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
    // F-M14: the latest run's counts by level, from its summary, leading to that run.
    const levels = screen.getByTestId('finance-last-run-levels')
    expect(levels).toHaveAttribute('href', `/qb/finance/reconciliation?run=${RUN}`)
    expect(levels).toHaveTextContent('Differences 3 / needs attention 4 / notes 5')
    // The staleness is the server's: the page only says since when.
    expect(calledWith(TEMPLATE.financeSummary)[0][1].params.staleBefore).toMatch(/^\d{4}-\d{2}-\d{2}T/)
    const accounts = await screen.findAllByTestId('finance-account')
    expect(accounts.map((row) => row.dataset.code)).toEqual(['2110', '2120', '2130', '1110', '1120', '4110', '5110', '5120',
      '2160', '1130', '4120', '2150', '2170', '2180', '2190', '5140'])
    expect(accounts[3]).toHaveTextContent('1110 Stripe balance')
    // Q88b: the entry fees paid and not yet settled.
    expect(screen.getByTestId('finance-entry-fees')).toHaveTextContent('120')
    // F-M1: the platform fee accounts by sub-account, as the server sums them for this (UTC) month.
    const feeAccounts = await screen.findAllByTestId('finance-fee-account')
    expect(feeAccounts.map((row) => row.dataset.subAccount)).toEqual(['FEE_CREDIT', 'CASH'])
    expect(feeAccounts[1]).toHaveTextContent('1,700')
    const [, totalsOptions] = calledWith(TEMPLATE.feeAccountTotals)[0]
    expect(totalsOptions.params.from).toMatch(/^\d{4}-\d{2}-01T00:00:00\.000Z$/)
  })

  it('does not ask for the ledger, the reconciliation or the tasks without the permissions to read them', async () => {
    server.permissions = new Set(['qb.admin.finance.read'])
    renderAt('/qb/finance', '/qb/finance', <FinanceOverviewPage />)
    await screen.findByTestId('finance-in-review')
    expect(screen.queryByTestId('finance-accounts')).not.toBeInTheDocument()
    expect(calledWith(TEMPLATE.accountBalances)).toHaveLength(0)
    expect(screen.queryByTestId('finance-last-run')).not.toBeInTheDocument()
    expect(screen.queryByTestId('finance-tasks')).not.toBeInTheDocument()
    expect(calledWith(TEMPLATE.reconSummary)).toHaveLength(0)
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

  it('warns when the transfer goes to another account than the payee has now', async () => {
    server.permissions = new Set([...FINANCE, 'qb.payout.review'])
    renderReview()
    await screen.findByTestId('approval-panel')
    expect(screen.queryByTestId('review-account-changed')).not.toBeInTheDocument()
    cleanup()
    server.templates.set(TEMPLATE.payoutAccounts, [{ status: 'ENABLED', payoutsEnabled: true,
      externalAccountId: 'acct_0000000000ABCD' }])
    renderReview()
    expect(await screen.findByTestId('review-account-changed')).toHaveTextContent('WXYZ')
    expect(screen.getByTestId('review-account-changed')).toHaveTextContent('ABCD')
  })

  it('hides the decision while the transfer is read again, and for another request until its payee is read',
    async () => {
      server.permissions = new Set([...FINANCE, 'qb.payout.review'])
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      renderAt(`/qb/finance/transfers/${TRANSFER}/review`, '/qb/finance/transfers/:transferId/review',
        <TransferReviewPage />, client)
      await screen.findByTestId('approval-panel')
      const key = ['qb-ext', 'finance', 'transfer', TRANSFER]
      // Read again: the panel goes while the read is under way.
      let answer: (value: unknown) => void = () => {}
      server.runQuery.mockImplementationOnce(() => new Promise((resolve) => { answer = resolve }))
      act(() => void client.refetchQueries({ queryKey: key }))
      await waitFor(() => expect(screen.queryByTestId('approval-panel')).not.toBeInTheDocument())
      expect(screen.getByTestId('review-needs-data')).toBeInTheDocument()
      await act(async () => answer({ items: [transfer()], offset: 0, limit: 1 }))
      expect(await screen.findByTestId('approval-panel')).toHaveAttribute('data-request', REQUEST)
      // Another request (the transfer was submitted again): the payee read for the old one does not count.
      server.runQuery.mockImplementation(() => new Promise(() => {}))
      act(() => client.setQueryData(key, { ...(client.getQueryData(key) as Row), approvalRequestId: 'request-2' }))
      await waitFor(() => expect(screen.queryByTestId('approval-panel')).not.toBeInTheDocument())
      expect(screen.getByTestId('review-needs-data')).toBeInTheDocument()
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
    // F-M14 (review L10): the runs' column counts the differences, with what needs attention beside them.
    expect(screen.getByTestId('recon-run-counts')).toHaveTextContent(/^3\+1 needs attention$/)
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

describe('levelCounts', () => {
  it('counts the summary by the level of each check', () => {
    expect(levelCounts('{"LEDGER_ZERO":1,"TOPUP_REFUNDS":2,"DISTRIBUTION_PRICE_RANGE":3,"CREDIT_UNUSED_WITH_DUE":1,'
      + '"AUDIENCE_REFUND_NO_PERIOD":2,"DISTRIBUTION_AT_FLOOR":4,"TAKER_WALLET_LAG":1}'))
      .toEqual({ differences: 3, attention: 6, notes: 5 })
    expect(levelCounts(null)).toEqual({ differences: 0, attention: 0, notes: 0 })
    expect(levelCounts('not json')).toEqual({ differences: 0, attention: 0, notes: 0 })
  })
})

describe('ReconciliationPage by level (F-M14)', () => {
  function items(): Row[] {
    return [
      { itemId: 'a1', checkCode: 'DISTRIBUTION_PRICE_RANGE', partyId: 'op-1', reference: null, expected: 0, actual: 1,
        difference: 1 },
      { itemId: 'a2', checkCode: 'CREDIT_UNUSED_WITH_DUE', partyId: null, reference: null, expected: 0, actual: 5,
        difference: 5 },
      { itemId: 'a3', checkCode: 'TAKER_WALLET_LAG', partyId: TAKER, reference: null, expected: 1, actual: 2,
        difference: 1 },
      { itemId: 'a4', checkCode: 'LEDGER_ZERO', partyId: null, reference: null, expected: 0, actual: 1, difference: 1 },
    ]
  }

  it('shows each group its level, what to do and, for attention, for how many days', async () => {
    server.templates.set(TEMPLATE.reconItems, items())
    renderAt(`/qb/finance/reconciliation?run=${RUN}`, '/qb/finance/reconciliation', <ReconciliationPage />)
    const groups = await screen.findAllByTestId('recon-group')
    const level = (check: string) => groups.find((group) => group.dataset.check === check)!
    expect(within(level('LEDGER_ZERO')).getByTestId('recon-level')).toHaveAttribute('data-level', 'DIFFERENCE')
    expect(within(level('TAKER_WALLET_LAG')).getByTestId('recon-level')).toHaveAttribute('data-level', 'NOTE')
    expect(within(level('TAKER_WALLET_LAG')).getByTestId('recon-level')).toHaveTextContent('Note')
    const price = level('DISTRIBUTION_PRICE_RANGE')
    expect(within(price).getByTestId('recon-level')).toHaveAttribute('data-level', 'ATTENTION')
    expect(within(price).getByTestId('recon-level')).toHaveTextContent('Needs attention')
    expect(within(price).getByTestId('recon-help')).toHaveTextContent(/outside the headquarters. range.*operations §7\.53/)
    await waitFor(() => expect(within(price).getByTestId('recon-days')).toHaveTextContent('Needs attention for 2 days'))
    expect(within(level('CREDIT_UNUSED_WITH_DUE')).getByTestId('recon-days'))
      .toHaveTextContent('Needs attention for 0 days')
    expect(within(level('LEDGER_ZERO')).queryByTestId('recon-days')).not.toBeInTheDocument()
    expect(calledWith(TEMPLATE.reconAttentionDays)[0][1].params).toMatchObject({ runId: RUN })
  })

  it('asks for the days only when the run has an item for attention', async () => {
    renderAt(`/qb/finance/reconciliation?run=${RUN}`, '/qb/finance/reconciliation', <ReconciliationPage />)
    await screen.findAllByTestId('recon-group')
    expect(calledWith(TEMPLATE.reconAttentionDays)).toHaveLength(0)
  })

  it('links a reference or a user only to someone who may open the page it leads to', async () => {
    server.permissions = new Set(['qb.reconcile', 'task.read'])
    renderAt(`/qb/finance/reconciliation?run=${RUN}`, '/qb/finance/reconciliation', <ReconciliationPage />)
    const groups = await screen.findAllByTestId('recon-group')
    const references = within(groups[0]).getAllByTestId('recon-reference')
    expect(references[0].tagName).toBe('SPAN')
    expect(references[0]).toHaveTextContent('The top-up')
    expect(within(groups[1]).getByTestId('recon-reference').tagName).toBe('SPAN')
    expect(within(groups[1]).getByTestId('recon-party').tagName).toBe('SPAN')
    expect(within(groups[1]).getByTestId('recon-party')).toHaveTextContent(TAKER)
    cleanup()
    server.permissions = new Set(['qb.reconcile', 'qb.admin.finance.read'])
    renderAt(`/qb/finance/reconciliation?run=${RUN}`, '/qb/finance/reconciliation', <ReconciliationPage />)
    const again = await screen.findAllByTestId('recon-group')
    expect(within(again[1]).getByTestId('recon-reference').tagName).toBe('A')
    expect(within(again[1]).getByTestId('recon-party').tagName).toBe('SPAN')
  })
})

describe('ReportsPage', () => {
  it('names the prepayment kinds and shows the 2180 column (F-M5)', async () => {
    server.templates.set(TEMPLATE.monthly, [{ seq: 1, category: 'PREPAYMENT_FROZEN', transactionCount: 2, amount: 1650,
      sponsorCash: 1650, feePrepayments: -1650 }])
    renderAt('/qb/finance/reports?month=2026-10', '/qb/finance/reports', <ReportsPage />)
    const monthly = screen.getByTestId('report-monthly')
    const row = await within(monthly).findByTestId('monthly-row')
    expect(row).toHaveAttribute('data-category', 'PREPAYMENT_FROZEN')
    expect(row).toHaveTextContent('Prepayments set aside')
    expect(within(monthly).getByText('2180')).toBeInTheDocument()
    expect(row).toHaveTextContent('1,650')
  })

  it('exports and issues the month of the address through the platform', async () => {
    renderAt('/qb/finance/reports?month=2026-10', '/qb/finance/reports', <ReportsPage />)
    expect(await screen.findByTestId('report-month')).toHaveValue('2026-10')
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
    // October was issued before: the new run supersedes it. Only October's runs are listed.
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith('REPORT_ISSUE', { templateId: TEMPLATE.monthly,
      params: { from: '2026-10-01T00:00:00.000Z', to: '2026-11-01T00:00:00.000Z' }, supersedes: 'run-0' }))
    expect(within(monthly).getAllByTestId('issued-run').map((run) => run.dataset.hash)).toEqual(['a'.repeat(64)])

    const balances = screen.getByTestId('report-balances')
    const check = await within(balances).findByText('Every Sponsor wallet line (2110) names its Sponsor')
    expect(check.closest('tr')).toHaveAttribute('data-matches', 'true')
  })

  it('puts the chosen month in the address', async () => {
    renderAt('/qb/finance/reports?month=2026-10', '/qb/finance/reports', <ReportsPage />)
    fireEvent.change(await screen.findByTestId('report-month'), { target: { value: '2026-09' } })
    await waitFor(() => expect(calledWith(TEMPLATE.monthly).at(-1)![1].params).toEqual({
      from: '2026-09-01T00:00:00.000Z', to: '2026-10-01T00:00:00.000Z' }))
    expect(screen.getByTestId('report-month')).toHaveValue('2026-09')
    expect(await within(screen.getByTestId('report-monthly')).findAllByTestId('issued-run'))
      .toHaveLength(1)
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

describe('InvestorMetricsPage', () => {
  it('shows the months of the period in the address with a total, the Sponsors, and exports through the platform',
    async () => {
      renderAt('/qb/finance/investor-metrics?from=2026-09&to=2026-10', '/qb/finance/investor-metrics',
        <InvestorMetricsPage />)
      expect(await screen.findByTestId('investor-from')).toHaveValue('2026-09')
      expect(screen.getByTestId('investor-to')).toHaveValue('2026-10')
      const months = screen.getByTestId('investor-months')
      const rows = await within(months).findAllByTestId('investor-month')
      expect(rows.map((row) => row.dataset.month)).toEqual(['2026-09', '2026-10'])
      // The pass rate and the redemption rate are derived: October passed 6 of 12, collected 4 of 6.
      expect(within(rows[1]).getAllByRole('cell').map((cell) => cell.textContent)).toContain('50.0%')
      expect(within(rows[1]).getAllByRole('cell').map((cell) => cell.textContent)).toContain('66.7%')
      // The total sums events; the counts of people (active, retained) have none.
      const total = within(months).getByTestId('investor-total')
      expect(within(total).getAllByRole('cell').map((cell) => cell.textContent)).toEqual(['Total', '3', '', '', '3',
        '12', '', '30', '20', '10', '50.0%', '10', '8', '6', '60.0%', '6', '3', '1', '¥10,000', '¥1,000', '¥200'])
      expect(calledWith(TEMPLATE.investorMetrics).at(-1)![1].params).toEqual({ from: '2026-09-01T00:00:00.000Z',
        to: '2026-11-01T00:00:00.000Z' })

      const sponsors = screen.getByTestId('investor-sponsors')
      expect(await within(sponsors).findByTestId('investor-sponsor')).toHaveAttribute('data-sponsor', 's-1')
      expect(within(sponsors).getByText('Kissa Kudos')).toBeInTheDocument()
      await expectAccessible()

      await userEvent.click(within(months).getByTestId('export-csv'))
      await waitFor(() => expect(server.sessionFetch).toHaveBeenCalled())
      const [url, init] = server.sessionFetch.mock.calls[0]
      expect(url).toBe('/api/queries/qb.admin.investor-metrics/export?format=csv')
      expect(JSON.parse(init.body)).toEqual({ params: { from: '2026-09-01T00:00:00.000Z',
        to: '2026-11-01T00:00:00.000Z' } })
      expect(within(months).getByTestId('report-issue')).toBeInTheDocument()
    })

  it('puts the chosen months in the address and refuses a period that is not one', async () => {
    renderAt('/qb/finance/investor-metrics?from=2026-09&to=2026-10', '/qb/finance/investor-metrics',
      <InvestorMetricsPage />)
    fireEvent.change(await screen.findByTestId('investor-to'), { target: { value: '2026-12' } })
    await waitFor(() => expect(calledWith(TEMPLATE.investorMetrics).at(-1)![1].params).toEqual({
      from: '2026-09-01T00:00:00.000Z', to: '2027-01-01T00:00:00.000Z' }))
    fireEvent.change(screen.getByTestId('investor-from'), { target: { value: '2027-01' } })
    expect(await screen.findByText('Choose a period of one to 36 months, the first month not after the last.'))
      .toBeInTheDocument()
    expect(screen.queryByTestId('investor-months')).not.toBeInTheDocument()
  })

  it('shows the last six months without an address, and no issue without the permission', async () => {
    server.permissions = new Set(['qb.admin.finance.read'])
    renderAt('/qb/finance/investor-metrics', '/qb/finance/investor-metrics', <InvestorMetricsPage />)
    expect(await screen.findByTestId('investor-to')).toHaveValue(previousMonth())
    const [from, to] = [screen.getByTestId('investor-from'), screen.getByTestId('investor-to')]
      .map((input) => (input as HTMLInputElement).value)
    expect(monthRange(from, to)).not.toBeNull()
    const months = (Number(to.slice(0, 4)) - Number(from.slice(0, 4))) * 12 + Number(to.slice(5)) - Number(from.slice(5))
    expect(months).toBe(5)
    await screen.findAllByTestId('export-pdf')
    expect(screen.queryByTestId('report-issue')).not.toBeInTheDocument()
    expect(screen.queryByTestId('issued-runs')).not.toBeInTheDocument()
  })
})

describe('finance helpers', () => {
  it('makes a range of months UTC, the end excluded, and refuses a backwards or too long one', () => {
    expect(monthRange('2026-09', '2026-10')).toEqual({ from: '2026-09-01T00:00:00.000Z', to: '2026-11-01T00:00:00.000Z' })
    expect(monthRange('2026-10', '2026-10')).toEqual({ from: '2026-10-01T00:00:00.000Z', to: '2026-11-01T00:00:00.000Z' })
    expect(monthRange('2026-11', '2026-10')).toBeNull()
    expect(monthRange('2023-10', '2026-10')).toBeNull()
    expect(monthRange('2023-11', '2026-10')).not.toBeNull()
    expect(monthRange('x', '2026-10')).toBeNull()
  })

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
