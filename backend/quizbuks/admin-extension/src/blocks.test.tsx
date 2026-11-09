import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import BlocksPage from './BlocksPage'
import { blockMessages } from './block-messages'
import { PERMISSION, TEMPLATE } from './ids'
import { messages } from './messages'

type Row = Record<string, unknown>

/** The server as the page sees it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  runQuery: vi.fn(),
  sponsors: [] as Record<string, unknown>[],
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    runQuery: server.runQuery,
    useAuth: () => ({ entry: 'admin', can: (p: string) => server.permissions.has(p) }),
  }
})

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set([PERMISSION.blockRead, PERMISSION.usersRead])
  server.sponsors = [{ sponsorId: 's-1', company: 'Neko Co', blockers: '30', minGroup: '20' }]
  server.runQuery.mockReset().mockImplementation(async (id: string) => {
    const items: Row[] = id === TEMPLATE.blocks
      // QUIZ has no row (no block of that kind yet): shown with zeros.
      ? [{ kind: 'PUBLICATION', blocks: '1200', blockers: '340', allBlockers: '512' },
        { kind: 'SPONSOR', blocks: '80', blockers: '75', allBlockers: '512' },
        { kind: 'PROVIDER', blocks: '9', blockers: '9', allBlockers: '512' }]
      : id === TEMPLATE.blockedSponsors ? server.sponsors : []
    return { items, offset: 0, limit: 20 }
  })
})

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={['/qb/blocks']}>
          <Routes>
            <Route path="/qb/blocks" element={<BlocksPage />} />
          </Routes>
        </MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('the figures of blocking (F-S8)', () => {
  it('has every text in every language', () => {
    const keys = (value: object, prefix = ''): string[] => Object.entries(value).flatMap(([key, child]) =>
      typeof child === 'object' && child !== null ? keys(child, `${prefix}${key}.`) : [`${prefix}${key}`])
    const english = keys(blockMessages.en)
    expect(keys(blockMessages.zh)).toEqual(english)
    expect(keys(blockMessages.ja)).toEqual(english)
  })

  it('shows totals by kind and the Sponsors most blocked, rounded, never who', async () => {
    const { container } = renderPage()
    const publications = await screen.findByTestId('blocks-kind-PUBLICATION')
    expect(publications).toHaveTextContent('Quizzes')
    expect(within(publications).getByTestId('blocks-kind-blocks')).toHaveTextContent('1,200 blocks')
    expect(within(publications).getByTestId('blocks-kind-blockers')).toHaveTextContent('by 340 Takers')
    expect(within(screen.getByTestId('blocks-kind-QUIZ')).getByTestId('blocks-kind-blocks'))
      .toHaveTextContent('0 blocks')
    expect(screen.getByTestId('blocks-all')).toHaveTextContent('512')
    const sponsor = await screen.findByTestId('blocks-sponsor')
    expect(sponsor).toHaveTextContent('Neko Co')
    expect(within(sponsor).getByTestId('blocks-sponsor-count')).toHaveTextContent('30')
    expect(screen.getByTestId('blocks-sponsors')).toHaveTextContent('fewer than 20 are not listed')
    // The template is asked for the most blocked first.
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.blockedSponsors, expect.objectContaining({
      sorts: [{ field: 'blockers', asc: false }] }))
    await expectAccessible(container)
  })

  it('says when no Sponsor reaches the minimum group', async () => {
    server.sponsors = []
    renderPage()
    expect(await screen.findByTestId('blocks-sponsors-none')).toHaveTextContent(
      'No Sponsor has 20 or more Takers blocking it.')
  })
})
