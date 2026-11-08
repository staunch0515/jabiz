import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import TopicsPage from './TopicsPage'
import { PERMISSION, TEMPLATE, TOPIC_PROCESS } from './ids'
import { messages } from './messages'
import { namesOf } from './topic-api'
import { topicMessages } from './topic-messages'

type Row = Record<string, unknown>

/** The server as the page sees it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  runQuery: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    runQuery: server.runQuery,
    useAuth: () => ({ entry: 'admin', can: (p: string) => server.permissions.has(p) }),
  }
})

const word = (change: Row = {}): Row => ({ topicId: 'cooking',
  name: JSON.stringify({ zh: '烹饪', ja: '料理', en: 'Cooking' }), active: true, mergedInto: null, publications: '4',
  livePublications: '2', likedBy: '12', strictBy: null, minGroup: '5', ...change })

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set([PERMISSION.topicRead, PERMISSION.topicWrite])
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { count?: boolean } = {}) => {
    const items = id === TEMPLATE.topics ? [word(),
      word({ topicId: 'old-recipes', active: false, likedBy: null, publications: '0', livePublications: '0' }),
      word({ topicId: 'recipes', active: false, mergedInto: 'cooking', publications: null, livePublications: null,
        likedBy: null })] : []
    return { items, offset: 0, limit: 20, ...(options.count ? { total: items.length } : {}) }
  })
})

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={['/qb/topics']}>
          <Routes>
            <Route path="/qb/topics" element={<TopicsPage />} />
          </Routes>
        </MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('the word list of topics (F-S9)', () => {
  it('has every text in every language', () => {
    const keys = (value: object, prefix = ''): string[] => Object.entries(value).flatMap(([key, child]) =>
      typeof child === 'object' && child !== null ? keys(child, `${prefix}${key}.`) : [`${prefix}${key}`])
    const english = keys(topicMessages.en)
    expect(keys(topicMessages.zh)).toEqual(english)
    expect(keys(topicMessages.ja)).toEqual(english)
  })

  it('reads the names whether the template gives text or an object', () => {
    expect(namesOf('{"en":"Cooking"}')).toEqual({ en: 'Cooking' })
    expect(namesOf({ en: 'Cooking' })).toEqual({ en: 'Cooking' })
    expect(namesOf('broken')).toEqual({})
    expect(namesOf(null)).toEqual({})
  })

  it('lists the words with their names, states and figures, each changed on the process page', async () => {
    const { container } = renderPage()
    const rows = await screen.findAllByTestId('topic-row')
    expect(rows).toHaveLength(3)
    const [cooking, old, merged] = rows
    expect(within(cooking).getByTestId('topic-name-zh')).toHaveTextContent('烹饪')
    expect(within(cooking).getByTestId('topic-name-ja')).toHaveTextContent('料理')
    expect(within(cooking).getByTestId('topic-name-en')).toHaveTextContent('Cooking')
    expect(within(cooking).getByTestId('topic-state')).toHaveTextContent('Offered')
    expect(within(cooking).getByTestId('topic-publications')).toHaveTextContent('4')
    expect(within(cooking).getByTestId('topic-publications')).toHaveTextContent('2 live')
    // Users from the minimum group up only.
    expect(within(cooking).getByTestId('topic-liked')).toHaveTextContent('12')
    expect(within(cooking).getByTestId('topic-liked')).toHaveTextContent('Fewer than 5 with only liked topics')
    expect(within(old).getByTestId('topic-liked')).toHaveTextContent('Fewer than 5')
    expect(screen.getByTestId('topic-min-group')).toHaveTextContent('from 5 up')
    expect(within(cooking).getByTestId('topic-rename')).toHaveAttribute('href',
      `/processes/${TOPIC_PROCESS.rename}/1?topicId=cooking`)
    expect(within(cooking).getByTestId('topic-deactivate')).toHaveAttribute('href',
      `/processes/${TOPIC_PROCESS.deactivate}/1?topicId=cooking`)
    expect(within(cooking).getByTestId('topic-merge')).toHaveAttribute('href',
      `/processes/${TOPIC_PROCESS.merge}/1?topicId=cooking`)
    // A deactivated word is offered again; a merged one changes no more and has no figures of its own.
    expect(within(old).getByTestId('topic-state')).toHaveTextContent('Deactivated')
    expect(within(old).getByTestId('topic-reactivate')).toBeInTheDocument()
    expect(within(merged).getByTestId('topic-state')).toHaveTextContent('Merged into cooking')
    expect(within(merged).queryByTestId('topic-rename')).toBeNull()
    expect(within(merged).queryByTestId('topic-publications')).toBeNull()
    expect(screen.getByTestId('topic-add')).toHaveAttribute('href', `/processes/${TOPIC_PROCESS.add}/1`)
    await expectAccessible(container)
  })

  it('offers no change to who may only read', async () => {
    server.permissions = new Set([PERMISSION.topicRead])
    renderPage()
    const rows = await screen.findAllByTestId('topic-row')
    expect(within(rows[0]).queryByTestId('topic-rename')).toBeNull()
    expect(screen.queryByTestId('topic-add')).toBeNull()
  })
})
