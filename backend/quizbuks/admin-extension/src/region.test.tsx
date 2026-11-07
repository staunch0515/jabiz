import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import RegionPage from './RegionPage'
import { PERMISSION, REGION_PROCESS, TEMPLATE } from './ids'
import { messages } from './messages'
import { regionMessages } from './region-messages'

type Row = Record<string, unknown>

/** The server as the page sees it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  runQuery: vi.fn(),
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
    useAuth: () => ({ can: (p: string) => server.permissions.has(p) }),
  }
})

const PUB = '0192f0a0-0000-7000-8000-00000000e001'

const exception = (change: Row = {}): Row => ({ exceptionId: 'x1', kind: 'PUBLICATION', publicationId: PUB,
  publicationName: 'Capitals week', foreignVideo: true, sponsorId: null, reason: 'Mirrored in <b>China</b>',
  expiresOn: '2026-12-31', revoked: false, revokeReason: null, inForce: true, createdAt: '2026-11-01T00:00:00Z',
  createdBy: 'admin-1', changedAt: '2026-11-01T00:00:00Z', changedBy: 'admin-1', ...change })

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set([PERMISSION.regionRead, PERMISSION.regionWrite])
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { params?: Row; count?: boolean } = {}) => {
    let items: Row[] = []
    if (id === TEMPLATE.regionExceptions) {
      items = [exception(), exception({ exceptionId: 'x2', kind: 'SPONSOR', publicationId: null, publicationName: null,
        foreignVideo: null, sponsorId: 'sponsor-1', expiresOn: null, revoked: true, revokeReason: 'Licence ended',
        inForce: false })].filter((row) => options.params?.inForce == null || row.inForce === options.params.inForce)
    } else if (id === TEMPLATE.regionRefusals) {
      items = [{ day: '2026-11-07', refused: '3' }]
    }
    return { items, offset: 0, limit: 20, ...(options.count ? { total: items.length } : {}) }
  })
  server.get.mockReset().mockImplementation(async () => { throw new ApiError(404, { detail: 'Not found' }) })
  server.post.mockReset().mockImplementation(async () => { throw new ApiError(404, { detail: 'Not found' }) })
})

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={['/qb/region']}>
          <Routes>
            <Route path="/qb/region" element={<RegionPage />} />
            <Route path="*" element={<p data-testid="elsewhere">elsewhere</p>} />
          </Routes>
        </MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('foreign videos and mainland China (F-S11)', () => {
  it('have every text in every language', () => {
    const keys = (value: object, prefix = ''): string[] => Object.entries(value).flatMap(([key, child]) =>
      typeof child === 'object' && child !== null ? keys(child, `${prefix}${key}.`) : [`${prefix}${key}`])
    const english = keys(regionMessages.en)
    expect(keys(regionMessages.zh)).toEqual(english)
    expect(keys(regionMessages.ja)).toEqual(english)
  })

  it('lists the exceptions in force, each changed or revoked on the process page, and the refused starts', async () => {
    const { container } = renderPage()
    const rows = await screen.findAllByTestId('region-row')
    expect(rows).toHaveLength(1)
    expect(rows[0]).toHaveTextContent('Capitals week')
    // The reason as plain text.
    expect(rows[0]).toHaveTextContent('Mirrored in <b>China</b>')
    expect(rows[0]).toHaveTextContent('2026-12-31')
    expect(within(rows[0]).getByTestId('region-state')).toHaveTextContent('In force')
    expect(within(rows[0]).getByTestId('region-expiry')).toHaveAttribute('href',
      `/processes/${REGION_PROCESS.expirySet}/1?exceptionId=x1`)
    expect(within(rows[0]).getByTestId('region-revoke')).toHaveAttribute('href',
      `/processes/${REGION_PROCESS.revoke}/1?exceptionId=x1`)
    expect(screen.getByTestId('region-add')).toHaveAttribute('href', `/processes/${REGION_PROCESS.add}/1`)
    const refused = await screen.findByTestId('region-refusal-row')
    expect(refused).toHaveTextContent('2026-11-07')
    expect(refused).toHaveTextContent('3')
    // The template is asked for those in force at the page's time.
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.regionExceptions,
      expect.objectContaining({ params: expect.objectContaining({ inForce: true, at: expect.any(String) }) }))
    await expectAccessible(container)
  })

  it('shows every exception on asking, a revoked one with its reason and no actions', async () => {
    renderPage()
    await screen.findAllByTestId('region-row')
    await userEvent.click(screen.getByTestId('region-show'))
    await userEvent.click(await screen.findByRole('option', { name: 'All' }))
    const rows = await screen.findAllByTestId('region-row')
    expect(rows).toHaveLength(2)
    const revoked = rows.find((row) => row.getAttribute('data-id') === 'x2')!
    expect(revoked).toHaveTextContent('All of a Sponsor')
    expect(revoked).toHaveTextContent('Until revoked')
    expect(revoked).toHaveTextContent('Revoked: Licence ended')
    expect(within(revoked).queryByTestId('region-revoke')).toBeNull()
  })

  it('offers no change to who may only read', async () => {
    server.permissions = new Set([PERMISSION.regionRead])
    renderPage()
    const rows = await screen.findAllByTestId('region-row')
    expect(within(rows[0]).queryByTestId('region-revoke')).toBeNull()
    expect(screen.queryByTestId('region-add')).toBeNull()
  })
})
