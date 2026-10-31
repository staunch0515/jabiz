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
import { currentMonthInJapan, laterChanges, nextMonth, percent, planInEffect } from './account-api'
import OperatorAccountPage from './OperatorAccountPage'
import OperatorPage from './OperatorPage'
import OperatorWithdrawalsPage from './OperatorWithdrawalsPage'
import { ACCOUNT_PROCESS, PERMISSION, TEMPLATE } from './ids'
import { messages } from './messages'

type Row = Record<string, unknown>

const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  account: {} as Record<string, unknown>,
  rows: {} as Record<string, Record<string, unknown>[]>,
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
  }
})

const OPERATOR = '0192f0a0-0000-7000-8000-00000000a001'
const LEAD = '0192f0a0-0000-7000-8000-00000000b002'

const account = (change: Row = {}): Row => ({ operatorId: OPERATOR, lead: true, credited: '52345', withdrawn: '40000',
  balance: '12345', minimum: '10000', cooldownHours: '72', bankName: 'Mizuho', branchName: 'Umeda',
  accountType: 'ORDINARY', accountNumber: '***4567', accountHolder: 'カンサイ', changedAt: '2026-10-01T01:00:00Z', ...change })

const terms = [['1', '0', '0.5000'], ['2', '20000', '0.4000'], ['3', '40000', '0.3000'], ['4', '60000', '0.2000'],
  ['5', '100000', '0.1000']].map(([plan, fixedFee, platformShare]) => ({ kind: 'TERMS', plan, fixedFee, platformShare,
  effectiveMonth: null, status: null, proposedAt: null }))

const withdrawal = (change: Row = {}): Row => ({ withdrawalId: 'w1', amount: '15000', status: 'REQUESTED',
  requestedAt: '2026-10-30T01:00:00Z', bankName: 'Mizuho', accountNumber: '***4567', paidOn: null, rejectReason: null,
  closedAt: null, ...change })

const adminWithdrawal = (change: Row = {}): Row => ({ ...withdrawal(), operatorId: OPERATOR,
  operatorName: 'Kansai Partners', requestedBy: LEAD, bankCode: '0001', branchCode: '123', branchName: 'Umeda', accountType: 'ORDINARY',
  accountHolder: 'カンサイ', bankReference: null, paymentRecordedBy: null, approvalRequestId: null, paidAt: null,
  revision: 1, ...change })

const LABELS = [
  ...['1', '2', '3', '4', '5'].map((code) => ({ code, label: `Plan ${code}` })),
  { code: 'REQUESTED', label: 'Requested' }, { code: 'REJECTED', label: 'Rejected' },
  { code: 'PENDING', label: 'Pending' }, { code: 'APPROVED', label: 'Approved' }, { code: 'ORDINARY', label: 'Ordinary' },
]

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set([PERMISSION.operatorUse])
  server.account = account()
  server.rows = {
    [TEMPLATE.ownPlans]: [...terms,
      { kind: 'CHANGE', plan: '3', effectiveMonth: '2020-01', status: 'APPROVED', proposedAt: '2019-12-01T01:00:00Z' },
      { kind: 'CHANGE', plan: '4', effectiveMonth: '2999-01', status: 'PENDING', proposedAt: '2026-10-01T01:00:00Z' }],
    [TEMPLATE.ownWithdrawals]: [withdrawal(), withdrawal({ withdrawalId: 'w2', status: 'REJECTED',
      rejectReason: 'The holder does not match' })],
    [TEMPLATE.operatorWithdrawals]: [adminWithdrawal()],
    [TEMPLATE.operators]: [{ operatorId: OPERATOR, name: 'Kansai Partners', contactName: 'Ken',
      contactEmail: 'ops@kansai.example', collection: 'PLATFORM', status: 'ACTIVE', note: null, revision: 3 }],
    [TEMPLATE.operatorPlans]: [{ planChangeId: 'p1', operatorId: OPERATOR, operatorName: 'Kansai Partners', plan: '2',
      effectiveMonth: '2020-01', reason: 'Signed', status: 'APPROVED', proposedBy: LEAD,
      proposedAt: '2019-12-01T01:00:00Z', approvalRequestId: 'r0', decidedAt: '2019-12-02T01:00:00Z' }],
  }
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { count?: boolean } = {}) => {
    const items = id === TEMPLATE.operatorAccount ? [server.account] : (server.rows[id] ?? [])
    return { items, offset: 0, limit: 20, ...(options.count ? { total: items.length } : {}) }
  })
  server.runProcess.mockReset().mockResolvedValue({ withdrawalId: 'w3', status: 'REQUESTED', amount: '12000',
    approvalRequestId: null })
  server.get.mockReset().mockImplementation(async (path: string) => {
    if (path === '/api/dictionaries/{urn}') return LABELS
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
          </Routes>
        </MemoryRouter>
        <Toaster />
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

const renderAccount = () => renderAt('/qb/operator/account', '/qb/operator/account', <OperatorAccountPage />)

describe('the plans', () => {
  it('takes the latest approved change not after the month, else plan 1', () => {
    const changes = [
      { plan: '2', effectiveMonth: '2026-03', status: 'APPROVED' },
      { plan: '3', effectiveMonth: '2026-06', status: 'APPROVED' },
      { plan: '5', effectiveMonth: '2026-05', status: 'REJECTED' },
      { plan: '4', effectiveMonth: '2026-12', status: 'PENDING' },
    ]
    expect(planInEffect(changes, '2026-02')).toBe('1')
    expect(planInEffect(changes, '2026-05')).toBe('2')
    expect(planInEffect(changes, '2026-11')).toBe('3')
    expect(laterChanges(changes, '2026-11').map((c) => c.plan)).toEqual(['4'])
    expect(nextMonth('2026-12')).toBe('2027-01')
    expect(nextMonth('2026-01')).toBe('2026-02')
    expect(currentMonthInJapan(new Date('2026-10-31T15:30:00Z'))).toBe('2026-11')
    expect(percent('0.4000')).toBe('40%')
  })
})

describe("the operator's account", () => {
  it('shows the balance in points, the plan and the terms, and the lead sets the bank account', async () => {
    const { container } = renderAccount()
    expect(await screen.findByTestId('account-balance-value')).toHaveTextContent('12,345 Kudos')
    expect(screen.getByTestId('account-credited')).toHaveTextContent('52,345 Kudos')
    expect(screen.getByTestId('operator-account')).not.toHaveTextContent(/JPY|¥|￥/)
    const current = await screen.findByTestId('account-plan-current')
    expect(current).toHaveAttribute('data-plan', '3')
    await waitFor(() => expect(current).toHaveTextContent('Plan 3'))
    expect(current).toHaveTextContent('40,000 Kudos')
    expect(current).toHaveTextContent('30%')
    expect(screen.getByTestId('account-plan-later')).toHaveTextContent('2999-01')
    expect(screen.getAllByTestId('plan-terms-row')).toHaveLength(5)
    expect(screen.getByTestId('account-bank-current')).toHaveTextContent('***4567')
    expect(screen.getByTestId('withdraw-hint')).toHaveTextContent('At least 10,000 Kudos; the balance is 12,345 Kudos.')
    await screen.findAllByTestId('withdrawal-row')
    await expectAccessible(container)

    const user = userEvent.setup()
    server.runProcess.mockResolvedValueOnce({ bankAccountId: 'b1', usableFrom: '2026-11-03T01:00:00Z' })
    await user.click(screen.getByTestId('bank-edit'))
    const form = screen.getByTestId('bank-form')
    expect(within(form).getByTestId('bank-save')).toBeDisabled()
    await user.type(within(form).getByTestId('bank-bankCode'), '0001')
    await user.type(within(form).getByTestId('bank-bankName'), 'Mizuho')
    await user.type(within(form).getByTestId('bank-branchCode'), '123')
    await user.type(within(form).getByTestId('bank-branchName'), 'Umeda')
    await user.type(within(form).getByTestId('bank-accountNumber'), '1234567')
    await user.type(within(form).getByTestId('bank-accountHolder'), 'カンサイ')
    await user.click(within(form).getByTestId('bank-save'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(ACCOUNT_PROCESS.bankAccountSave, {
      bankCode: '0001', bankName: 'Mizuho', branchCode: '123', branchName: 'Umeda', accountType: 'ORDINARY',
      accountNumber: '1234567', accountHolder: 'カンサイ' }))
  })

  it('lets the lead request a withdrawal of the amount, and cancel a requested one', async () => {
    renderAccount()
    const user = userEvent.setup()
    await user.type(await screen.findByTestId('withdraw-amount'), '12000')
    await user.click(screen.getByTestId('withdraw-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(ACCOUNT_PROCESS.withdrawalRequest,
      { amount: 12000 }))
    const rows = await screen.findAllByTestId('withdrawal-row')
    expect(rows[0]).toHaveTextContent('15,000 Kudos')
    expect(within(rows[1]).getByTestId('withdrawal-reason')).toHaveTextContent('The holder does not match')
    expect(within(rows[1]).queryByTestId('withdrawal-cancel')).not.toBeInTheDocument()
    await user.click(within(rows[0]).getByTestId('withdrawal-cancel'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(ACCOUNT_PROCESS.withdrawalCancel,
      { withdrawalId: 'w1' }))
  })

  it('shows the server’s refusal of a withdrawal', async () => {
    server.runProcess.mockRejectedValueOnce(new ApiError(422, { detail: 'The bank account changed less than 72 hours ago.' }))
    renderAccount()
    const user = userEvent.setup()
    await user.type(await screen.findByTestId('withdraw-amount'), '12000')
    await user.click(screen.getByTestId('withdraw-submit'))
    expect(await screen.findByTestId('withdraw-problem')).toBeInTheDocument()
  })

  it('offers a member who does not lead neither the bank form nor withdrawing nor cancelling', async () => {
    server.account = account({ lead: false })
    renderAccount()
    expect(await screen.findByTestId('operator-account')).toHaveAttribute('data-lead', 'false')
    expect(await screen.findByTestId('account-balance-value')).toHaveTextContent('12,345 Kudos')
    const rows = await screen.findAllByTestId('withdrawal-row')
    expect(screen.queryByTestId('bank-edit')).not.toBeInTheDocument()
    expect(screen.queryByTestId('withdraw-form')).not.toBeInTheDocument()
    expect(within(rows[0]).queryByTestId('withdrawal-cancel')).not.toBeInTheDocument()
  })
})

describe("finance's withdrawals", () => {
  const renderFinance = (path = '/qb/finance/operator-withdrawals') =>
    renderAt(path, '/qb/finance/operator-withdrawals', <OperatorWithdrawalsPage />)

  beforeEach(() => {
    server.permissions = new Set([PERMISSION.financeRead, PERMISSION.settlementPay])
    server.runProcess.mockResolvedValue({ withdrawalId: 'w1', status: 'PAYING', approvalRequestId: 'r1' })
  })

  it('records the payment for approval, filtered by the state in the address', async () => {
    const { container } = renderFinance('/qb/finance/operator-withdrawals?status=REQUESTED')
    const row = await screen.findByTestId('operator-withdrawal-row')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.operatorWithdrawals,
      expect.objectContaining({ params: { status: 'REQUESTED' } }))
    expect(within(row).getByTestId('withdrawal-bank')).toHaveTextContent('0001 Mizuho / 123 Umeda')
    expect(row).toHaveTextContent(/JPY|¥|￥/)
    await expectAccessible(container)
    const user = userEvent.setup()
    await user.click(within(row).getByTestId('withdrawal-pay'))
    const dialog = await screen.findByTestId('operator-withdrawal-pay-dialog')
    await user.type(within(dialog).getByTestId('withdrawal-reference'), ' OPW-0001 ')
    await user.click(within(dialog).getByTestId('withdrawal-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(ACCOUNT_PROCESS.withdrawalPay,
      { withdrawalId: 'w1', paidOn: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/), bankReference: 'OPW-0001' }))
  })

  it('rejects only with a reason', async () => {
    renderFinance()
    const row = await screen.findByTestId('operator-withdrawal-row')
    const user = userEvent.setup()
    await user.click(within(row).getByTestId('withdrawal-reject'))
    const dialog = await screen.findByTestId('operator-withdrawal-reject-dialog')
    expect(within(dialog).getByTestId('withdrawal-submit')).toBeDisabled()
    await user.type(within(dialog).getByTestId('withdrawal-reject-reason'), 'The holder does not match')
    await user.click(within(dialog).getByTestId('withdrawal-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(ACCOUNT_PROCESS.withdrawalReject,
      { withdrawalId: 'w1', reason: 'The holder does not match' }))
  })

  it('offers no payment without the permission', async () => {
    server.permissions = new Set([PERMISSION.financeRead])
    renderFinance()
    const row = await screen.findByTestId('operator-withdrawal-row')
    expect(within(row).queryByTestId('withdrawal-pay')).not.toBeInTheDocument()
    expect(within(row).queryByTestId('withdrawal-reject')).not.toBeInTheDocument()
  })
})

describe("headquarters' plan of an operator", () => {
  const renderOperator = () => renderAt(`/qb/operators/${OPERATOR}`, '/qb/operators/:operatorId', <OperatorPage />)

  it('lists the changes and proposes a plan from a later month with a reason', async () => {
    server.permissions = new Set([PERMISSION.operatorsRead, PERMISSION.operatorsWrite])
    server.runProcess.mockResolvedValue({ planChangeId: 'p2', status: 'PENDING', approvalRequestId: 'r2' })
    const { container } = renderOperator()
    const section = await screen.findByTestId('operator-plan')
    expect(await within(section).findByTestId('plan-change-row')).toHaveTextContent('Signed')
    expect(within(section).getByTestId('operator-plan-current')).toHaveAttribute('data-plan', '2')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.operatorPlans,
      expect.objectContaining({ params: { operatorId: OPERATOR } }))
    await expectAccessible(container)
    const user = userEvent.setup()
    expect(within(section).getByTestId('plan-propose')).toBeDisabled()
    await user.type(within(section).getByTestId('plan-reason'), 'Bigger partner')
    await user.click(within(section).getByTestId('plan-propose'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(ACCOUNT_PROCESS.planSet, {
      operatorId: OPERATOR, plan: '2', effectiveMonth: nextMonth(currentMonthInJapan()), reason: 'Bigger partner' }))
  })

  it('offers no proposal without the permission to write', async () => {
    server.permissions = new Set([PERMISSION.operatorsRead])
    renderOperator()
    const section = await screen.findByTestId('operator-plan')
    await within(section).findByTestId('plan-change-row')
    expect(within(section).queryByTestId('plan-propose')).not.toBeInTheDocument()
  })
})
