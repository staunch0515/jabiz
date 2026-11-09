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
import FeeRequestPage from './FeeRequestPage'
import FeesPage from './FeesPage'
import { SponsorFeeAccount } from './SponsorFeeAccount'
import { FEE_PROCESS, TEMPLATE } from './ids'
import { messages } from './messages'

type Row = Record<string, unknown>

/** The server as the fee pages see it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  charges: [] as Record<string, unknown>[],
  invoices: [] as Record<string, unknown>[],
  requests: [] as Record<string, unknown>[],
  sponsors: [] as Record<string, unknown>[],
  onboarding: [] as Record<string, unknown>[],
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
const CHARGE = '0192f0a0-0000-7000-8000-00000000c011'
const INVOICE = '0192f0a0-0000-7000-8000-00000000d011'
const REQUEST = '0192f0a0-0000-7000-8000-00000000ee11'
const APPROVAL = '0192f0a0-0000-7000-8000-00000000cc11'
const FINANCE_ADMIN = '0192f0a0-0000-7000-8000-00000000ff01'

const charge = (change: Row = {}): Row => ({ chargeId: CHARGE, sponsorId: SPONSOR, company: 'Acme', operatorId: null,
  kind: 'MONTHLY', period: '2026-10', billingMode: 'PREPAID', amount: 1000, tax: 100, total: 1100, reason: null,
  chargedAt: '2026-10-02T01:07:00Z', chargedBy: null, invoiceNo: 'QBF-2026-00001', invoiceStatus: 'PAID', ...change })
const invoice = (change: Row = {}): Row => ({ invoiceId: INVOICE, invoiceNo: 'QBF-2026-00002', sponsorId: SPONSOR,
  company: 'Acme', operatorId: null, period: '2026-10', billingMode: 'POSTPAID', amount: 1000, tax: 100, total: 1100,
  paidAmount: 300, status: 'OVERDUE', issuedAt: '2026-11-02T01:17:00Z', dueOn: '2026-12-02', paidAt: null, remindersSent: 1,
  voidedAt: null, voidReason: null, ...change })
const request = (change: Row = {}): Row => ({ requestId: REQUEST, kind: 'MANUAL', sponsorId: SPONSOR, company: 'Acme',
  amount: 500, invoiceId: null, reason: 'Extra support', status: 'REVIEW', requestedBy: FINANCE_ADMIN,
  requestedAt: '2026-11-03T01:00:00Z', approvalRequestId: APPROVAL, decidedAt: null, chargeId: null, ...change })

const ACCOUNT: Row[] = [{ feeCreditBalance: 900, cashBalance: 700, cashHeld: 0 }]
const LOTS: Row[] = [
  { seq: 1, subAccount: 'FEE_CREDIT', lotId: 'g-1', source: 'MANUAL', amount: 900, remaining: 900,
    arrivedAt: '2026-11-01T00:00:00Z', expiresAt: '2027-01-30T00:00:00Z' },
  { seq: 2, subAccount: 'CASH', lotId: 'c-1', source: 'CARD', amount: 1000, remaining: 700,
    arrivedAt: '2026-11-02T00:00:00Z', expiresAt: null }]
const PAYMENTS: Row[] = [
  { paymentId: 'p-1', paidAt: '2026-11-02T01:07:00Z', targetEntity: 'QbFeeCharge', invoiceNo: 'QBF-2026-00001',
    subAccount: 'FEE_CREDIT', source: 'MANUAL', amount: 800 },
  { paymentId: 'p-2', paidAt: '2026-11-02T01:07:00Z', targetEntity: 'QbFeeCharge', invoiceNo: 'QBF-2026-00001',
    subAccount: 'CASH', source: 'CARD', amount: 300 }]

const SUMMARY: Row[] = [
  { groupKey: 'op-1', label: 'Tokyo Ops', lots: 2, granted: 4000, spent: 330, remaining: 3000, voided: 670 },
  { groupKey: '-', label: null, lots: 1, granted: 1000, spent: 0, remaining: 1000, voided: 0 }]
const GRANTS: Row[] = [
  { grantId: 'g-1', sponsorId: SPONSOR, company: 'Acme', operatorId: 'op-1', operatorName: 'Tokyo Ops',
    source: 'ONBOARDING', amount: 1000, spent: 330, remaining: 0, voidedAmount: 670, grantedAt: '2026-08-01T00:00:00Z',
    expiresAt: '2026-10-30T00:00:00Z', voidedAt: '2026-10-30T17:47:00Z' }]

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set(['qb.admin.finance.read', 'qb.fee.manage', 'qb.topup.bank.approve'])
  server.charges = [charge()]
  server.invoices = [invoice()]
  server.requests = [request()]
  server.onboarding = [{ enabled: true, grantedAt: null, amount: null, expiresAt: null }]
  server.sponsors = [{ userId: SPONSOR, company: 'Acme', contactName: 'Alice', reviewStatus: 'APPROVED' }]
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { filters?: { field: string;
    value: unknown }[]; count?: boolean } = {}) => {
    const one = (rows: Row[], key: string) => {
      const filter = options.filters?.find((f) => f.field === key)
      return filter ? rows.filter((row) => row[key] === filter.value) : rows
    }
    const items = id === TEMPLATE.sponsors ? server.sponsors
      : id === TEMPLATE.feeCharges ? server.charges
        : id === TEMPLATE.feeInvoices ? one(server.invoices, 'invoiceId')
          : id === TEMPLATE.feeRequests ? one(server.requests, 'requestId')
            : id === TEMPLATE.feeAccount || id === TEMPLATE.operatorFeeAccount ? ACCOUNT
              : id === TEMPLATE.feeAccountLots || id === TEMPLATE.operatorFeeAccountLots ? LOTS
                : id === TEMPLATE.feePayments || id === TEMPLATE.operatorFeePayments ? PAYMENTS
                  : id === TEMPLATE.feeCreditSummary ? SUMMARY
                    : id === TEMPLATE.feeCreditGrants ? GRANTS
                      : id === TEMPLATE.operatorFeeCreditOnboarding ? server.onboarding : []
    return { items, offset: 0, limit: 20, ...(options.count ? { total: items.length } : {}) }
  })
  server.runProcess.mockReset().mockResolvedValue({ requestId: REQUEST, kind: 'MANUAL', status: 'REVIEW',
    approvalRequestId: APPROVAL })
  server.get.mockReset().mockImplementation(async (path: string) => {
    if (path === '/api/dictionaries/{urn}') return [{ code: 'MONTHLY', label: 'Monthly fee' },
      { code: 'MANUAL', label: 'By hand' }, { code: 'VOID', label: 'Void an invoice' }, { code: 'PREPAID', label: 'Prepaid' },
      { code: 'POSTPAID', label: 'Postpaid' }, { code: 'OPEN', label: 'Open' }, { code: 'OVERDUE', label: 'Overdue' },
      { code: 'PAID', label: 'Paid' }, { code: 'REVIEW', label: 'Waiting for approval' }, { code: 'DONE', label: 'Done' },
      { code: 'REJECTED', label: 'Rejected' }]
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

describe('FeesPage', () => {
  it('lists the charges with their invoice, and the invoices by state', async () => {
    const { container } = renderAt('/qb/finance/fees', '/qb/finance/fees', <FeesPage />)
    const row = await screen.findByTestId('fee-charge-row')
    expect(within(row).getByTestId('fee-charge-kind')).toHaveTextContent('Monthly fee')
    expect(within(row).getByTestId('fee-charge-total')).toHaveTextContent('1,100')
    expect(within(row).getByText('QBF-2026-00001')).toBeInTheDocument()
    // The monthly fee is the platform's own charge, no administrator's.
    expect(within(row).getByText('The platform')).toBeInTheDocument()
    await expectAccessible(container)
    const user = userEvent.setup()
    await user.click(screen.getByTestId('tab-invoices'))
    const invoiceRow = await screen.findByTestId('fee-invoice-row')
    expect(invoiceRow).toHaveAttribute('data-status', 'OVERDUE')
    expect(within(invoiceRow).getByTestId('fee-invoice-no')).toHaveTextContent('QBF-2026-00002')
    expect(within(invoiceRow).getByText('Reminders: 1')).toBeInTheDocument()
    // Finance voids an invoice from its row: the dialog knows which.
    await user.click(within(invoiceRow).getByTestId('fee-invoice-void'))
    const dialog = await screen.findByTestId('fee-request-dialog')
    expect(dialog).toHaveAttribute('data-kind', 'VOID')
    expect(within(dialog).getByTestId('fee-request-invoice')).toHaveValue(INVOICE)
    expect(within(dialog).getByTestId('fee-request-submit')).toBeDisabled()
    await user.type(within(dialog).getByTestId('fee-request-reason'), 'Charged twice')
    await user.click(within(dialog).getByTestId('fee-request-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(FEE_PROCESS.request,
      { kind: 'VOID', invoiceId: INVOICE, reason: 'Charged twice' }))
    await waitFor(() => expect(screen.queryByTestId('fee-request-dialog')).not.toBeInTheDocument())
  })

  it('records a fee by hand for another administrator, and shows the server\'s refusal', async () => {
    server.runProcess.mockRejectedValue(new ApiError(422, { detail: 'refused', violations: [
      { field: null, ruleCode: 'QB_FEE_SPONSOR', message: 'The Sponsor is not approved.' }] }))
    renderAt('/qb/finance/fees', '/qb/finance/fees', <FeesPage />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('fee-new-manual'))
    const dialog = await screen.findByTestId('fee-request-dialog')
    expect(dialog).toHaveAttribute('data-kind', 'MANUAL')
    expect(within(dialog).getByTestId('fee-request-submit')).toBeDisabled()
    await user.click(within(dialog).getByTestId('fee-request-sponsor'))
    await user.click(await screen.findByRole('option', { name: /Acme/ }))
    await user.type(within(dialog).getByTestId('fee-request-amount'), '5,000')
    await user.type(within(dialog).getByTestId('fee-request-reason'), 'Extra support')
    await user.click(within(dialog).getByTestId('fee-request-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(FEE_PROCESS.request,
      { kind: 'MANUAL', sponsorId: SPONSOR, amount: 5000, reason: 'Extra support' }))
    expect(await screen.findByTestId('fee-request-problem')).toHaveTextContent('not approved')
  })

  it('asks for platform fee Kudos granted by hand, with their days of validity', async () => {
    renderAt('/qb/finance/fees', '/qb/finance/fees', <FeesPage />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('fee-new-credit'))
    const dialog = await screen.findByTestId('fee-request-dialog')
    expect(dialog).toHaveAttribute('data-kind', 'CREDIT')
    await user.click(within(dialog).getByTestId('fee-request-sponsor'))
    await user.click(await screen.findByRole('option', { name: /Acme/ }))
    await user.type(within(dialog).getByTestId('fee-request-amount'), '3000')
    await user.type(within(dialog).getByTestId('fee-request-valid-days'), '0')
    await user.type(within(dialog).getByTestId('fee-request-reason'), 'Event partner')
    await user.click(within(dialog).getByTestId('fee-request-submit'))
    // Out of 1 to 999: not sent.
    expect(server.runProcess).not.toHaveBeenCalled()
    await user.clear(within(dialog).getByTestId('fee-request-valid-days'))
    await user.type(within(dialog).getByTestId('fee-request-valid-days'), '30')
    await user.click(within(dialog).getByTestId('fee-request-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(FEE_PROCESS.request,
      { kind: 'CREDIT', sponsorId: SPONSOR, amount: 3000, reason: 'Event partner', validDays: 30 }))
  })

  it('shows headquarters the grants summed and each lot, read only', async () => {
    const { container } = renderAt('/qb/finance/fees?tab=grants', '/qb/finance/fees', <FeesPage />)
    const sums = await screen.findAllByTestId('fee-grant-sum-row')
    expect(sums.map((row) => row.dataset.key)).toEqual(['op-1', '-'])
    expect(sums[0]).toHaveTextContent('Tokyo Ops')
    expect(sums[1]).toHaveTextContent('No operator')
    const lot = await screen.findByTestId('fee-grant-row')
    expect(lot).toHaveTextContent('670')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.feeCreditSummary, expect.objectContaining({
      params: { groupBy: 'OPERATOR' } }))
    await userEvent.setup().click(screen.getByTestId('fee-grants-by-SOURCE'))
    await waitFor(() => expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.feeCreditSummary,
      expect.objectContaining({ params: { groupBy: 'SOURCE' } })))
    await expectAccessible(container)
  })

  it('shows the requests to the approvers only, and opens one for the decision', async () => {
    const { container } = renderAt('/qb/finance/fees?tab=requests', '/qb/finance/fees', <FeesPage />)
    const row = await screen.findByTestId('fee-request-row')
    expect(within(row).getByTestId('fee-request-kind')).toHaveTextContent('By hand')
    expect(within(row).getByTestId('fee-request-status')).toHaveTextContent('Waiting for approval')
    expect(within(row).getByTestId('fee-request-open')).toHaveAttribute('href', `/qb/finance/fees/requests/${REQUEST}`)
    await expectAccessible(container)
  })

  it('offers neither the requests nor the recording without the permissions', async () => {
    server.permissions = new Set(['qb.admin.finance.read'])
    renderAt('/qb/finance/fees?tab=requests', '/qb/finance/fees', <FeesPage />)
    // Asked for the requests, shown the charges: the tab is not theirs.
    await screen.findByTestId('fee-charge-row')
    expect(screen.queryByTestId('tab-requests')).not.toBeInTheDocument()
    expect(screen.queryByTestId('fee-new-manual')).not.toBeInTheDocument()
    expect(screen.queryByTestId('fee-new-void')).not.toBeInTheDocument()
    expect(server.runQuery).not.toHaveBeenCalledWith(TEMPLATE.feeRequests, expect.anything())
  })
})

describe('FeeRequestPage', () => {
  it('shows the request and the platform\'s decision panel while it waits', async () => {
    const { container } = renderAt(`/qb/finance/fees/requests/${REQUEST}`, '/qb/finance/fees/requests/:requestId',
      <FeeRequestPage />)
    expect(await screen.findByTestId('fee-request')).toHaveAttribute('data-status', 'REVIEW')
    expect(screen.getByTestId('fee-amount')).toHaveTextContent('500')
    expect(screen.getByTestId('fee-reason')).toHaveTextContent('Extra support')
    expect(screen.getByTestId('approval-panel')).toHaveAttribute('data-request', APPROVAL)
    await expectAccessible(container)
  })

  it('names the invoice a void request is about, and says when there is nothing to decide', async () => {
    server.requests = [request({ kind: 'VOID', amount: null, invoiceId: INVOICE, status: 'DONE',
      decidedAt: '2026-11-03T02:00:00Z' })]
    renderAt(`/qb/finance/fees/requests/${REQUEST}`, '/qb/finance/fees/requests/:requestId', <FeeRequestPage />)
    // The invoice is read after the request names it.
    expect(await within(await screen.findByTestId('fee-invoice')).findByText('QBF-2026-00002')).toBeInTheDocument()
    expect(screen.getByTestId('fee-not-pending')).toHaveTextContent('Done')
    expect(screen.queryByTestId('approval-panel')).not.toBeInTheDocument()
  })

  it('says when there is no such request', async () => {
    renderAt('/qb/finance/fees/requests/nope', '/qb/finance/fees/requests/:requestId', <FeeRequestPage />)
    expect(await screen.findByText('There is no such request.')).toBeInTheDocument()
  })
})

describe('SponsorFeeAccount', () => {
  it('shows the account, its lots in the order they are spent and the payments, read only', async () => {
    const view = renderAt('/here', '/here', <SponsorFeeAccount userId={SPONSOR} operator={false} />)
    const lots = await screen.findAllByTestId('fee-lot-row')
    expect(lots.map((row) => row.dataset.subAccount)).toEqual(['FEE_CREDIT', 'CASH'])
    expect(await screen.findAllByTestId('fee-payment-row')).toHaveLength(2)
    expect(screen.getByTestId('sponsor-fee-account')).toHaveTextContent('900')
    expect(screen.queryByRole('button', { name: /pay|grant/i })).not.toBeInTheDocument()
    const calls = server.runQuery.mock.calls.map(([template, options]) => [template, options?.params?.sponsorId])
    expect(calls).toContainEqual([TEMPLATE.feeAccount, SPONSOR])
    expect(calls).toContainEqual([TEMPLATE.feePayments, SPONSOR])
    await expectAccessible(view.container)
  })

  it('reads an operator\'s own Sponsor through the operator\'s templates', async () => {
    renderAt('/here', '/here', <SponsorFeeAccount userId={SPONSOR} operator />)
    await screen.findAllByTestId('fee-lot-row')
    const templates = server.runQuery.mock.calls.map(([template]) => template)
    expect(templates).toEqual(expect.arrayContaining([TEMPLATE.operatorFeeAccount, TEMPLATE.operatorFeeAccountLots,
      TEMPLATE.operatorFeePayments]))
    expect(templates).not.toContain(TEMPLATE.feeAccount)
    // Without the lead's permission: no confirmation.
    expect(screen.queryByTestId('sponsor-fee-onboarding')).not.toBeInTheDocument()
  })

  it('lets an operator confirm its Sponsor for the onboarding platform fee Kudos', async () => {
    server.permissions = new Set(['qb.operator.use', 'qb.fee-credit.onboarding.confirm'])
    server.runProcess.mockImplementation(async () => {
      server.onboarding = [{ enabled: true, grantedAt: '2026-11-09T01:00:00Z', amount: 1000,
        expiresAt: '2027-02-07T01:00:00Z' }]
      return { grantId: 'g-9', granted: true, amount: 1000, expiresAt: '2027-02-07T01:00:00Z' }
    })
    const view = renderAt('/here', '/here', <SponsorFeeAccount userId={SPONSOR} operator />)
    const confirm = await screen.findByTestId('fee-onboarding-confirm')
    await expectAccessible(view.container)
    await userEvent.setup().click(confirm)
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(FEE_PROCESS.onboarding, { sponsorId: SPONSOR }))
    expect(await screen.findByTestId('fee-onboarding-granted')).toHaveTextContent('1,000')
  })

  it('says when the onboarding is switched off, and shows the server\'s refusal', async () => {
    server.permissions = new Set(['qb.operator.use', 'qb.fee-credit.onboarding.confirm'])
    server.onboarding = [{ enabled: false, grantedAt: null, amount: null, expiresAt: null }]
    const view = renderAt('/here', '/here', <SponsorFeeAccount userId={SPONSOR} operator />)
    expect(await screen.findByTestId('fee-onboarding-confirm')).toBeDisabled()
    expect(screen.getByTestId('sponsor-fee-onboarding')).toHaveTextContent('switched off')
    view.unmount()
    server.onboarding = [{ enabled: true, grantedAt: null, amount: null, expiresAt: null }]
    server.runProcess.mockRejectedValue(new ApiError(422, { detail: 'refused', violations: [
      { field: null, ruleCode: 'QB_FEE_CREDIT_LEAD', message: 'Only a lead confirms.' }] }))
    renderAt('/here', '/here', <SponsorFeeAccount userId={SPONSOR} operator />)
    await userEvent.setup().click(await screen.findByTestId('fee-onboarding-confirm'))
    expect(await screen.findByTestId('fee-onboarding-problem')).toHaveTextContent('Only a lead')
  })
})
