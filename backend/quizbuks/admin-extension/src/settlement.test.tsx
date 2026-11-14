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
  sessionFetch: vi.fn(),
  rows: {} as Record<string, unknown[]>,
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { GET: server.get },
    unwrap: async (call: Promise<unknown>) => call,
    runQuery: server.runQuery,
    runProcess: server.runProcess,
    sessionFetch: server.sessionFetch,
    useAuth: () => ({ can: (p: string) => server.permissions.has(p) }),
  }
})

const row = { settlementId: 's1', operatorId: 'op-1', operatorName: 'Kyushu Partners', month: '2026-11',
  plan: '2', fixedFee: '20000', giftPaid: '4000', giftRecovered: '700', giftCarriedOut: '300', platformShare: '0.2', operatorShare: '17', prospectingCost: '0', creditNoteDeduction: '3', carriedIn: '0', payable: '17',
  carriedOut: '0', status: 'ISSUED', documentNo: 's1', documentRunId: 'run-1', paidOn: null }

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

const line = { lineId: 'l1', settlementId: 's1', lineKind: 'CHARGE', sponsorId: 'sp-1', sponsorName: 'Acme Quiz',
  feeKind: 'DISTRIBUTION', period: '2026-10', late: true, amount: '1100', excluded: '100',
  occurredAt: '2026-11-02T01:00:00Z' }

beforeEach(() => {
  server.rows = {
    [TEMPLATE.settlementsWaiting]: [
      { operatorId: 'op-1', operatorName: 'Kyushu Partners', month: '2026-10', steps: 'DISTRIBUTION,INVOICE' },
      { operatorId: 'op-2', operatorName: 'Kanto Partners', month: '2026-09', steps: '' },
    ],
    [TEMPLATE.adminSettlementLines]: [line, { ...line, lineId: 'l2', lineKind: 'GIFT', late: false }],
    [TEMPLATE.ownSettlementLines]: [line],
  }
  server.sessionFetch.mockReset().mockResolvedValue(new Response('lineId\r\n', {
    headers: { 'Content-Disposition': 'attachment; filename="lines.csv"' } }))
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { count?: boolean } = {}) => {
    const items = server.rows[id] ?? [row]
    return { items, offset: 0, limit: 20, ...(options.count ? { total: items.length } : {}) }
  })
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
  it('pays only an ISSUED one: a CREDITED month went to the operator’s account', async () => {
    server.permissions = new Set([PERMISSION.financeRead, PERMISSION.settlementPay])
    server.runQuery.mockImplementation(async (_id: string, options: { count?: boolean } = {}) =>
      ({ items: [{ ...row, status: 'CREDITED' }], offset: 0, limit: 20, ...(options.count ? { total: 1 } : {}) }))
    renderPage()
    const settlement = await screen.findByTestId('settlement-row')
    expect(within(settlement).queryByTestId('settlement-pay')).not.toBeInTheDocument()
  })


  it('lets finance record a payment for approval', async () => {
    server.permissions = new Set([PERMISSION.financeRead, PERMISSION.settlementPay])
    const { container } = renderPage()
    const settlement = await screen.findByTestId('settlement-row')
    expect(settlement).toHaveTextContent('Kyushu Partners')
    // Finance sees yen, with the plan's fixed fee.
    expect(settlement).toHaveTextContent(/20,000/)
    expect(settlement).toHaveTextContent(/JPY|¥|￥/)
    // F-M7: what credit notes took off the share.
    expect(within(settlement).getByTestId('settlement-credit-note-deduction')).toHaveTextContent('3')
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
    // Q48e-2: the operator sees points, never a currency.
    expect(settlement).toHaveTextContent('17 Kudos')
    expect(settlement).toHaveTextContent('20,000 Kudos')
    expect(settlement).not.toHaveTextContent(/JPY|¥|￥/)
    expect(within(settlement).getByTestId('settlement-credit-note-deduction')).toHaveTextContent('3 Kudos')
    expect(within(settlement).queryByTestId('settlement-pay')).not.toBeInTheDocument()
    expect(within(settlement).queryByTestId('settlement-download')).not.toBeInTheDocument()
  })

  it('shows the granted credit paid, taken back and carried, and no commission column', async () => {
    server.permissions = new Set([PERMISSION.financeRead])
    renderPage()
    const settlement = await screen.findByTestId('settlement-row')
    expect(within(settlement).getByTestId('settlement-gift-paid')).toHaveTextContent('4,000')
    expect(within(settlement).getByTestId('settlement-gift-recovered')).toHaveTextContent('700')
    expect(within(settlement).getByTestId('settlement-gift-carried-out')).toHaveTextContent('300')
    expect(screen.getByRole('columnheader', { name: 'Paid with granted credit (not shared)' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Granted credit taken back' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Take-back carried to next month' })).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: /commission/i })).not.toBeInTheDocument()
  })

  it('shows an operator the same columns in points', async () => {
    server.permissions = new Set([PERMISSION.operatorUse])
    renderPage()
    const settlement = await screen.findByTestId('settlement-row')
    expect(within(settlement).getByTestId('settlement-gift-recovered')).toHaveTextContent('700 Kudos')
    expect(within(settlement).getByTestId('settlement-gift-carried-out')).toHaveTextContent('300 Kudos')
    expect(screen.queryByTestId('settlements-waiting')).not.toBeInTheDocument()
  })

  it('opens a month’s lines for finance, marks the late one and exports through the platform', async () => {
    server.permissions = new Set([PERMISSION.financeRead])
    renderPage()
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('settlement-lines-open'))
    const dialog = await screen.findByTestId('settlement-lines')
    const lines = await within(dialog).findAllByTestId('settlement-line')
    expect(lines).toHaveLength(2)
    expect(lines[0]).toHaveTextContent('Acme Quiz')
    expect(lines[0]).toHaveTextContent('Fee')
    expect(within(lines[0]).getByTestId('line-late')).toHaveTextContent('Late')
    expect(within(lines[1]).queryByTestId('line-late')).not.toBeInTheDocument()
    expect(lines[1]).toHaveTextContent('Paid with granted credit')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.adminSettlementLines,
      expect.objectContaining({ params: { settlementId: 's1' } }))
    await user.click(within(dialog).getByTestId('lines-export-csv'))
    await waitFor(() => expect(server.sessionFetch).toHaveBeenCalled())
    const [url, init] = server.sessionFetch.mock.calls[0]
    expect(url).toBe('/api/queries/qb.admin.settlement-lines/export?format=csv')
    expect(JSON.parse(init.body)).toEqual({ params: { settlementId: 's1' } })
    await expectAccessible(dialog)
  })

  it('opens the operator’s own lines from its own template', async () => {
    server.permissions = new Set([PERMISSION.operatorUse])
    renderPage()
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('settlement-lines-open'))
    const dialog = await screen.findByTestId('settlement-lines')
    expect(await within(dialog).findAllByTestId('settlement-line')).toHaveLength(1)
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.ownSettlementLines,
      expect.objectContaining({ params: { settlementId: 's1' } }))
    await user.click(within(dialog).getByTestId('lines-export-xlsx'))
    await waitFor(() => expect(server.sessionFetch).toHaveBeenCalled())
    expect(server.sessionFetch.mock.calls[0][0]).toBe('/api/queries/qb.operator.settlement-lines/export?format=xlsx')
  })

  it('lists the waiting settlements for finance with the steps they wait for', async () => {
    server.permissions = new Set([PERMISSION.financeRead])
    const { container } = renderPage()
    const rows = await screen.findAllByTestId('waiting-row')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('Kyushu Partners')
    expect(rows[0]).toHaveTextContent('2026-10')
    expect(rows[0]).toHaveTextContent('distribution fees / monthly invoices of postpaid fees')
    expect(rows[1]).toHaveTextContent('the settlement run has not finished')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.settlementsWaiting,
      expect.objectContaining({ params: expect.objectContaining({ zone: 'Asia/Tokyo' }) }))
    await expectAccessible(container)
  })

  it.each(['zh', 'ja'] as const)('is written in %s, with no key left over', async (language) => {
    await i18next.changeLanguage(language)
    try {
      server.permissions = new Set([PERMISSION.financeRead])
      renderPage()
      const user = userEvent.setup()
      await screen.findAllByTestId('waiting-row')
      await user.click(await screen.findByTestId('settlement-lines-open'))
      await screen.findAllByTestId('settlement-line')
      const text = document.body.textContent ?? ''
      expect(text).not.toMatch(/settlement\.(lines|waiting|gift)/)
      expect(text).not.toMatch(/後台|后台/)
    } finally {
      await i18next.changeLanguage('en')
    }
  })
})
