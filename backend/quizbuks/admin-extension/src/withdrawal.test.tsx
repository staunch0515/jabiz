import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE, Toaster } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import BankWithdrawalsPage from './BankWithdrawalsPage'
import { DATASET, PATH, PROCESS, TEMPLATE } from './ids'
import { messages } from './messages'

type Row = Record<string, unknown>

const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  rows: [] as Row[],
  runQuery: vi.fn(),
  runProcess: vi.fn(),
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
    useAuth: () => ({ can: (p: string) => server.permissions.has(p) }),
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
  }
})

const TAKER = '0192f0a0-0000-7000-8000-00000000aa01'
const row = (transferId: string, status: string, extra: Row = {}): Row => ({
  transferId, userId: TAKER, amount: 3000, fee: 200, net: 2800, status, createdAt: '2026-10-22T01:00:00Z',
  bankCode: '0001', bankName: 'みずほ銀行', branchCode: '001', branchName: '東京営業部', accountType: 'ORDINARY',
  accountHolder: 'ヤマダ タロウ', approvalRequestId: 'r1', paidOn: null, paidReference: null, paidBy: null,
  failureCode: null, failureMessage: null, ...extra,
})

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set(['qb.admin.finance.read', 'qb.withdrawal.pay', 'qb.payout.bank.reveal'])
  server.rows = [row('w1', 'SENDING'), row('w2', 'REVIEW'),
    row('w3', 'SENT', { paidOn: '2026-10-22', paidReference: 'PAY0001' })]
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { count?: boolean } = {}) => {
    const items = id === TEMPLATE.bankWithdrawals ? server.rows : []
    return { items, offset: 0, limit: 20, ...(options.count ? { total: items.length } : {}) }
  })
  server.runProcess.mockReset().mockResolvedValue({ transferId: 'w1', status: 'SENT' })
  server.get.mockReset().mockImplementation(async (path: string) => {
    if (path === '/api/dictionaries/{urn}') return [{ code: 'SENDING', label: 'Sending' }]
    throw new ApiError(404, { detail: 'Not found' })
  })
  server.post.mockReset().mockResolvedValue({ value: '0001234' })
})

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={[PATH.bankWithdrawals]}>
          <Routes>
            <Route path={PATH.bankWithdrawals} element={<BankWithdrawalsPage />} />
          </Routes>
        </MemoryRouter>
        <Toaster />
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('BankWithdrawalsPage', () => {
  it('reveals the number of one to pay and records it paid', async () => {
    const user = userEvent.setup()
    renderPage()
    const rows = await screen.findAllByTestId('withdrawal-row')
    expect(rows).toHaveLength(3)
    const sending = rows[0]
    expect(sending).toHaveTextContent('ヤマダ タロウ')
    expect(within(rows[1]).getByTestId('withdrawal-review')).toBeInTheDocument()
    expect(within(rows[2]).queryByTestId('withdrawal-paid')).toBeNull()
    expect(rows[2]).toHaveTextContent('PAY0001')
    await expectAccessible()

    await user.click(within(sending).getByTestId('withdrawal-reveal'))
    expect(await within(sending).findByTestId('withdrawal-number')).toHaveTextContent('0001234')
    expect(server.post).toHaveBeenCalledWith('/api/datasets/{resourceId}/reveal', {
      params: { path: { resourceId: DATASET.transfer } }, body: { id: 'w1', field: 'accountNumber' } })

    await user.click(within(sending).getByTestId('withdrawal-paid'))
    await user.type(await screen.findByTestId('withdrawal-reference'), 'PAY0002')
    await user.click(screen.getByTestId('withdrawal-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROCESS.withdrawalPaid, {
      transferId: 'w1', paidOn: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/), paidReference: 'PAY0002' }))
  })

  it('records a return with its reason, and shows the server\'s refusal', async () => {
    server.runProcess.mockRejectedValueOnce(new ApiError(422, { detail: 'Your own withdrawal is paid by someone else' }))
    const user = userEvent.setup()
    renderPage()
    const sending = (await screen.findAllByTestId('withdrawal-row'))[0]
    await user.click(within(sending).getByTestId('withdrawal-returned'))
    await user.type(await screen.findByTestId('withdrawal-reason'), 'Account closed')
    await user.click(screen.getByTestId('withdrawal-submit'))
    expect(await screen.findByTestId('withdrawal-problem')).toHaveTextContent('paid by someone else')
    expect(server.runProcess).toHaveBeenCalledWith(PROCESS.withdrawalReturned, { transferId: 'w1',
      reason: 'Account closed' })
  })

  it('offers neither paying nor the number without the permissions', async () => {
    server.permissions = new Set(['qb.admin.finance.read'])
    renderPage()
    const sending = (await screen.findAllByTestId('withdrawal-row'))[0]
    expect(within(sending).queryByTestId('withdrawal-paid')).toBeNull()
    expect(within(sending).queryByTestId('withdrawal-reveal')).toBeNull()
  })
})
