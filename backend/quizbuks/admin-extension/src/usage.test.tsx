import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import type { ReactNode } from 'react'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import { SponsorAssets } from './SponsorAssets'
import UsagePage from './UsagePage'
import { DICTIONARY, isByteMeter, PATH, PERMISSION, TEMPLATE } from './ids'
import { messages } from './messages'
import { formatUsage, usageParams, validRange } from './usage-api'

type Row = Record<string, unknown>
type Options = { params?: Row; offset?: number; limit?: number; count?: boolean; sorts?: unknown[] }

/** The server as the usage page sees it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  rows: [] as Record<string, unknown>[],
  get: vi.fn(),
  post: vi.fn(),
  runQuery: vi.fn(),
  sessionFetch: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { GET: server.get, POST: server.post },
    unwrap: async (call: Promise<unknown>) => call,
    runQuery: server.runQuery,
    sessionFetch: server.sessionFetch,
    useAuth: () => ({ can: (p: string) => server.permissions.has(p) }),
  }
})

const ANN = '0192f0a0-0000-7000-8000-00000000c001'
const BOB = '0192f0a0-0000-7000-8000-00000000c002'
const MB = 1_048_576

const row = (change: Row = {}): Row => ({ sponsorId: ANN, company: 'Ann Co', month: '2026-11', meter: 'storage.bytes',
  days: 6, average: 120 * MB, peak: 600 * MB, latest: 610 * MB, ...change })

beforeAll(async () => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-11-07T03:00:00Z'))
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})
afterAll(() => {
  vi.useRealTimers()
})

beforeEach(() => {
  server.permissions = new Set([PERMISSION.publicationRead])
  server.rows = [row(), row({ meter: 'storage.files', average: 12, peak: 14, latest: 14 }),
    row({ sponsorId: BOB, company: null, meter: 'assets.count', average: 3, peak: 3, latest: 3 })]
  server.runQuery.mockReset().mockImplementation(async (id: string, options: Options) => {
    if (id !== TEMPLATE.usage) return { items: [], offset: 0, limit: 1 }
    const params = options.params ?? {}
    const all = server.rows.filter((r) => (!params.sponsorId || r.sponsorId === params.sponsorId)
      && (!params.meter || r.meter === params.meter))
    const offset = options.offset ?? 0
    const limit = options.limit ?? 100
    return { items: all.slice(offset, offset + limit), offset, limit, ...(options.count ? { total: all.length } : {}) }
  })
  server.post.mockReset().mockResolvedValue({ items: [] })
  server.sessionFetch.mockReset().mockResolvedValue(new Response('sponsorId,month\r\n', {
    status: 200, headers: { 'Content-Disposition': 'attachment; filename="qb.admin.usage.csv"' } }))
  server.get.mockReset().mockImplementation(async (path: string, options: { params: { path: Record<string, string> } }) => {
    if (path === '/api/dictionaries/{urn}' && options.params.path.urn === DICTIONARY.usageMeter) {
      return [{ code: 'storage.bytes', label: 'Storage used' }, { code: 'storage.files', label: 'Files' },
        { code: 'assets.count', label: 'Assets' }]
    }
    return []
  })
})

function Where() {
  const location = useLocation()
  return <p data-testid="where">{location.pathname}{location.search}</p>
}

function renderAt(path: string, route: string, element: ReactNode) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path={route} element={<>{element}<Where /></>} />
            <Route path="*" element={<Where />} />
          </Routes>
        </MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

const usageCalls = () => server.runQuery.mock.calls.filter(([id]) => id === TEMPLATE.usage)
  .map(([, options]) => options as Options)

describe('the usage helpers', () => {
  it('turns months into days and tells bytes from counts', () => {
    expect(usageParams({ from: '2026-02', to: '2026-02' })).toEqual({ from: '2026-02-01', to: '2026-02-28' })
    expect(usageParams({ from: '2026-01', to: '2026-12', sponsorId: ANN, meter: 'ai.tokens' }))
      .toEqual({ from: '2026-01-01', to: '2026-12-31', sponsorId: ANN, meter: 'ai.tokens' })
    expect(validRange({ from: '2026-03', to: '2026-02' })).toBe(false)
    expect(validRange({ from: '', to: '2026-02' })).toBe(false)
    expect(validRange({ from: '2026-02', to: '2026-02' })).toBe(true)
    expect(isByteMeter('storage.bytes')).toBe(true)
    expect(isByteMeter('assets.text-bytes')).toBe(true)
    expect(isByteMeter('storage.files')).toBe(false)
    expect(formatUsage(1.5 * MB, true, 'en')).toBe('1.5 MB')
    expect(formatUsage(12345, false, 'en')).toBe('12,345')
  })
})

describe('UsagePage', () => {
  it('lists the sponsors’ months by meter, bytes in MB, read only', async () => {
    renderAt(PATH.usage, PATH.usage, <UsagePage />)
    const rows = await screen.findAllByTestId('usage-row')
    expect(rows).toHaveLength(3)
    expect(usageCalls()[0]).toMatchObject({ params: { from: '2026-11-01', to: '2026-11-30' }, count: true })
    expect(rows[0]).toHaveTextContent('Ann Co')
    expect(rows[0]).toHaveTextContent('2026-11')
    await waitFor(() => expect(rows[0]).toHaveTextContent('Storage used'))
    expect(rows[0]).toHaveTextContent('120.0 MB')
    expect(rows[0]).toHaveTextContent('600.0 MB')
    expect(rows[0]).toHaveTextContent('610.0 MB')
    expect(rows[1]).toHaveTextContent('12')
    // A sponsor without an onboarding name shows its id; each leads to the sponsor's page.
    expect(within(rows[2]).getByTestId('usage-sponsor')).toHaveTextContent(BOB)
    expect(within(rows[0]).getByTestId('usage-sponsor')).toHaveAttribute('href', PATH.sponsor(ANN))
    // Nothing to change: the only buttons export.
    expect(screen.queryByRole('button', { name: /save|delete|edit|change/i })).toBeNull()
    await expectAccessible()
  })

  it('asks another range and meter, refuses a range backwards, and exports every row', async () => {
    renderAt(PATH.usage, PATH.usage, <UsagePage />)
    await screen.findAllByTestId('usage-row')
    const fromInput = screen.getByTestId('usage-from')
    await userEvent.clear(fromInput)
    await userEvent.type(fromInput, '2026-09')
    await waitFor(() => expect(usageCalls().at(-1)!.params).toMatchObject({ from: '2026-09-01', to: '2026-11-30' }))
    await userEvent.click(screen.getByTestId('usage-meter'))
    await userEvent.click(await screen.findByRole('option', { name: 'Files' }))
    await waitFor(() => expect(usageCalls().at(-1)!.params).toMatchObject({ meter: 'storage.files' }))
    await waitFor(() => expect(screen.getAllByTestId('usage-row')).toHaveLength(1))

    await userEvent.click(screen.getByTestId('usage-export-csv'))
    await waitFor(() => expect(server.sessionFetch).toHaveBeenCalled())
    const [url, init] = server.sessionFetch.mock.calls[0]
    expect(url).toBe('/api/queries/qb.admin.usage/export?format=csv')
    expect(JSON.parse(init.body)).toEqual({ params: { from: '2026-09-01', to: '2026-11-30', meter: 'storage.files' } })

    await userEvent.clear(fromInput)
    await userEvent.type(fromInput, '2026-12')
    expect(await screen.findByTestId('usage-invalid')).toBeInTheDocument()
    expect(screen.getByTestId('usage-export-csv')).toBeDisabled()
    await expectAccessible()
  })

  it('shows one sponsor from the sponsor’s page, and every sponsor again', async () => {
    renderAt(`${PATH.usage}?sponsor=${ANN}`, PATH.usage, <UsagePage />)
    await waitFor(() => expect(screen.getAllByTestId('usage-row')).toHaveLength(2))
    expect(usageCalls()[0].params).toMatchObject({ sponsorId: ANN })
    await userEvent.click(screen.getByTestId('usage-show-all'))
    await waitFor(() => expect(screen.getAllByTestId('usage-row')).toHaveLength(3))
    expect(screen.getByTestId('where')).toHaveTextContent(/^\/qb\/usage$/)
  })

  it('says when no usage was recorded', async () => {
    server.rows = []
    renderAt(PATH.usage, PATH.usage, <UsagePage />)
    expect(await screen.findByText('No usage recorded in these months.')).toBeInTheDocument()
    await expectAccessible()
  })
})

describe('the sponsor’s asset section', () => {
  it('links to the sponsor’s usage', async () => {
    renderAt('/here', '/here', <SponsorAssets userId={ANN} />)
    expect(await screen.findByTestId('sponsor-assets-usage')).toHaveAttribute('href', `${PATH.usage}?sponsor=${ANN}`)
  })
})
