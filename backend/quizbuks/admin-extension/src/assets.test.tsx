import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import type { ReactNode } from 'react'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import { SponsorAssets } from './SponsorAssets'
import SponsorReviewPage from './SponsorReviewPage'
import { parseTags } from './asset-api'
import { DATASET, DICTIONARY, PAGE_LIMIT, PERMISSION } from './ids'
import { messages } from './messages'

type Row = Record<string, unknown>
type Filter = { field: string; op: string; value?: unknown; values?: unknown[] }
type Body = { offset?: number; limit?: number; filters?: Filter[]; sorts?: { field: string; asc: boolean }[] }

/** The server as the Sponsor's asset library section sees it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  datasets: new Map<string, Record<string, unknown>[] | Error>(),
  get: vi.fn(),
  post: vi.fn(),
  runQuery: vi.fn(),
  runProcess: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { GET: server.get, POST: server.post },
    unwrap: async (call: Promise<unknown>) => call,
    runQuery: server.runQuery,
    runProcess: server.runProcess,
    useAuth: () => ({ can: (p: string) => server.permissions.has(p) }),
    FilePreview: () => <span data-testid="file" />,
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
    ApprovalPanel: () => <div data-testid="approval-panel" />,
  }
})

const SPONSOR = '0192f0a0-0000-7000-8000-00000000c001'
const OTHER = '0192f0a0-0000-7000-8000-00000000c002'
const COVER = '0192f0a0-0000-7000-8000-0000000a5e01'
const INTRO = '0192f0a0-0000-7000-8000-0000000a5e02'
const QUIZ = '0192f0a0-0000-7000-8000-00000000f001'
const PUBLICATION = '0192f0a0-0000-7000-8000-00000000f002'

const asset = (change: Row = {}): Row => ({ assetId: COVER, sponsorId: SPONSOR, kind: 'IMAGE', name: 'Brand cover',
  description: 'The square one', language: 'en', usage: null, tags: '["brand","cover"]', revision: 3,
  editedAt: '2026-11-05T01:00:00Z', ...change })

const use = (change: Row = {}): Row => ({ useId: 'u1', assetId: COVER, sponsorId: SPONSOR, targetKind: 'QUIZ_COVER',
  targetId: QUIZ, assetRevision: 2, usedAt: '2026-11-04T01:00:00Z', ...change })

/** What a filter of the datasets keeps: `eq` and `in`, as the platform's query. */
function matches(row: Row, filter: Filter): boolean {
  if (filter.op === 'in') return (filter.values ?? []).includes(row[filter.field])
  return row[filter.field] === filter.value
}

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set([PERMISSION.publicationRead, PERMISSION.quizRead, PERMISSION.usersRead])
  server.datasets = new Map<string, Row[] | Error>([
    [DATASET.asset, [
      asset(),
      asset({ assetId: INTRO, kind: 'TEXT', usage: 'QUIZ_INTRO', name: 'Welcome text', description: null, tags: '[]',
        revision: 1 }),
      asset({ assetId: 'theirs', sponsorId: OTHER, name: 'Not this Sponsor’s' }),
    ]],
    [DATASET.assetUse, [
      use(),
      use({ useId: 'u2', targetKind: 'PUBLICATION_COVER', targetId: PUBLICATION, assetRevision: 3 }),
      use({ useId: 'u3', targetKind: 'MATERIAL', targetId: 'material-1', assetRevision: 3 }),
      use({ useId: 'u4', assetId: INTRO, targetKind: 'QUIZ_INTRO', assetRevision: 1 }),
    ]],
    [DATASET.sponsor, [{ userId: SPONSOR, contactName: 'Rhea', company: 'Rhea Co', reviewStatus: 'APPROVED' }]],
    [DATASET.sponsorMethods, []],
  ])
  server.post.mockReset().mockImplementation(async (_path: string,
    options: { params: { path: { resourceId: string } }; body: Body }) => {
    const rows = server.datasets.get(options.params.path.resourceId)
    if (rows === undefined) throw new ApiError(403, { detail: 'Forbidden' })
    if (rows instanceof Error) throw rows
    const matching = rows.filter((row) => (options.body.filters ?? []).every((f) => matches(row, f)))
    const offset = options.body.offset ?? 0
    const limit = options.body.limit ?? 100
    return { items: matching.slice(offset, offset + limit).map((attributes) => ({ attributes })) }
  })
  server.get.mockReset().mockImplementation(async (path: string, options: { params: { path: Record<string, string> } }) => {
    if (path === '/api/dictionaries/{urn}') {
      const urn = options.params.path.urn
      if (urn === DICTIONARY.assetKind) return [{ code: 'IMAGE', label: 'Image' }, { code: 'TEXT', label: 'Text' }]
      if (urn === DICTIONARY.assetTextUsage) return [{ code: 'QUIZ_INTRO', label: 'Quiz introduction' }]
      if (urn === DICTIONARY.assetUseTarget) {
        return [{ code: 'QUIZ_COVER', label: 'Quiz cover' }, { code: 'PUBLICATION_COVER', label: 'Publication cover' },
          { code: 'MATERIAL', label: 'Material' }]
      }
      return []
    }
    throw new ApiError(404, { detail: 'Not found' })
  })
  server.runQuery.mockReset().mockResolvedValue({ items: [], offset: 0, limit: 1 })
  server.runProcess.mockReset()
})

function renderAt(path: string, route: string, element: ReactNode) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path={route} element={element} />
            <Route path="*" element={<p data-testid="elsewhere">elsewhere</p>} />
          </Routes>
        </MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

const renderSection = () => renderAt('/here', '/here', <SponsorAssets userId={SPONSOR} />)

/** The first asset's row once the counts of its uses are in (the row is drawn again when they come). */
async function openedRow() {
  await waitFor(() => expect(screen.getAllByTestId('sponsor-asset-use-count')[0]).not.toBeEmptyDOMElement())
  return screen.getAllByTestId('sponsor-asset-row')[0]
}

/** The bodies the section sent to one dataset. */
const asked = (dataset: string): Body[] => server.post.mock.calls
  .filter(([, options]) => (options as { params: { path: { resourceId: string } } }).params.path.resourceId === dataset)
  .map(([, options]) => (options as { body: Body }).body)

describe('parseTags', () => {
  it('reads the stored JSON list and nothing else', () => {
    expect(parseTags('["a","b"]')).toEqual(['a', 'b'])
    expect(parseTags('[1,"a"]')).toEqual(['a'])
    expect(parseTags('not json')).toEqual([])
    expect(parseTags('{"a":1}')).toEqual([])
    expect(parseTags(null)).toEqual([])
  })
})

describe('SponsorAssets', () => {
  it('lists the Sponsor’s own assets with their kind, tags, revision and where used, read only', async () => {
    const { container } = renderSection()
    const rows = await screen.findAllByTestId('sponsor-asset-row')
    expect(rows.map((row) => row.dataset.id)).toEqual([COVER, INTRO])
    expect(asked(DATASET.asset)[0].filters).toEqual([{ field: 'sponsorId', op: 'eq', value: SPONSOR }])
    expect(rows[0]).toHaveTextContent('Brand cover')
    expect(rows[0]).toHaveTextContent('The square one')
    expect(within(rows[0]).getByTestId('sponsor-asset-tags')).toHaveTextContent('brandcover')
    expect(rows[0]).toHaveTextContent('3')
    expect(await within(rows[1]).findByText('Text · Quiz introduction')).toBeInTheDocument()
    await waitFor(() => expect(within(rows[0]).getByTestId('sponsor-asset-use-count')).toHaveTextContent('3 places'))
    expect(within(rows[1]).getByTestId('sponsor-asset-use-count')).toHaveTextContent('1 places')
    // One read of the uses for the page's assets.
    expect(asked(DATASET.assetUse)).toEqual([{ offset: 0, limit: PAGE_LIMIT,
      filters: [{ field: 'assetId', op: 'in', values: [COVER, INTRO] }] }])
    // Nothing to change: in the rows, the only buttons open where an asset is used.
    for (const row of rows) {
      expect(within(row).getAllByRole('button').map((button) => button.dataset.testid)).toEqual(['sponsor-asset-uses-open'])
    }
    expect(within(screen.getByTestId('sponsor-assets')).queryByRole('button', { name: /edit|delete|save|collect/i }))
      .toBeNull()
    expect(server.runProcess).not.toHaveBeenCalled()
    await expectAccessible(container)
  })

  it('says the count is only a lower bound when the uses filled one read', async () => {
    const many = Array.from({ length: PAGE_LIMIT }, (_, i) => use({ useId: `m${i}`, targetId: `quiz-${i}` }))
    server.datasets.set(DATASET.assetUse, many)
    renderSection()
    const [row] = await screen.findAllByTestId('sponsor-asset-row')
    await waitFor(() => expect(within(row).getByTestId('sponsor-asset-use-count')).toHaveTextContent(`${PAGE_LIMIT}+ places`))
  })

  it('filters by kind on the server', async () => {
    renderSection()
    await screen.findAllByTestId('sponsor-asset-row')
    const user = userEvent.setup()
    await user.click(screen.getByTestId('sponsor-assets-kind'))
    await user.click(await screen.findByRole('option', { name: 'Text' }))
    await waitFor(() => expect(screen.getAllByTestId('sponsor-asset-row').map((row) => row.dataset.id)).toEqual([INTRO]))
    expect(asked(DATASET.asset).at(-1)?.filters).toEqual([{ field: 'sponsorId', op: 'eq', value: SPONSOR },
      { field: 'kind', op: 'eq', value: 'TEXT' }])
  })

  it('shows where an asset is used, marking older copies and linking to the quiz and the publication', async () => {
    renderSection()
    const user = userEvent.setup()
    const row = await openedRow()
    await user.click(within(row).getByRole('button', { name: 'Where Brand cover is used' }))
    const dialog = await screen.findByTestId('sponsor-asset-uses')
    expect(within(dialog).getByRole('heading', { name: 'Where “Brand cover” is used' })).toBeInTheDocument()
    const uses = await within(dialog).findAllByTestId('sponsor-asset-use')
    expect(uses.map((u) => u.dataset.kind)).toEqual(['QUIZ_COVER', 'PUBLICATION_COVER', 'MATERIAL'])
    expect(asked(DATASET.assetUse).at(-1)?.filters).toEqual([{ field: 'assetId', op: 'eq', value: COVER }])
    // Copied at revision 2 of an asset now at 3: older content; the others are up to date.
    expect(within(uses[0]).getByTestId('sponsor-asset-use-stale')).toHaveTextContent('Older content')
    expect(within(uses[1]).queryByTestId('sponsor-asset-use-stale')).toBeNull()
    expect(uses[1]).toHaveTextContent('Up to date')
    expect(await within(uses[0]).findByText('Quiz cover')).toBeInTheDocument()
    expect(within(uses[0]).getByTestId('asset-use-link')).toHaveAttribute('href', `/qb/quizzes/${QUIZ}`)
    expect(within(uses[1]).getByTestId('asset-use-link')).toHaveAttribute('href', `/qb/reviews/publications/${PUBLICATION}`)
    expect(within(uses[2]).queryByTestId('asset-use-link')).toBeNull()
    expect(uses[2]).toHaveTextContent('material-1')
    await expectAccessible(dialog)
    await user.click(within(dialog).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(screen.queryByTestId('sponsor-asset-uses')).toBeNull())
  })

  it('links a quiz only for readers of quizzes', async () => {
    server.permissions.delete(PERMISSION.quizRead)
    renderSection()
    const user = userEvent.setup()
    const row = await openedRow()
    await user.click(within(row).getByTestId('sponsor-asset-uses-open'))
    const uses = await within(await screen.findByTestId('sponsor-asset-uses')).findAllByTestId('sponsor-asset-use')
    expect(within(uses[0]).queryByTestId('asset-use-link')).toBeNull()
    expect(uses[0]).toHaveTextContent(QUIZ)
  })

  it('says when the Sponsor has no assets, and asks nothing about uses', async () => {
    server.datasets.set(DATASET.asset, [])
    renderSection()
    expect(await screen.findByText('No assets.')).toBeInTheDocument()
    expect(asked(DATASET.assetUse)).toEqual([])
  })

  it('says when the library cannot be read, and reads again', async () => {
    server.datasets.set(DATASET.asset, new ApiError(403, { detail: 'Broken' }))
    renderSection()
    expect(await screen.findByText('Broken')).toBeInTheDocument()
    server.datasets.set(DATASET.asset, [asset()])
    await userEvent.click(screen.getByRole('button', { name: /try again|retry/i }))
    expect(await screen.findAllByTestId('sponsor-asset-row')).toHaveLength(1)
  })
})

describe('the Sponsor’s page', () => {
  it('shows the asset library to readers of publications', async () => {
    renderAt(`/qb/reviews/sponsors/${SPONSOR}`, '/qb/reviews/sponsors/:userId', <SponsorReviewPage />)
    const section = await screen.findByTestId('sponsor-assets')
    expect(within(section).getByRole('heading', { level: 2, name: 'Asset library' })).toBeInTheDocument()
    expect(await within(section).findAllByTestId('sponsor-asset-row')).toHaveLength(2)
  })

  it('neither shows nor reads it without the permission', async () => {
    server.permissions = new Set([PERMISSION.usersRead])
    renderAt(`/qb/reviews/sponsors/${SPONSOR}`, '/qb/reviews/sponsors/:userId', <SponsorReviewPage />)
    expect(await screen.findByTestId('sponsor-application')).toBeInTheDocument()
    expect(screen.queryByTestId('sponsor-assets')).toBeNull()
    expect(asked(DATASET.asset)).toEqual([])
    expect(asked(DATASET.assetUse)).toEqual([])
  })
})
