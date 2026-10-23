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
import BankTopUpPage from './BankTopUpPage'
import BankTopUpsPage from './BankTopUpsPage'
import { parseAmount } from './finance'
import { todayInJapan } from './finance-api'
import { PROCESS, TEMPLATE } from './ids'
import { messages } from './messages'

type Row = Record<string, unknown>

/** The server as the bank top-up pages see it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  rows: [] as Record<string, unknown>[],
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
const TOPUP = '0192f0a0-0000-7000-8000-00000000ee01'
const REQUEST = '0192f0a0-0000-7000-8000-00000000cc01'
const FINANCE_ADMIN = '0192f0a0-0000-7000-8000-00000000ff01'

function bankTopUp(change: Row = {}): Row {
  return { topUpId: TOPUP, sponsorId: SPONSOR, company: 'Acme', transferCode: '1000042', requestedAmount: 50000,
    amount: 50000, status: 'REPORTED', createdAt: '2026-10-22T01:00:00Z', payerName: '1000042 カ）アクメ',
    transferDate: '2026-10-22', reportedAt: '2026-10-22T02:00:00Z', receivedOn: null, bankReference: null,
    recordedBy: null, recordedAt: null, approvalRequestId: null, paidAt: null, ...change }
}

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set(['qb.topup.bank.record', 'qb.topup.bank.approve'])
  server.rows = [bankTopUp()]
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { filters?: { field: string;
    value: unknown }[]; count?: boolean } = {}) => {
    if (id !== TEMPLATE.bankTopUps) return { items: [], offset: 0, limit: 20 }
    const one = options.filters?.find((filter) => filter.field === 'topUpId')
    const items = one ? server.rows.filter((row) => row.topUpId === one.value) : server.rows
    return { items, offset: 0, limit: 20, ...(options.count ? { total: items.length } : {}) }
  })
  server.runProcess.mockReset().mockResolvedValue({ topUpId: TOPUP, status: 'REVIEW', approvalRequestId: REQUEST })
  server.get.mockReset().mockImplementation(async (path: string) => {
    if (path === '/api/dictionaries/{urn}') return [{ code: 'REPORTED', label: 'Transfer reported' },
      { code: 'REVIEW', label: 'Being confirmed' }, { code: 'PAID', label: 'Paid' }]
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

describe('parseAmount', () => {
  it('takes whole positive amounts with separators only', () => {
    expect(parseAmount('49,560')).toBe(49560)
    expect(parseAmount(' 1 000 ')).toBe(1000)
    expect(parseAmount('0')).toBeNull()
    expect(parseAmount('12.5')).toBeNull()
    expect(parseAmount('-3')).toBeNull()
  })
})

describe('BankTopUpsPage', () => {
  it('records the money of a reported transfer for approval', async () => {
    const user = userEvent.setup()
    renderAt('/qb/finance/bank-topups', '/qb/finance/bank-topups', <BankTopUpsPage />)
    const row = await screen.findByTestId('bank-row')
    expect(within(row).getByTestId('bank-row-code')).toHaveTextContent('1000042')
    expect(row).toHaveTextContent('1000042 カ）アクメ · sent 2026-10-22')
    await user.click(within(row).getByTestId('bank-row-record'))
    const dialog = await screen.findByTestId('bank-record-dialog')
    expect(within(dialog).getByTestId('bank-record-amount')).toHaveValue('50000')
    expect(within(dialog).getByTestId('bank-record-day')).toHaveValue(todayInJapan())
    expect(within(dialog).getByTestId('bank-record-submit')).toBeDisabled()
    await expectAccessible()
    await user.clear(within(dialog).getByTestId('bank-record-amount'))
    await user.type(within(dialog).getByTestId('bank-record-amount'), '49,560')
    await user.type(within(dialog).getByTestId('bank-record-reference'), 'ST-0001')
    await user.click(within(dialog).getByTestId('bank-record-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROCESS.bankRecord, { topUpId: TOPUP,
      amount: 49560, receivedOn: todayInJapan(), bankReference: 'ST-0001' }))
    await waitFor(() => expect(screen.queryByTestId('bank-record-dialog')).toBeNull())
  })

  it('shows the server\'s refusal and keeps the dialog', async () => {
    server.runProcess.mockRejectedValue(new ApiError(422, { detail: 'Refused', violations: [{ field: 'bankReference',
      code: 'QB_TOPUP_BANK_REFERENCE_USED', message: 'This entry of the bank statement is already recorded.' }] }))
    const user = userEvent.setup()
    renderAt('/qb/finance/bank-topups', '/qb/finance/bank-topups', <BankTopUpsPage />)
    await user.click(await screen.findByTestId('bank-row-record'))
    await user.type(screen.getByTestId('bank-record-reference'), 'ST-0001')
    await user.click(screen.getByTestId('bank-record-submit'))
    expect(await screen.findByTestId('bank-record-problem')).toBeInTheDocument()
    expect(screen.getByTestId('bank-record-dialog')).toBeInTheDocument()
  })

  it('offers recording only to those who may, and the approval of one under review', async () => {
    server.permissions = new Set(['qb.topup.bank.approve'])
    server.rows = [bankTopUp({ status: 'REVIEW', approvalRequestId: REQUEST, receivedOn: '2026-10-23', amount: 49560,
      bankReference: 'ST-0001', recordedBy: FINANCE_ADMIN })]
    renderAt('/qb/finance/bank-topups', '/qb/finance/bank-topups', <BankTopUpsPage />)
    const row = await screen.findByTestId('bank-row')
    expect(within(row).queryByTestId('bank-row-record')).toBeNull()
    expect(within(row).getByTestId('bank-row-open')).toHaveTextContent('Approval')
    expect(within(row).getByTestId('bank-row-open')).toHaveAttribute('href', `/qb/finance/bank-topups/${TOPUP}`)
  })
})

describe('BankTopUpPage', () => {
  it('shows what was recorded against what was asked for, with the decision', async () => {
    server.rows = [bankTopUp({ status: 'REVIEW', approvalRequestId: REQUEST, receivedOn: '2026-10-23', amount: 49560,
      bankReference: 'ST-0001', recordedBy: FINANCE_ADMIN, recordedAt: '2026-10-23T03:00:00Z' })]
    renderAt(`/qb/finance/bank-topups/${TOPUP}`, '/qb/finance/bank-topups/:topUpId', <BankTopUpPage />)
    expect(await screen.findByTestId('bank-reference')).toHaveTextContent('ST-0001')
    expect(screen.getByTestId('bank-received')).toHaveTextContent('49,560')
    expect(screen.getByTestId('bank-differs')).toBeInTheDocument()
    expect(screen.getByTestId('approval-panel')).toHaveAttribute('data-request', REQUEST)
    await expectAccessible()
  })

  it('offers no decision without the permission, nor once it is no longer under review', async () => {
    server.permissions = new Set(['qb.topup.bank.record'])
    server.rows = [bankTopUp({ status: 'REVIEW', approvalRequestId: REQUEST, receivedOn: '2026-10-23',
      bankReference: 'ST-0001', recordedBy: FINANCE_ADMIN })]
    const first = renderAt(`/qb/finance/bank-topups/${TOPUP}`, '/qb/finance/bank-topups/:topUpId', <BankTopUpPage />)
    expect(await screen.findByText('Deciding needs the permission to approve bank top-ups.')).toBeInTheDocument()
    expect(screen.queryByTestId('approval-panel')).toBeNull()
    first.unmount()
    server.permissions = new Set(['qb.topup.bank.approve'])
    server.rows = [bankTopUp({ status: 'PAID', receivedOn: '2026-10-23', bankReference: 'ST-0001',
      recordedBy: FINANCE_ADMIN, paidAt: '2026-10-23T04:00:00Z' })]
    renderAt(`/qb/finance/bank-topups/${TOPUP}`, '/qb/finance/bank-topups/:topUpId', <BankTopUpPage />)
    expect(await screen.findByTestId('bank-not-pending')).toHaveTextContent('Paid')
    expect(screen.queryByTestId('approval-panel')).toBeNull()
  })

  it('says when there is no such bank top-up', async () => {
    server.rows = []
    renderAt(`/qb/finance/bank-topups/${TOPUP}`, '/qb/finance/bank-topups/:topUpId', <BankTopUpPage />)
    expect(await screen.findByText('There is no such bank top-up.')).toBeInTheDocument()
  })
})
