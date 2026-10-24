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
import CashRewardsPage from './CashRewardsPage'
import RedemptionsPage from './RedemptionsPage'
import { PROCESS, TEMPLATE } from './ids'
import { messages } from './messages'

type Row = Record<string, unknown>

/** The server as the cash rewards page sees it through `@jabiz/admin`. */
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
  }
})

const SPONSOR = '0192f0a0-0000-7000-8000-00000000aa01'
const TAKER = '0192f0a0-0000-7000-8000-00000000bb01'

function reward(change: Row = {}): Row {
  return { cashRewardId: 'c1', code: 'C-ABCD1234', attemptId: 'a1', publicationId: 'p1', publicationName: 'Coffee week',
    sponsorId: SPONSOR, sponsorCompany: 'Acme', userId: TAKER, takerName: 'Mei', amount: 100, status: 'DISPUTED',
    dueAt: '2026-10-20T01:00:00Z', payBy: '2026-11-03T01:00:00Z', remindedAt: null, markedPaidAt: '2026-10-21T01:00:00Z',
    markedPaidNote: 'Receipt 17', confirmBy: '2026-10-28T01:00:00Z', confirmedAt: null, disputedAt: '2026-10-22T01:00:00Z',
    disputeNote: 'I got 50, not 100', overdueAt: null, resolvedAt: null, resolution: null, resolveNote: null, ...change }
}

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set(['qb.admin.publication.read', 'qb.cash.resolve'])
  server.rows = [reward()]
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { count?: boolean } = {}) => {
    if (id !== TEMPLATE.cashRewards && id !== TEMPLATE.redemptions) return { items: [], offset: 0, limit: 20 }
    return { items: server.rows, offset: 0, limit: 20, ...(options.count ? { total: server.rows.length } : {}) }
  })
  server.runProcess.mockReset().mockResolvedValue({ cashRewardId: 'c1', status: 'CONFIRMED', revision: 4 })
  server.get.mockReset().mockImplementation(async (path: string) => {
    if (path === '/api/dictionaries/{urn}') return [{ code: 'DUE', label: 'To be paid by the sponsor' },
      { code: 'DISPUTED', label: 'Disputed' }, { code: 'CONFIRMED', label: 'Confirmed' },
      { code: 'CLAIMED', label: 'Claimed, awaiting the sponsor' }, { code: 'REDEEMED', label: 'Collected' }]
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

describe('CashRewardsPage', () => {
  it('lists the cash rewards with both sides and the taker\'s note, and decides a disputed one', async () => {
    const { container } = renderAt('/qb/cash-rewards', '/qb/cash-rewards', <CashRewardsPage />)
    const row = await screen.findByTestId('cash-row')
    expect(row).toHaveTextContent('C-ABCD1234')
    expect(row).toHaveTextContent('Acme')
    expect(row).toHaveTextContent('Mei')
    expect(within(row).getByTestId('cash-row-dispute')).toHaveTextContent('Taker: I got 50, not 100')
    await waitFor(() => expect(within(row).getByTestId('cash-row-status')).toHaveTextContent('Disputed'))
    await expectAccessible(container)
    await userEvent.click(within(row).getByTestId('cash-row-resolve'))
    const dialog = await screen.findByTestId('cash-resolve-dialog')
    expect(dialog).toHaveTextContent('I got 50, not 100')
    await userEvent.click(within(dialog).getByTestId('cash-resolution-UNPAID'))
    await userEvent.type(within(dialog).getByTestId('cash-resolve-note'), 'The sponsor agrees')
    await userEvent.click(within(dialog).getByTestId('cash-resolve-send'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROCESS.cashResolve,
      { cashRewardId: 'c1', resolution: 'UNPAID', note: 'The sponsor agrees' }))
    await waitFor(() => expect(screen.queryByTestId('cash-resolve-dialog')).toBeNull())
  })

  it('offers no decision without the permission, and filters by state on the server', async () => {
    server.permissions = new Set(['qb.admin.publication.read'])
    renderAt('/qb/cash-rewards?status=DUE', '/qb/cash-rewards', <CashRewardsPage />)
    await screen.findByTestId('cash-row')
    expect(screen.queryByTestId('cash-row-resolve')).toBeNull()
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.cashRewards, expect.objectContaining({
      filters: [{ field: 'status', op: 'eq', value: 'DUE' }] }))
  })
})

describe('RedemptionsPage (Q22b)', () => {
  function prize(change: Row = {}): Row {
    return { redemptionId: 'r1', code: 'C-ABCD1234', attemptId: 'a1', publicationId: 'p1', publicationName: 'Coffee week',
      sponsorId: SPONSOR, sponsorCompany: 'Acme', userId: TAKER, takerName: 'Mei', amount: 100,
      description: 'A coffee at our counter', status: 'CLAIMED', dueAt: '2026-10-20T01:00:00Z',
      expiresAt: '2026-11-19T01:00:00Z', claimKind: 'PRINTED', claimedAt: '2026-10-21T01:00:00Z',
      confirmBy: '2026-10-21T01:10:00Z', redeemedAt: null, redeemedBy: null, voidedBy: null, voidNote: null, ...change }
  }

  it('lists the prizes with both sides and voids one not collected yet, with a note', async () => {
    server.permissions = new Set(['qb.admin.publication.read', 'qb.redemption.void'])
    server.rows = [prize(), prize({ redemptionId: 'r2', code: 'C-00000002', status: 'REDEEMED',
      redeemedAt: '2026-10-21T01:05:00Z', claimKind: 'ROTATING' })]
    server.runProcess.mockReset().mockResolvedValue({ redemptionId: 'r1', status: 'VOID', revision: 3 })
    const { container } = renderAt('/qb/redemptions', '/qb/redemptions', <RedemptionsPage />)
    const rows = await screen.findAllByTestId('redeem-row')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('C-ABCD1234')
    expect(rows[0]).toHaveTextContent('Acme')
    expect(rows[0]).toHaveTextContent('A coffee at our counter')
    await waitFor(() => expect(within(rows[0]).getByTestId('redeem-row-status')).toHaveTextContent('Claimed, awaiting the sponsor'))
    expect(within(rows[1]).queryByTestId('redeem-row-void')).toBeNull()
    await expectAccessible(container)
    await userEvent.click(within(rows[0]).getByTestId('redeem-row-void'))
    const dialog = await screen.findByTestId('redeem-void-dialog')
    expect(dialog).toHaveTextContent('Mei won "A coffee at our counter" at Acme')
    await userEvent.type(within(dialog).getByTestId('redeem-void-note'), 'Duplicate account')
    await userEvent.click(within(dialog).getByTestId('redeem-void-send'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROCESS.redemptionVoid,
      { redemptionId: 'r1', note: 'Duplicate account' }))
    await waitFor(() => expect(screen.queryByTestId('redeem-void-dialog')).toBeNull())
  })

  it('offers no void without the permission', async () => {
    server.permissions = new Set(['qb.admin.publication.read'])
    server.rows = [prize()]
    renderAt('/qb/redemptions', '/qb/redemptions', <RedemptionsPage />)
    await screen.findByTestId('redeem-row')
    expect(screen.queryByTestId('redeem-row-void')).toBeNull()
  })
})
