import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import ReferralsPage from './ReferralsPage'
import { PATH, TEMPLATE } from './ids'
import { messages } from './messages'

const server = vi.hoisted(() => ({
  runQuery: vi.fn(),
  get: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { GET: server.get },
    unwrap: async (call: Promise<unknown>) => call,
    runQuery: server.runQuery,
    useAuth: () => ({ can: () => true }),
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
  }
})

const SPONSOR = '0192f0a0-0000-7000-8000-00000000aa01'
const WANG = '0192f0a0-0000-7000-8000-00000000bb01'
const LI = '0192f0a0-0000-7000-8000-00000000bb02'

const rows = [
  { referralId: 'r1', publicationId: 'p1', publicationName: 'Autumn menu', publisherKind: 'SPONSOR',
    publisherId: SPONSOR, referrerId: WANG, refereeId: LI, status: 'REWARDED', referredAt: '2026-10-21T01:00:00Z',
    expiresAt: '2026-10-28T01:00:00Z', rewardMethod: 'PLATFORM', amount: 300, rewardedAt: '2026-10-22T01:00:00Z',
    fundsShort: false, voidReason: null },
  { referralId: 'r2', publicationId: 'p1', publicationName: 'Autumn menu', publisherKind: 'SPONSOR',
    publisherId: SPONSOR, referrerId: WANG, refereeId: null, status: 'REFERRED', referredAt: '2026-10-25T01:00:00Z',
    expiresAt: '2026-11-01T01:00:00Z', rewardMethod: 'PLATFORM', amount: 300, rewardedAt: null, fundsShort: true,
    voidReason: null },
]

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.runQuery.mockReset().mockImplementation(async (id: string) => {
    if (id !== TEMPLATE.referrals) return { items: [], offset: 0, limit: 20 }
    return { items: rows, offset: 0, limit: 20, total: rows.length }
  })
  server.get.mockReset().mockImplementation(async (path: string) => {
    if (path === '/api/dictionaries/{urn}') return [{ code: 'REFERRED', label: 'Referred' },
      { code: 'REWARDED', label: 'Rewarded' }]
    throw new ApiError(404, { detail: 'Not found' })
  })
})

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={[PATH.referrals]}>
          <Routes>
            <Route path={PATH.referrals} element={<ReferralsPage />} />
          </Routes>
        </MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('the referrals page (Q70)', () => {
  it('lists every referral with its people, state and reward, and narrows to one referrer', async () => {
    renderPage()
    const list = await screen.findAllByTestId('referral-row')
    expect(list.map((row) => row.dataset.status)).toEqual(['REWARDED', 'REFERRED'])
    expect(within(list[0]).getAllByTestId('user-name').map((e) => e.textContent)).toContain(LI)
    expect(list[1]).toHaveTextContent("Waiting for the sponsor's wallet")
    await expectAccessible()
    await userEvent.click(within(list[0]).getByTestId('referral-only-referrer'))
    await waitFor(() => expect(server.runQuery).toHaveBeenLastCalledWith(TEMPLATE.referrals,
      expect.objectContaining({ params: { referrerId: WANG } })))
    expect(screen.getByTestId('referral-clear-referrer')).toBeInTheDocument()
  })
})
