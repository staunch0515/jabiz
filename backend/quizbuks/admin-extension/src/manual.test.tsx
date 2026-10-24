import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import type { ReactNode } from 'react'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE, Toaster } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import ManualCreditPage from './ManualCreditPage'
import ManualCreditsPage from './ManualCreditsPage'
import { todayInJapan } from './finance-api'
import { PROCESS, TEMPLATE } from './ids'
import { messages } from './messages'

type Row = Record<string, unknown>

/** The server as the manual credit pages see it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  rows: [] as Record<string, unknown>[],
  sponsors: [] as Record<string, unknown>[],
  runQuery: vi.fn(),
  runProcess: vi.fn(),
  get: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { GET: server.get },
    unwrap: async (call: Promise<unknown>) => call,
    runQuery: server.runQuery,
    runProcess: server.runProcess,
    useAuth: () => ({ can: (p: string) => server.permissions.has(p) }),
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
    ApprovalPanel: ({ requestId }: { requestId: string }) => (
      <div data-testid="approval-panel" data-request={requestId}>decide</div>
    ),
  }
})

const SPONSOR = '0192f0a0-0000-7000-8000-00000000aa01'
const CREDIT = '0192f0a0-0000-7000-8000-00000000ee11'
const REQUEST = '0192f0a0-0000-7000-8000-00000000cc11'
const FINANCE_ADMIN = '0192f0a0-0000-7000-8000-00000000ff01'

function credit(change: Row = {}): Row {
  return { topUpId: CREDIT, sponsorId: SPONSOR, company: 'Acme', transferCode: '1000077', manualKind: 'PAID_OUTSIDE',
    manualReason: 'Paid by cash at the office', amount: 30000, status: 'REVIEW', createdAt: '2026-10-24T01:00:00Z',
    receivedOn: '2026-10-24', bankReference: 'CASH-0001', recordedBy: FINANCE_ADMIN, approvalRequestId: REQUEST,
    paidAt: null, ...change }
}

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set(['qb.topup.manual.record', 'qb.topup.bank.approve'])
  server.rows = [credit()]
  server.sponsors = [{ userId: SPONSOR, company: 'Acme', contactName: 'Alice', reviewStatus: 'APPROVED' }]
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { filters?: { field: string;
    value: unknown }[]; count?: boolean } = {}) => {
    if (id === TEMPLATE.sponsors) return { items: server.sponsors, offset: 0, limit: 200 }
    if (id !== TEMPLATE.manualCredits) return { items: [], offset: 0, limit: 20 }
    const one = options.filters?.find((filter) => filter.field === 'topUpId')
    const items = one ? server.rows.filter((row) => row.topUpId === one.value) : server.rows
    return { items, offset: 0, limit: 20, ...(options.count ? { total: items.length } : {}) }
  })
  server.runProcess.mockReset().mockResolvedValue({ topUpId: CREDIT, status: 'REVIEW', approvalRequestId: REQUEST })
  server.get.mockReset().mockImplementation(async (path: string) => {
    if (path === '/api/dictionaries/{urn}') return [{ code: 'REVIEW', label: 'Being confirmed' },
      { code: 'PAID', label: 'Paid' }, { code: 'CANCELLED', label: 'Cancelled' }]
    throw new ApiError(404, { detail: 'Not found' })
  })
})

function renderAt(path: string, route: string, element: ReactNode) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
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

describe('ManualCreditsPage', () => {
  it('lists the credits with their kind and opens the one under review for approval', async () => {
    const { container } = renderAt('/qb/finance/manual-credits', '/qb/finance/manual-credits', <ManualCreditsPage />)
    const row = await screen.findByTestId('manual-row')
    expect(within(row).getByTestId('manual-row-code')).toHaveTextContent('1000077')
    expect(within(row).getByTestId('manual-row-kind')).toHaveTextContent('Paid outside the system')
    expect(within(row).getByTestId('manual-row-status')).toHaveTextContent('Being confirmed')
    expect(within(row).getByTestId('manual-row-open')).toHaveTextContent('Approval')
    expect(within(row).getByTestId('manual-row-open')).toHaveAttribute('href', `/qb/finance/manual-credits/${CREDIT}`)
    await expectAccessible(container)
  })

  it('records money received outside the system, with its statement entry', async () => {
    renderAt('/qb/finance/manual-credits', '/qb/finance/manual-credits', <ManualCreditsPage />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('manual-new'))
    const dialog = await screen.findByTestId('manual-record-dialog')
    // Nothing is sent before the sponsor, the amount, the reason and the entry are given.
    expect(within(dialog).getByTestId('manual-record-submit')).toBeDisabled()
    await user.click(within(dialog).getByTestId('manual-record-sponsor'))
    await user.click(await screen.findByRole('option', { name: /Acme/ }))
    await user.type(within(dialog).getByTestId('manual-record-amount'), '30,000')
    await user.type(within(dialog).getByTestId('manual-record-reason'), 'Paid by cash at the office')
    await user.clear(within(dialog).getByTestId('manual-record-day'))
    await user.type(within(dialog).getByTestId('manual-record-day'), '2026-10-24')
    await user.type(within(dialog).getByTestId('manual-record-reference'), ' CASH-0001 ')
    await user.click(within(dialog).getByTestId('manual-record-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROCESS.manualRecord, {
      sponsorId: SPONSOR, kind: 'PAID_OUTSIDE', amount: 30000, reason: 'Paid by cash at the office',
      receivedOn: '2026-10-24', bankReference: 'CASH-0001' }))
    await waitFor(() => expect(screen.queryByTestId('manual-record-dialog')).not.toBeInTheDocument())
  })

  it('records a grant without a statement entry, and shows the server\'s refusal', async () => {
    server.runProcess.mockRejectedValue(new ApiError(422, { detail: 'refused', violations: [
      { field: null, ruleCode: 'QB_TOPUP_MANUAL_OWN', message: 'Your own wallet is credited by another administrator.' }] }))
    renderAt('/qb/finance/manual-credits', '/qb/finance/manual-credits', <ManualCreditsPage />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('manual-new'))
    const dialog = await screen.findByTestId('manual-record-dialog')
    await user.click(within(dialog).getByTestId('manual-record-kind-GRANT'))
    expect(within(dialog).queryByTestId('manual-record-reference')).not.toBeInTheDocument()
    await user.click(within(dialog).getByTestId('manual-record-sponsor'))
    await user.click(await screen.findByRole('option', { name: /Acme/ }))
    await user.type(within(dialog).getByTestId('manual-record-amount'), '5000')
    await user.type(within(dialog).getByTestId('manual-record-reason'), 'Launch campaign')
    await user.click(within(dialog).getByTestId('manual-record-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROCESS.manualRecord, {
      sponsorId: SPONSOR, kind: 'GRANT', amount: 5000, reason: 'Launch campaign' }))
    expect(await screen.findByTestId('manual-record-problem')).toHaveTextContent('own wallet')
    expect(todayInJapan(new Date('2026-10-24T20:00:00Z'))).toBe('2026-10-25')
  })

  it('offers no recording without the permission', async () => {
    server.permissions = new Set(['qb.topup.bank.approve'])
    renderAt('/qb/finance/manual-credits', '/qb/finance/manual-credits', <ManualCreditsPage />)
    await screen.findByTestId('manual-row')
    expect(screen.queryByTestId('manual-new')).not.toBeInTheDocument()
  })

  it('takes a sponsor\'s id when the list is capped, and says when no sponsor can be credited', async () => {
    server.sponsors = Array.from({ length: 200 }, (_, i) => ({ userId: `0192f0a0-0000-7000-8000-${String(i)
      .padStart(12, '0')}`, company: `Company ${i}`, contactName: null, reviewStatus: 'APPROVED' }))
    renderAt('/qb/finance/manual-credits', '/qb/finance/manual-credits', <ManualCreditsPage />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('manual-new'))
    const dialog = await screen.findByTestId('manual-record-dialog')
    expect(await within(dialog).findByTestId('manual-sponsor-capped')).toHaveTextContent('first 200')
    await user.type(within(dialog).getByTestId('manual-record-sponsor-id'), SPONSOR)
    await user.click(within(dialog).getByTestId('manual-record-kind-GRANT'))
    await user.type(within(dialog).getByTestId('manual-record-amount'), '100')
    await user.type(within(dialog).getByTestId('manual-record-reason'), 'Welcome')
    await user.click(within(dialog).getByTestId('manual-record-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROCESS.manualRecord, {
      sponsorId: SPONSOR, kind: 'GRANT', amount: 100, reason: 'Welcome' }))

    server.sponsors = []
    renderAt('/qb/finance/manual-credits', '/qb/finance/manual-credits', <ManualCreditsPage />)
    await user.click((await screen.findAllByTestId('manual-new')).at(-1)!)
    expect(await screen.findByTestId('manual-no-sponsors')).toBeInTheDocument()
  })
})

describe('ManualCreditPage', () => {
  it('shows the credit and the approval panel while it waits', async () => {
    const { container } = renderAt(`/qb/finance/manual-credits/${CREDIT}`, '/qb/finance/manual-credits/:topUpId',
      <ManualCreditPage />)
    expect(await screen.findByTestId('manual-credit')).toHaveAttribute('data-status', 'REVIEW')
    expect(screen.getByTestId('manual-kind')).toHaveTextContent('Paid outside the system')
    expect(screen.getByTestId('manual-amount')).toHaveTextContent('30,000')
    expect(screen.getByTestId('manual-reference')).toHaveTextContent('CASH-0001')
    expect(screen.getByTestId('approval-panel')).toHaveAttribute('data-request', REQUEST)
    await expectAccessible(container)
  })

  it('shows a grant without a statement entry, and a paid one without the panel', async () => {
    server.rows = [credit({ manualKind: 'GRANT', receivedOn: null, bankReference: null, status: 'PAID',
      paidAt: '2026-10-24T02:00:00Z' })]
    renderAt(`/qb/finance/manual-credits/${CREDIT}`, '/qb/finance/manual-credits/:topUpId', <ManualCreditPage />)
    expect(await screen.findByTestId('manual-credit')).toHaveAttribute('data-status', 'PAID')
    expect(screen.getByTestId('manual-kind')).toHaveTextContent('Granted')
    expect(screen.queryByTestId('manual-reference')).not.toBeInTheDocument()
    expect(screen.getByTestId('manual-not-pending')).toHaveTextContent('Paid')
    expect(screen.queryByTestId('approval-panel')).not.toBeInTheDocument()
  })

  it('says when there is no such credit', async () => {
    server.rows = []
    renderAt(`/qb/finance/manual-credits/${CREDIT}`, '/qb/finance/manual-credits/:topUpId', <ManualCreditPage />)
    expect(await screen.findByText('There is no such manual credit.')).toBeInTheDocument()
  })
})
