import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import ExposurePage from './ExposurePage'
import { exposureMessages } from './exposure-messages'
import { PERMISSION, TEMPLATE } from './ids'
import { messages } from './messages'

/** The server as the page sees it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  runQuery: vi.fn(),
  rows: [] as Record<string, unknown>[],
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    runQuery: server.runQuery,
    useAuth: () => ({ entry: 'admin', can: (p: string) => server.permissions.has(p) }),
    UserName: ({ id }: { id: string }) => <span data-testid="user-name">{`name of ${id}`}</span>,
  }
})

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set([PERMISSION.exposureRead, PERMISSION.usersRead])
  server.rows = [{ userId: 'u-1', hits: '7', publications: '3', lastCappedOn: '2026-11-08', successes: '420' },
    { userId: 'u-2', hits: '1', publications: '1', lastCappedOn: '2026-11-01', successes: '200' }]
  server.runQuery.mockReset().mockImplementation(async (id: string) => ({
    items: id === TEMPLATE.exposureSuspicious ? server.rows : [], offset: 0, limit: 100 }))
})

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={['/qb/exposure']}>
          <Routes>
            <Route path="/qb/exposure" element={<ExposurePage />} />
          </Routes>
        </MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('the suspicious inflation of exposures (F-S13)', () => {
  it('has every text in every language', () => {
    const keys = (value: object, prefix = ''): string[] => Object.entries(value).flatMap(([key, child]) =>
      typeof child === 'object' && child !== null ? keys(child, `${prefix}${key}.`) : [`${prefix}${key}`])
    const english = keys(exposureMessages.en)
    expect(keys(exposureMessages.zh)).toEqual(english)
    expect(keys(exposureMessages.ja)).toEqual(english)
    expect(exposureMessages.ja.description).toContain('運営パートナー')
  })

  it('lists the Takers the daily cap refused, most refusals first', async () => {
    const { container } = renderPage()
    const rows = await screen.findAllByTestId('exposure-row')
    expect(rows.map((r) => r.dataset.id)).toEqual(['u-1', 'u-2'])
    expect(within(rows[0]).getByTestId('exposure-hits')).toHaveTextContent('7')
    expect(rows[0]).toHaveTextContent('420')
    expect(within(rows[0]).getByTestId('user-name')).toHaveTextContent('name of u-1')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.exposureSuspicious, expect.objectContaining({
      sorts: [{ field: 'hits', asc: false }] }))
    await expectAccessible(container)
  })

  it('shows an operator the account without a link to the users, and says when there is none', async () => {
    server.permissions = new Set([PERMISSION.exposureRead])
    const first = renderPage()
    const rows = await screen.findAllByTestId('exposure-row')
    expect(within(rows[0]).queryByTestId('user-name')).toBeNull()
    expect(rows[0]).toHaveTextContent('u-1')
    first.unmount()
    server.rows = []
    const { container } = renderPage()
    expect(await screen.findByTestId('exposure-none')).toHaveTextContent('No Taker has reached the daily cap.')
    await expectAccessible(container)
  })
})
