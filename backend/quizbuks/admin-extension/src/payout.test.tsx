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
import AttemptsPage from './AttemptsPage'
import { PROCESS, TEMPLATE } from './ids'
import { messages } from './messages'
import { attemptFilterOf } from './payout-api'
import { PayoutPanel } from './PayoutPanel'

type Row = Record<string, unknown>

const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  templates: new Map<string, Row[]>(),
  preview: {} as Row,
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

const TAKER = '0192f0a0-0000-7000-8000-00000000aa01'
const PUBLICATION = '0192f0a0-0000-7000-8000-00000000bb01'

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set(['qb.admin.finance.read', 'qb.admin.users.read', 'qb.transfer.now'])
  server.templates = new Map<string, Row[]>([
    [TEMPLATE.takerKudos, [{ userId: TAKER, attempts: 4, completed: 3, earned: 1200, pendingRewards: 0,
      balance: 1200, inTransfer: 0, transferred: 500 }]],
    [TEMPLATE.attempts, [{ attemptId: 'a1', userId: TAKER, publicationId: PUBLICATION, name: 'Capitals',
      status: 'SUBMITTED', startedAt: '2026-10-22T01:00:00Z', submittedAt: '2026-10-22T01:03:00Z', score: 5,
      fullScore: 5, passed: true, rewardStatus: 'PAID' }]],
    [TEMPLATE.attemptRewards, [{ attemptId: 'a1', reward: 100, fee: 10 }]],
  ])
  server.preview = { transfers: true, skip: null, amount: 1200, fee: 0, net: 1200, review: false, balance: 1200,
    held: 0 }
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { count?: boolean } = {}) => {
    const items = server.templates.get(id) ?? []
    return { items, offset: 0, limit: 20, ...(options.count ? { total: items.length } : {}) }
  })
  server.runProcess.mockReset().mockImplementation(async (name: string) => {
    if (name === PROCESS.transferPreview) return server.preview
    return { transferId: 'tr1', status: 'SENDING', reason: null, approvalRequestId: null }
  })
  server.get.mockReset().mockImplementation(async (path: string) => {
    if (path === '/api/dictionaries/{urn}') return []
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

describe('PayoutPanel', () => {
  it('shows the Kudos and transfers now after asking', async () => {
    const user = userEvent.setup()
    renderAt('/', '/', <PayoutPanel userId={TAKER} />)
    expect(await screen.findByTestId('kudos-earned')).toHaveTextContent('1,200')
    expect(await screen.findByTestId('payout-status')).toHaveTextContent('The daily transfer would send')
    await expectAccessible()
    await user.click(screen.getByTestId('transfer-now'))
    await user.click(await screen.findByRole('button', { name: 'Transfer' }))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROCESS.transferNow, { userId: TAKER }))
    expect(await screen.findByTestId('transfer-now-outcome')).toHaveTextContent('Transfer started')
  })

  it('says why nothing would be sent, and offers no transfer', async () => {
    server.preview = { transfers: false, skip: 'ACCOUNT_NOT_ENABLED', amount: null, fee: null, net: null,
      review: false, balance: 1200, held: 300 }
    renderAt('/', '/', <PayoutPanel userId={TAKER} />)
    expect(await screen.findByTestId('payout-reason')).toHaveTextContent('Stripe payout account is not enabled')
    expect(screen.getByTestId('payout-status')).toHaveTextContent('300')
    expect(screen.queryByTestId('transfer-now')).toBeNull()
  })

  it('offers no transfer without the permission', async () => {
    server.permissions = new Set(['qb.admin.finance.read'])
    renderAt('/', '/', <PayoutPanel userId={TAKER} />)
    expect(await screen.findByTestId('payout-status')).toBeInTheDocument()
    expect(screen.queryByTestId('transfer-now')).toBeNull()
  })
})

describe('AttemptsPage', () => {
  it('lists the attempts with the taker\'s Kudos for finance', async () => {
    renderAt('/qb/attempts', '/qb/attempts', <AttemptsPage />)
    const row = await screen.findByTestId('attempt-row')
    expect(row).toHaveTextContent('Capitals')
    expect(row).toHaveTextContent('5 / 5')
    expect(await within(row).findByTestId('attempt-kudos')).toHaveTextContent('90')
    await expectAccessible()
  })

  it('shows no Kudos to a content administrator', async () => {
    server.permissions = new Set(['qb.admin.users.read'])
    renderAt('/qb/attempts', '/qb/attempts', <AttemptsPage />)
    const row = await screen.findByTestId('attempt-row')
    expect(within(row).queryByTestId('attempt-kudos')).toBeNull()
    expect(server.runQuery.mock.calls.some(([id]) => id === TEMPLATE.attemptRewards)).toBe(false)
  })

  it('reads its filter from the address', () => {
    expect(attemptFilterOf(new URLSearchParams(`user=${TAKER}&status=SUBMITTED&reward=bad value`))).toEqual({
      userId: TAKER, publicationId: null, status: 'SUBMITTED', rewardStatus: null })
  })
})
