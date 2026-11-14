import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import { closureMessages } from './closure-messages'
import { minuteNow } from './closure-api'
import MoneyClosuresPage from './MoneyClosuresPage'
import { SponsorClosure } from './SponsorClosure'
import { TEMPLATE } from './ids'
import { messages } from './messages'

type Row = Record<string, unknown>

/** The server as the pages see it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  runQuery: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { GET: async () => ({ data: [] }) },
    unwrap: async (call: Promise<unknown>) => call,
    runQuery: server.runQuery,
    useAuth: () => ({ entry: 'admin', can: () => true }),
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
  }
})

const closure = (change: Row = {}): Row => ({ sponsorId: 's-1', status: 'WAITING', reasonCode: 'DEPOSIT_OPEN',
  erasedAt: '2026-11-01T00:00:00Z', lastRunAt: '2026-11-05T18:53:00Z', closedAt: null, alertedAt: null,
  owed: '0', slow: true, ...change })

let rows: Row[] = []

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  rows = [closure(), closure({ sponsorId: 's-2', status: 'CLOSED', reasonCode: 'FEE_OWED',
    closedAt: '2026-11-02T00:00:00Z', owed: '8100', slow: false })]
  server.runQuery.mockReset().mockImplementation(async (id: string, options: {
    params?: Row; filters?: Row[]; count?: boolean } = {}) => {
    let items: Row[] = []
    const params = options.params ?? {}
    if (id === TEMPLATE.moneyClosures) {
      items = rows.filter((row) => (!params.show || (params.show === 'OPEN' && row.status === 'WAITING')
        || (params.show === 'OWED' && row.status === 'CLOSED' && row.owed !== '0') || (params.show === 'SLOW' && row.slow))
        && (options.filters ?? []).every((f) => row[String(f.field)] === f.value))
    } else if (id === TEMPLATE.moneyClosureAmounts && rows.some((row) => row.sponsorId === params.sponsorId)) {
      items = [{ sponsorId: params.sponsorId, paidFromCredit: '1200', paidFromCash: '300', creditVoided: '500',
        owed: '8100', cashLeft: '0' }]
    }
    return { items, offset: 0, limit: 20, ...(options.count ? { total: items.length } : {}) }
  })
})

function renderAt(at: string, element: React.ReactNode, path = '*') {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={[at]}>
          <Routes><Route path={path} element={element} /></Routes>
        </MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('the wind-ups of deleted accounts (F-M15 b)', () => {
  it('has every text in every language', () => {
    const keys = (value: object, prefix = ''): string[] => Object.entries(value).flatMap(([key, child]) =>
      typeof child === 'object' && child !== null ? keys(child, `${prefix}${key}.`) : [`${prefix}${key}`])
    const english = keys(closureMessages.en)
    expect(keys(closureMessages.zh)).toEqual(english)
    expect(keys(closureMessages.ja)).toEqual(english)
    for (const language of ['en', 'zh', 'ja'] as const) {
      expect(JSON.stringify(closureMessages[language])).not.toMatch(/后台|管理画面|console/i)
    }
  })

  it('lists those not done by default, with the server\'s time to the minute, and filters by the address', async () => {
    const view = renderAt('/qb/finance/money-closures', <MoneyClosuresPage />)
    expect(await screen.findAllByTestId('closure-row')).toHaveLength(1)
    expect(screen.getByTestId('closure-slow')).toHaveTextContent('Taking long')
    const [, options] = server.runQuery.mock.calls.find(([id]) => id === TEMPLATE.moneyClosures)!
    expect(options.params.show).toBe('OPEN')
    expect(String(options.params.at)).toMatch(/:00\.000Z$/)
    await expectAccessible(view.container)
    view.unmount()
    renderAt('/qb/finance/money-closures?show=OWED', <MoneyClosuresPage />)
    const owed = await screen.findAllByTestId('closure-row')
    expect(owed.map((row) => row.dataset.sponsor)).toEqual(['s-2'])
    expect(owed[0]).toHaveTextContent('8,100')
  })

  it('shows every wind-up when asked, and changes the filter in the address', async () => {
    renderAt('/qb/finance/money-closures?show=ALL', <MoneyClosuresPage />)
    expect(await screen.findAllByTestId('closure-row')).toHaveLength(2)
    expect(screen.getByTestId('closures-export-csv')).toBeEnabled()
    await userEvent.setup().click(screen.getByTestId('closures-show'))
    await userEvent.setup().click(await screen.findByRole('option', { name: 'Taking long' }))
    await waitFor(() => expect(screen.getAllByTestId('closure-row')).toHaveLength(1))
  })

  it('shows one Sponsor\'s wind-up and its amounts, read only, and nothing without one', async () => {
    const view = renderAt('/here', <SponsorClosure userId="s-2" />)
    const block = await screen.findByTestId('sponsor-closure')
    await waitFor(() => expect(block).toHaveTextContent('8,100'))
    expect(block).toHaveTextContent('1,200')
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    await expectAccessible(view.container)
    view.unmount()
    renderAt('/here', <SponsorClosure userId="nobody" />)
    await waitFor(() => expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.moneyClosures, expect.anything()))
    expect(screen.queryByTestId('sponsor-closure')).not.toBeInTheDocument()
    expect(server.runQuery.mock.calls.filter(([id, options]) => id === TEMPLATE.moneyClosureAmounts
      && options?.params?.sponsorId === 'nobody')).toHaveLength(0)
  })

  it('asks for the time to the minute', () => {
    expect(minuteNow(new Date('2026-11-14T05:06:07.890Z'))).toBe('2026-11-14T05:06:00.000Z')
  })
})
