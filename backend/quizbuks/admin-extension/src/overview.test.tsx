import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import type { ReactNode } from 'react'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes, type InitialEntry } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { SNAPSHOT_JSON } from './fixtures'
import { DATASET, TEMPLATE } from './ids'
import { messages } from './messages'
import { readDraft, type QuizRow } from './overview-api'
import PublicationReviewPage from './PublicationReviewPage'
import PublicationsPage from './PublicationsPage'
import QuizDetailPage from './QuizDetailPage'
import QuizzesPage from './QuizzesPage'

type Row = Record<string, unknown>

/** The server as the pages see it through `@jabiz/admin`: datasets, templates and dictionaries. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  entities: new Map<string, Row>(),
  datasets: new Map<string, Row[]>(),
  templates: new Map<string, Row[]>(),
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
    FilePreview: ({ fileId }: { fileId: string }) => <span data-testid="file" data-file={fileId} />,
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
    ApprovalPanel: () => <span data-testid="approval-panel" />,
  }
})

const SPONSOR = '0192f0a0-0000-7000-8000-0000000005a1'
const QUIZ = '0192f0a0-0000-7000-8000-00000000a011'
const REMOVED = '0192f0a0-0000-7000-8000-00000000a012'
const VERSION = '0192f0a0-0000-7000-8000-0000000000e1'
const PUBLICATION = '0192f0a0-0000-7000-8000-0000000000b1'
const SENT_BACK = '0192f0a0-0000-7000-8000-0000000000b2'

const quizRow = (overrides: Row = {}): Row => ({
  quizId: QUIZ, ownerId: SPONSOR, title: 'World capitals', kind: 'QUIZ', status: 'VERSIONED', removed: false,
  aiGenerated: false, versionLabel: 'v1.0', latestVersionNo: 1, changedSinceVersion: true, questionCount: 2,
  materialCount: 0, timeLimitSec: 300, clonedFrom: null, editedAt: '2026-10-20T00:00:00Z', publicationCount: 2,
  ...overrides,
})

const publicationRow = (overrides: Row = {}): Row => ({
  publicationId: PUBLICATION, sponsorId: SPONSOR, quizId: QUIZ, name: 'Spring run', quizTitle: 'World capitals',
  versionLabel: 'v1.0', status: 'LIVE', starts: 12, completions: 10, winners: 3, startAt: '2026-10-01T00:00:00Z',
  endAt: '2026-11-01T00:00:00Z', editedAt: '2026-10-02T00:00:00Z', rejectReason: null, ...overrides,
})

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set(['qb.admin.quiz.read', 'qb.admin.publication.read', 'qb.admin.users.read'])
  server.entities = new Map([[`${DATASET.version}/${VERSION}`, { versionId: VERSION, label: 'v1.0',
    title: 'World capitals', contentHash: 'a'.repeat(64), versionedAt: '2026-10-14T00:00:00Z',
    content: SNAPSHOT_JSON }],
  // Read by its key: the quiz dataset is not filtered by key (it is no dataset here, so a query fails).
  [`${DATASET.quiz}/${QUIZ}`, { quizId: QUIZ, title: 'World capitals (draft)', intro: 'Read *first*.', cover: null,
    timeLimitSec: 300 }]])
  server.datasets = new Map<string, Row[]>([
    [DATASET.material, []],
    [DATASET.materialImage, []],
    // Out of order on purpose: the draft is shown in each part's order.
    [DATASET.question, [
      { questionId: 'q2', quizId: QUIZ, seq: 2, stem: 'Capital of Japan?', points: 3, image: null },
      { questionId: 'q1', quizId: QUIZ, seq: 1, stem: 'Capital of France?', points: 2, image: null },
    ]],
    [DATASET.option, [
      { optionId: 'o2', questionId: 'q1', quizId: QUIZ, seq: 2, text: 'Lyon', correct: false },
      { optionId: 'o1', questionId: 'q1', quizId: QUIZ, seq: 1, text: 'Paris', correct: true },
      { optionId: 'o3', questionId: 'q2', quizId: QUIZ, seq: 1, text: 'Tokyo', correct: true },
    ]],
  ])
  server.templates = new Map<string, Row[]>([
    [TEMPLATE.quizzes, [quizRow(), quizRow({ quizId: REMOVED, title: 'Old one', removed: true, publicationCount: 0 })]],
    [TEMPLATE.quizVersions, [{ versionId: VERSION, versionNo: 1, label: 'v1.0', title: 'World capitals',
      questionCount: 2, fullScore: 5, versionedAt: '2026-10-14T00:00:00Z' }]],
    [TEMPLATE.publications, [publicationRow(), publicationRow({ publicationId: SENT_BACK, name: 'Too generous',
      status: 'DRAFT', starts: 0, completions: 0, winners: 0, rejectReason: 'The rewards are too high.' })]],
    [TEMPLATE.quizTakedowns, []],
  ])
  server.get.mockReset().mockImplementation(async (path: string, options: { params: { path: Record<string, string> } }) => {
    if (path === '/api/dictionaries/{urn}') return []
    const { resourceId, id } = options.params.path
    const found = server.entities.get(`${resourceId}/${id}`)
    if (!found) throw new ApiError(404, { detail: 'Not found' })
    return { id, attributes: found }
  })
  server.post.mockReset().mockImplementation(async (_path: string, options: {
    params: { path: { resourceId: string } }
    body: { offset?: number; limit?: number; filters?: { field: string; value: unknown }[] }
  }) => {
    const rows = server.datasets.get(options.params.path.resourceId)
    if (!rows) throw new ApiError(403, { detail: 'Forbidden' })
    const matching = rows.filter((row) => (options.body.filters ?? []).every((f) => row[f.field] === undefined
      || row[f.field] === f.value))
    const offset = options.body.offset ?? 0
    return { items: matching.slice(offset, offset + (options.body.limit ?? 100)).map((attributes) => ({ attributes })) }
  })
  server.runQuery.mockReset().mockImplementation(async (id: string,
    options: { offset?: number; limit?: number; count?: boolean } = {}) => {
    const all = server.templates.get(id) ?? []
    const offset = options.offset ?? 0
    const limit = options.limit ?? 100
    return { items: all.slice(offset, offset + limit), offset, limit, ...(options.count ? { total: all.length } : {}) }
  })
})

function renderAt(entry: InitialEntry, route: string, element: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={[entry]}>
          <Routes>
            <Route path={route} element={element} />
            <Route path="*" element={<p data-testid="elsewhere">elsewhere</p>} />
          </Routes>
        </MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

/** The options of the template's latest call. */
const lastCall = (template: string) =>
  server.runQuery.mock.calls.filter(([id]) => id === template).at(-1)?.[1] as Row

describe('QuizzesPage', () => {
  it('lists every sponsor\'s quizzes, without the removed ones unless asked, linking to each', async () => {
    renderAt('/qb/quizzes', '/qb/quizzes', <QuizzesPage />)
    const links = await screen.findAllByTestId('quiz-link')
    expect(links[0]).toHaveTextContent('World capitals')
    expect(links[0]).toHaveAttribute('href', `/qb/quizzes/${QUIZ}`)
    expect(lastCall(TEMPLATE.quizzes)).toMatchObject({ params: { q: null },
      filters: [{ field: 'removed', op: 'eq', value: false }], count: true })
    const first = screen.getAllByTestId('quiz-row')[0]
    expect(within(first).getByText('changed since v1.0')).toBeInTheDocument()
    expect(within(first).getByRole('link', { name: 'Publications of World capitals' }))
      .toHaveAttribute('href', `/qb/publications?quiz=${QUIZ}`)

    await userEvent.click(screen.getByTestId('quizzes-removed'))
    await waitFor(() => expect(lastCall(TEMPLATE.quizzes)).toMatchObject({ filters: [] }))
    expect(screen.getAllByTestId('quiz-removed')).toHaveLength(1)
  })

  it('searches by title and narrows to the sponsor the address names', async () => {
    renderAt(`/qb/quizzes?sponsor=${SPONSOR}`, '/qb/quizzes', <QuizzesPage />)
    await screen.findAllByTestId('quiz-link')
    expect(lastCall(TEMPLATE.quizzes)).toMatchObject({ filters: [{ field: 'ownerId', op: 'eq', value: SPONSOR },
      { field: 'removed', op: 'eq', value: false }] })
    expect(screen.getByTestId('quizzes-sponsor')).toHaveTextContent(SPONSOR)
    await userEvent.type(screen.getByTestId('quizzes-search'), ' capitals {Enter}')
    await waitFor(() => expect(lastCall(TEMPLATE.quizzes)).toMatchObject({ params: { q: 'capitals' } }))
    await userEvent.click(screen.getByTestId('quizzes-all'))
    await waitFor(() => expect(lastCall(TEMPLATE.quizzes)).toMatchObject({ params: { q: null },
      filters: [{ field: 'removed', op: 'eq', value: false }] }))
  })
})

describe('QuizDetailPage', () => {
  const renderQuiz = () => renderAt(`/qb/quizzes/${QUIZ}`, '/qb/quizzes/:quizId', <QuizDetailPage />)

  it('shows the facts, the publications in every state and the draft with its correct answers, in order', async () => {
    renderQuiz()
    expect(await screen.findByTestId('quiz-facts')).toHaveTextContent('The draft changed since the latest version.')
    expect(screen.getByTestId('quiz-sponsor')).toHaveAttribute('href', `/qb/users/${SPONSOR}`)
    expect(lastCall(TEMPLATE.publications)).toMatchObject({ filters: [{ field: 'quizId', op: 'eq', value: QUIZ }] })
    const publications = await screen.findAllByTestId('quiz-publication-link')
    expect(publications.map((link) => link.textContent)).toEqual(['Spring run', 'Too generous'])
    expect(within(screen.getByTestId('quiz-publications')).getByRole('link', { name: 'Attempts of Spring run' }))
      .toHaveAttribute('href', `/qb/attempts?publication=${PUBLICATION}`)

    const draft = screen.getByTestId('quiz-draft')
    await waitFor(() => expect(within(draft).getAllByTestId('preview-stem').map((stem) => stem.textContent))
      .toEqual(['Capital of France?', 'Capital of Japan?']))
    expect(within(draft).getByTestId('preview-title')).toHaveTextContent('World capitals (draft)')
    const options = within(draft).getAllByTestId('preview-option')
    expect(options.map((option) => [option.textContent?.includes('Paris') ? 'Paris'
      : option.textContent?.includes('Lyon') ? 'Lyon' : 'Tokyo', option.dataset.correct]))
      .toEqual([['Paris', 'true'], ['Lyon', 'false'], ['Tokyo', 'true']])
  })

  it('reads a version\'s frozen content only when asked', async () => {
    renderQuiz()
    const show = await screen.findByTestId('quiz-version-show')
    const versionReads = () => server.get.mock.calls.filter(([, options]) =>
      options?.params?.path?.resourceId === DATASET.version)
    expect(versionReads()).toHaveLength(0)
    await userEvent.click(show)
    const content = await screen.findByTestId('quiz-version-content')
    expect(await within(content).findByTestId('version-preview')).toBeInTheDocument()
    expect(show).toHaveAttribute('aria-expanded', 'true')
    await userEvent.click(show)
    expect(screen.queryByTestId('quiz-version-content')).not.toBeInTheDocument()
  })

  it('shows a removed quiz\'s versions but no draft', async () => {
    server.templates.set(TEMPLATE.quizzes, [quizRow({ removed: true })])
    renderQuiz()
    expect(await screen.findByTestId('qb-quiz')).toHaveAttribute('data-removed', 'true')
    expect(screen.getByTestId('quiz-versions')).toBeInTheDocument()
    expect(screen.queryByTestId('quiz-draft')).not.toBeInTheDocument()
    expect(server.post).not.toHaveBeenCalled()
  })

  it('shows no publications to whom they are not offered, and says when there is no such quiz', async () => {
    server.permissions = new Set(['qb.admin.quiz.read'])
    const { unmount } = renderQuiz()
    expect(await screen.findByTestId('quiz-facts')).toBeInTheDocument()
    expect(screen.queryByTestId('quiz-publications')).not.toBeInTheDocument()
    unmount()
    server.templates.set(TEMPLATE.quizzes, [])
    renderQuiz()
    expect(await screen.findByText('There is no such quiz.')).toBeInTheDocument()
  })
})

describe('PublicationsPage', () => {
  it('lists the publications in every state, marking those sent back, with their attempts', async () => {
    renderAt('/qb/publications', '/qb/publications', <PublicationsPage />)
    const rows = await screen.findAllByTestId('publication-row')
    expect(rows.map((row) => row.dataset.status)).toEqual(['LIVE', 'DRAFT'])
    expect(lastCall(TEMPLATE.publications)).toMatchObject({ params: { q: null }, filters: [], count: true })
    expect(within(rows[1]).getByTestId('publication-sent-back')).toBeInTheDocument()
    expect(within(rows[0]).getByTestId('publication-attempts'))
      .toHaveAttribute('href', `/qb/attempts?publication=${PUBLICATION}`)
    expect(within(rows[1]).queryByTestId('publication-attempts')).not.toBeInTheDocument()
    expect(within(rows[0]).getByRole('link', { name: 'World capitals · v1.0' }))
      .toHaveAttribute('href', `/qb/quizzes/${QUIZ}`)
  })

  it('narrows by the state, sponsor and quiz the address names', async () => {
    renderAt(`/qb/publications?status=ENDED&sponsor=${SPONSOR}&quiz=${QUIZ}`, '/qb/publications',
      <PublicationsPage />)
    await screen.findAllByTestId('publication-row')
    expect(lastCall(TEMPLATE.publications)).toMatchObject({ filters: [
      { field: 'status', op: 'eq', value: 'ENDED' }, { field: 'sponsorId', op: 'eq', value: SPONSOR },
      { field: 'quizId', op: 'eq', value: QUIZ }] })
    await userEvent.click(screen.getByTestId('publications-all'))
    await waitFor(() => expect(lastCall(TEMPLATE.publications)).toMatchObject({ filters: [] }))
  })

  it('links no quiz to whom the quizzes are not offered (finance)', async () => {
    server.permissions = new Set(['qb.admin.publication.read'])
    renderAt('/qb/publications', '/qb/publications', <PublicationsPage />)
    const rows = await screen.findAllByTestId('publication-row')
    expect(within(rows[0]).queryByRole('link', { name: 'World capitals · v1.0' })).not.toBeInTheDocument()
    expect(rows[0]).toHaveTextContent('World capitals · v1.0')
  })

  it('opens a publication that goes back to the list it came from', async () => {
    server.entities.set(`${DATASET.publication}/${PUBLICATION}`, { publicationId: PUBLICATION, sponsorId: SPONSOR,
      quizId: QUIZ, versionId: VERSION, versionLabel: 'v1.0', name: 'Spring run', regions: 'JP', status: 'LIVE',
      approvalRequestId: null })
    server.datasets.set(DATASET.tier, [])
    server.datasets.set(DATASET.sponsor, [])
    renderAt({ pathname: `/qb/reviews/publications/${PUBLICATION}`,
      state: { backTo: { path: '/qb/publications?status=LIVE', label: 'publications.backToList' } } },
    '/qb/reviews/publications/:publicationId', <PublicationReviewPage />)
    const back = await screen.findByRole('link', { name: 'Back to publications' })
    expect(back).toHaveAttribute('href', '/qb/publications?status=LIVE')
    expect(screen.getByTestId('publication-quiz')).toHaveAttribute('href', `/qb/quizzes/${QUIZ}`)
  })
})

describe('readDraft', () => {
  it('reads every part page by page', async () => {
    const many = Array.from({ length: 250 }, (_, i) => ({ optionId: `o${i}`, questionId: 'q1', quizId: QUIZ,
      seq: 250 - i, text: `Option ${250 - i}`, correct: i === 0 }))
    server.datasets.set(DATASET.option, many)
    const draft = await readDraft(quizRow() as unknown as QuizRow)
    expect(draft.questions[0].options).toHaveLength(250)
    expect(draft.questions[0].options[0]).toMatchObject({ no: 1, text: 'Option 1', correct: false })
    expect(draft.questions[0].options.at(-1)).toMatchObject({ no: 250, text: 'Option 250', correct: true })
    expect(server.post.mock.calls.filter(([, options]) => options.params.path.resourceId === DATASET.option)
      .map(([, options]) => options.body.offset)).toEqual([0, 100, 200])
  })
})
