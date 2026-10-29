import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE, Toaster } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import SettlementsPage from './SettlementsPage'
import { PERMISSION, SETTLEMENT_PROCESS, TEMPLATE } from './ids'
import { messages } from './messages'

const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
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
  }
})

const row = { settlementId: 's1', operatorId: 'op-1', operatorName: 'Kyushu Partners', month: '2026-11',
  revenue: '22', platformShare: '0.2', operatorShare: '17', prospectingCost: '0', carriedIn: '0', payable: '17',
  carriedOut: '0', status: 'ISSUED', documentNo: 's1', documentRunId: 'run-1', paidOn: null }

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.runQuery.mockReset().mockImplementation(async (_id: string, options: { count?: boolean } = {}) =>
    ({ items: [row], offset: 0, limit: 20, ...(options.count ? { total: 1 } : {}) }))
  server.runProcess.mockReset().mockResolvedValue({ settlementId: 's1', status: 'PAYING', approvalRequestId: 'r1' })
  server.get.mockReset().mockImplementation(async (path: string) => {
    if (path === '/api/dictionaries/{urn}') return [{ code: 'ISSUED', label: 'Issued' }]
    throw new ApiError(404, { detail: 'Not found' })
  })
})

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter><SettlementsPage /></MemoryRouter>
        <Toaster />
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('the settlements', () => {
  it('lets finance record a payment for approval', async () => {
    server.permissions = new Set([PERMISSION.financeRead, PERMISSION.settlementPay])
    const { container } = renderPage()
    const settlement = await screen.findByTestId('settlement-row')
    expect(settlement).toHaveTextContent('Kyushu Partners')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.operatorSettlements, expect.anything())
    await expectAccessible(container)
    const user = userEvent.setup()
    await user.click(within(settlement).getByTestId('settlement-pay'))
    const dialog = await screen.findByTestId('pay-dialog')
    await user.type(within(dialog).getByTestId('pay-reference'), ' OP-0001 ')
    await user.click(within(dialog).getByTestId('pay-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(SETTLEMENT_PROCESS.pay,
      expect.objectContaining({ settlementId: 's1', bankReference: 'OP-0001' })))
  })

  it('shows an operator its own, without paying or downloading', async () => {
    server.permissions = new Set([PERMISSION.operatorUse])
    renderPage()
    const settlement = await screen.findByTestId('settlement-row')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.ownSettlements, expect.anything())
    expect(within(settlement).queryByTestId('settlement-pay')).not.toBeInTheDocument()
    expect(within(settlement).queryByTestId('settlement-download')).not.toBeInTheDocument()
  })
})
