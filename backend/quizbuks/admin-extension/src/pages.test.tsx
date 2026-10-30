import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import type { ReactNode } from 'react'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE, Toaster } from '@jabiz/admin'
import { COVER, PDF, PICTURE, SNAPSHOT_JSON } from './fixtures'
import { textIn } from './hooks'
import { DATASET, TEMPLATE, PROCESS } from './ids'
import { messages } from './messages'
import PublicationReviewPage from './PublicationReviewPage'
import ReviewsPage from './ReviewsPage'
import SponsorReviewPage from './SponsorReviewPage'
import UserSummaryPage from './UserSummaryPage'

type Row = Record<string, unknown>

/** The server as the pages see it through `@jabiz/admin`: datasets, templates and dictionaries. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  entities: new Map<string, Row | Error | (() => Row | Error)>(),
  datasets: new Map<string, Row[]>(),
  templates: new Map<string, Row[] | Error>(),
  runQuery: vi.fn(),
  runProcess: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  decided: vi.fn(),
  /** The request's state APPROVAL_DECIDE answers with: APPROVED closes it, PENDING leaves it to the next level. */
  decision: 'APPROVED',
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { GET: server.get, POST: server.post },
    unwrap: async (call: Promise<unknown>) => call,
    runQuery: server.runQuery,
    runProcess: server.runProcess,
    useAuth: () => ({ can: (p: string) => server.permissions.has('*') || server.permissions.has(p) }),
    // The platform's own parts that call the server themselves stand in by what they were given.
    FilePreview: ({ fileId, field }: { fileId: string; field: { image?: boolean } }) => (
      <span data-testid="file" data-file={fileId} data-image={field.image === true} />
    ),
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
    ApprovalPanel: ({ requestId, onDecided }: { requestId: string; onDecided?: (status: string) => void }) => (
      <button type="button" data-testid="approval-panel" data-request={requestId}
        onClick={() => {
          server.decided(requestId)
          onDecided?.(server.decision)
        }}>
        decide
      </button>
    ),
  }
})

const SPONSOR_ID = '0192f0a0-0000-7000-8000-0000000005a1'
const PUBLICATION_ID = '0192f0a0-0000-7000-8000-0000000000b1'
const VERSION_ID = '0192f0a0-0000-7000-8000-0000000000e1'
const REQUEST_ID = '0192f0a0-0000-7000-8000-0000000000a1'

function publication(status = 'PENDING_REVIEW'): Row {
  return {
    publicationId: PUBLICATION_ID, publisherKind: 'SPONSOR', publisherId: SPONSOR_ID, quizId: 'quiz-1',
    versionId: VERSION_ID, versionLabel: 'v1',
    name: 'Spring run', regions: 'JP,ASIA', startAt: '2026-11-01T00:00:00Z', endAt: '2026-11-08T00:00:00Z',
    timeLimitSec: 300, passScore: 3, fullScore: 5, budgetCap: 10000, maxReward: 100, maxSpend: 5000, maxWinners: 50,
    status, approvalRequestId: REQUEST_ID, submittedAt: '2026-10-15T01:00:00Z',
    rejectReason: status === 'DRAFT' ? 'Too easy.' : null,
  }
}

const SPONSOR: Row = {
  userId: SPONSOR_ID, contactName: 'Rhea', company: 'Rhea Co', intro: 'We ask **questions**.', avatar: 'avatar-1',
  reviewStatus: 'PENDING', termsAcceptedAt: '2026-10-14T00:00:00Z', approvalRequestId: 'sponsor-request',
  submittedAt: '2026-10-14T00:00:00Z',
}

const USER: Row = {
  userId: SPONSOR_ID, userName: 'rhea@example.com', email: 'rhea@example.com', emailVerified: true, enabled: true,
  displayName: 'Rhea', countryCode: 'JP', countryName: { en: 'Japan', ja: '日本', zh: '日本' }, city: 'Kyoto', registeredAt: '2026-10-01T00:00:00Z',
  sponsorStatus: 'APPROVED', company: 'Rhea Co', banned: false,
}

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set(['qb.review.publication', 'qb.review.sponsor', 'qb.admin.quiz.read',
    'qb.admin.users.read', 'qb.admin.publication.read'])
  server.entities = new Map<string, Row | Error | (() => Row | Error)>([
    [`${DATASET.publication}/${PUBLICATION_ID}`, publication()],
    [`${DATASET.version}/${VERSION_ID}`, { versionId: VERSION_ID, label: 'v1', title: 'World capitals',
      contentHash: 'a'.repeat(64), versionedAt: '2026-10-14T00:00:00Z', content: SNAPSHOT_JSON }],
  ])
  server.datasets = new Map([
    [DATASET.tier, [{ seq: 1, fromScore: 3, toScore: 4, reward: 10 }, { seq: 0, fromScore: 0, toScore: 2, reward: 0 },
      { seq: 2, fromScore: 5, toScore: 5, reward: 100 }]],
    [DATASET.sponsor, [SPONSOR]],
    [DATASET.ban, []],
    [DATASET.sponsorMethods, []],
  ])
  server.templates = new Map<string, Row[] | Error>([
    [TEMPLATE.publications, [{ ...publication(), quizTitle: 'World capitals' }]],
    [TEMPLATE.rewardDefaults, [{ paramKey: 'qb.reward.default-methods', value: 'OFFLINE_REDEEM', paramExists: true,
      seq: 1 }, { paramKey: 'qb.reward.max-per-attempt', value: '0', paramExists: true, seq: 2 }]],
    [TEMPLATE.quizTakedowns, []],
    [TEMPLATE.publicationFunds, [{ publicationId: PUBLICATION_ID, fundsShort: true, pendingCount: 2,
      pendingAmount: 300 }]],
    [TEMPLATE.sponsors, [{ onboardingId: 'o1', ...SPONSOR, email: 'rhea@example.com' }]],
    [TEMPLATE.users, [USER]],
    [TEMPLATE.attempts, [{ attemptId: 'a1', publicationId: PUBLICATION_ID, name: 'Spring run', status: 'SUBMITTED',
      startedAt: '2026-10-15T00:00:00Z', score: 4, fullScore: 5, passed: true, rewardStatus: 'PAID' }]],
    [TEMPLATE.attemptRewards, [{ attemptId: 'a1', name: 'Spring run', submittedAt: '2026-10-15T00:05:00Z',
      reward: 7777, fee: 0, rewardStatus: 'PAID' }]],
    [TEMPLATE.transfers, [{ transferId: 't1', amount: 1200, fee: 0, net: 1200, status: 'PAID',
      createdAt: '2026-10-15T00:00:00Z' }]],
    [TEMPLATE.payoutAccounts, [{ status: 'ENABLED', payoutsEnabled: true, externalAccountId: 'acct_1234567890WXYZ' }]],
    [TEMPLATE.topUps, [{ topUpId: 'u1', amount: 5000, status: 'PAID', createdAt: '2026-10-10T00:00:00Z' }]],
  ])
  server.get.mockReset().mockImplementation(async (path: string, options: { params: { path: Record<string, string> } }) => {
    if (path === '/api/dictionaries/{urn}') return []
    const { resourceId, id } = options.params.path
    const found = server.entities.get(`${resourceId}/${id}`)
    const value = typeof found === 'function' ? found() : found
    if (value === undefined) throw new ApiError(404, { detail: 'Not found' })
    if (value instanceof Error) throw value
    return { id, attributes: value }
  })
  server.post.mockReset().mockImplementation(async (_path: string,
    options: { params: { path: { resourceId: string } }; body: { filters?: { field: string; value: unknown }[] } }) => {
    const rows = server.datasets.get(options.params.path.resourceId)
    if (!rows) throw new ApiError(403, { detail: 'Forbidden' })
    const matching = rows.filter((row) => (options.body.filters ?? []).every((f) => row[f.field] === undefined
      || row[f.field] === f.value))
    return { items: matching.map((attributes) => ({ attributes })) }
  })
  server.runQuery.mockReset().mockImplementation(async (id: string,
    options: { offset?: number; limit?: number; count?: boolean } = {}) => {
    const rows = server.templates.get(id)
    if (rows instanceof Error) throw rows
    const all = rows ?? []
    const offset = options.offset ?? 0
    const limit = options.limit ?? 100
    return { items: all.slice(offset, offset + limit), offset, limit, ...(options.count ? { total: all.length } : {}) }
  })
  server.decided.mockReset()
  server.runProcess.mockReset().mockResolvedValue({})
  server.decision = 'APPROVED'
})

function renderAt(path: string, route: string, element: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path={route} element={element} />
            <Route path="*" element={<p data-testid="elsewhere">elsewhere</p>} />
          </Routes>
        </MemoryRouter>
        <Toaster />
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

const renderPublication = () =>
  renderAt(`/qb/reviews/publications/${PUBLICATION_ID}`, '/qb/reviews/publications/:publicationId', <PublicationReviewPage />)

describe('ReviewsPage', () => {
  it('lists the publications waiting for review, the longest waiting first, linking to their review', async () => {
    renderAt('/qb/reviews', '/qb/reviews', <ReviewsPage />)
    const link = await screen.findByTestId('queue-publication-link')
    expect(link).toHaveTextContent('Spring run')
    expect(link).toHaveAttribute('href', `/qb/reviews/publications/${PUBLICATION_ID}`)
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.publications, {
      params: { inReview: true },
      sorts: [{ field: 'waitingSince', asc: true }],
      offset: 0,
      limit: 20,
      count: true,
    })
    expect(screen.getByTestId('tab-publications')).toHaveTextContent('Publications (1)')
    await userEvent.click(screen.getByTestId('tab-sponsors'))
    const sponsor = await screen.findByTestId('queue-sponsor-link')
    expect(sponsor).toHaveTextContent('Rhea Co')
    expect(sponsor).toHaveAttribute('href', `/qb/reviews/sponsors/${SPONSOR_ID}`)
  })

  it('shows a running publication\'s switch of version among them (Q51)', async () => {
    server.templates.set(TEMPLATE.publications, [{ ...publication('LIVE'), quizTitle: 'World capitals',
      pendingVersionLabel: 'v2', pendingRequestId: 'switch-request', waitingSince: '2026-10-20T00:00:00Z' }])
    renderAt('/qb/reviews', '/qb/reviews', <ReviewsPage />)
    expect(await screen.findByTestId('queue-publication-switch')).toHaveTextContent('World capitals · v1 → v2')
  })

  it('sorts the queue on the server by the column clicked, from the first page (Q53a)', async () => {
    renderAt('/qb/reviews', '/qb/reviews', <ReviewsPage />)
    await screen.findByTestId('queue-publication-link')
    await userEvent.click(screen.getByRole('button', { name: 'Waiting since' }))
    await waitFor(() => expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.publications, expect.objectContaining({
      sorts: [{ field: 'waitingSince', asc: true }], offset: 0 })))
    await userEvent.click(screen.getByRole('button', { name: 'Waiting since' }))
    await waitFor(() => expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.publications, expect.objectContaining({
      sorts: [{ field: 'waitingSince', asc: false }] })))
    // A column the template does not sort by has no button.
    expect(screen.queryByRole('button', { name: 'Budget' })).toBeNull()
  })

  it('offers only the queues the user may review', async () => {
    server.permissions = new Set(['qb.review.publication'])
    renderAt('/qb/reviews?tab=sponsors', '/qb/reviews', <ReviewsPage />)
    expect(await screen.findByTestId('queue-publication-link')).toBeInTheDocument()
    expect(screen.queryByTestId('tab-sponsors')).not.toBeInTheDocument()
    expect(server.runQuery).not.toHaveBeenCalledWith(TEMPLATE.sponsors, expect.anything())
  })
})

describe('PublicationReviewPage', () => {
  it('shows the settings, the tiers and the whole frozen version with the correct answers', async () => {
    renderPublication()
    expect(await screen.findByRole('heading', { level: 1, name: 'Spring run' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('publication-sponsor')).toHaveTextContent('Rhea Co'))
    expect(screen.getByTestId('publication-sponsor')).toHaveAttribute('href', `/qb/users/${SPONSOR_ID}`)
    const settings = screen.getByTestId('publication-settings')
    expect(settings).toHaveTextContent('3 of 5')
    expect(settings).toHaveTextContent('300 s')
    // The administration shows the ledger's real currency.
    expect(settings).toHaveTextContent(/10,000/)
    expect(settings).toHaveTextContent(/JPY|¥|￥/)
    expect(within(screen.getByTestId('publication-tiers')).getAllByTestId('publication-tier').map((r) => r.textContent))
      .toEqual([expect.stringMatching(/^0 – 2/), expect.stringMatching(/^3 – 4/), expect.stringMatching(/^5 – 5/)])

    const preview = await screen.findByTestId('version-preview')
    expect(within(preview).getByTestId('preview-title')).toHaveTextContent('World capitals')
    const questions = within(preview).getAllByTestId('preview-question')
    expect(questions).toHaveLength(2)
    expect(questions[0]).toHaveTextContent('Capital of France?')
    expect(questions[1]).toHaveTextContent('Capital of Japan?')
    // One correct option each: single choices, as the taker is shown them (D-Q5-2).
    expect(questions[0]).toHaveTextContent('Single choice')
    const options = within(preview).getAllByTestId('preview-option')
    expect(options.map((o) => o.getAttribute('data-correct'))).toEqual(['true', 'false', 'false', 'true'])
    expect(options[0]).toHaveTextContent('Paris')
    expect(within(options[0]).getByText('Correct answer')).toBeInTheDocument()
    expect(within(options[1]).queryByText('Correct answer')).not.toBeInTheDocument()
    expect(options[3]).toHaveTextContent('(image only)')
    // Every file is shown by its own preview: the cover, the pictures, and PDFs and sounds as downloads.
    const files = within(preview).getAllByTestId('file')
    expect(files.map((f) => f.getAttribute('data-file'))).toEqual(expect.arrayContaining([COVER, PICTURE, PDF]))
    expect(files.find((f) => f.getAttribute('data-file') === PDF)).toHaveAttribute('data-image', 'false')
    expect(within(preview).getAllByTestId('preview-material')).toHaveLength(6)
  })

  it('opens web links in a new tab and makes no other link live', async () => {
    renderPublication()
    const preview = await screen.findByTestId('version-preview')
    const links = within(preview).getAllByTestId('material-link')
    expect(links).toHaveLength(1)
    expect(links[0]).toHaveAttribute('href', 'https://video.example/watch?v=1')
    expect(links[0]).toHaveAttribute('target', '_blank')
    expect(links[0]).toHaveAttribute('rel', 'noopener noreferrer')
    expect(preview).toHaveTextContent('javascript:alert(3)')
    // The introduction's Markdown: no script, no javascript: link.
    const intro = within(preview).getByTestId('preview-intro')
    expect(intro.querySelector('script')).toBeNull()
    for (const anchor of preview.querySelectorAll('a')) {
      expect(anchor.getAttribute('href') ?? '').not.toMatch(/^javascript:/i)
    }
    expect(within(intro).getByText('notes').tagName).toBe('STRONG')
  })

  it('offers the decision while the publication waits, and reads again until its state follows', async () => {
    let reads = 0
    server.entities.set(`${DATASET.publication}/${PUBLICATION_ID}`, () => {
      reads += 1
      return publication(reads <= 2 ? 'PENDING_REVIEW' : 'SCHEDULED')
    })
    renderPublication()
    const panel = await screen.findByTestId('approval-panel')
    expect(panel).toHaveAttribute('data-request', REQUEST_ID)
    await userEvent.click(panel)
    expect(server.decided).toHaveBeenCalledWith(REQUEST_ID)
    expect(screen.queryByTestId('approval-panel')).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('publication-review')).toHaveAttribute('data-status', 'SCHEDULED'),
      { timeout: 5_000 })
    expect(screen.getByTestId('publication-not-pending')).toBeInTheDocument()
    const settled = reads
    await new Promise((resolve) => setTimeout(resolve, 2_500))
    expect(reads).toBe(settled)
  }, 15_000)

  it('reviews a running publication\'s switch with the version it asks for (Q51)', async () => {
    const next = '0192f0a0-0000-7000-8000-0000000000e2'
    server.entities.set(`${DATASET.publication}/${PUBLICATION_ID}`, { ...publication('LIVE'),
      pendingVersionId: next, pendingVersionLabel: 'v2', pendingRequestId: 'switch-request',
      pendingSince: '2026-10-20T00:00:00Z' })
    server.entities.set(`${DATASET.version}/${next}`, { versionId: next, label: 'v2', title: 'World capitals, revised',
      contentHash: 'b'.repeat(64), versionedAt: '2026-10-19T00:00:00Z', content: SNAPSHOT_JSON })
    renderPublication()
    expect(await screen.findByTestId('publication-switch')).toHaveTextContent('from version v1 to v2')
    const panel = await screen.findByTestId('approval-panel')
    expect(panel).toHaveAttribute('data-request', 'switch-request')
    expect(within(await screen.findByTestId('version-preview')).getByTestId('preview-title'))
      .toHaveTextContent('World capitals')
    expect(screen.getByTestId('publication-settings')).toHaveTextContent('World capitals, revised')
  })

  it('links a content administrator to taking a running publication down (Q10b)', async () => {
    server.permissions = new Set([...server.permissions, 'qb.admin.content.write'])
    server.entities.set(`${DATASET.publication}/${PUBLICATION_ID}`, publication('LIVE'))
    renderPublication()
    const pause = await screen.findByTestId('publication-admin-pause')
    expect(pause).toHaveAttribute('href',
      `/processes/QB_PUBLICATION_ADMIN_PAUSE/1?publicationId=${PUBLICATION_ID}`)
    expect(screen.getByTestId('publication-admin-end')).toHaveAttribute('href',
      `/processes/QB_PUBLICATION_ADMIN_END/1?publicationId=${PUBLICATION_ID}`)
    expect(screen.queryByTestId('publication-admin-resume')).not.toBeInTheDocument()
  })

  it('offers an administrator\'s pause its resume, with the reason', async () => {
    server.permissions = new Set([...server.permissions, 'qb.admin.content.write'])
    server.entities.set(`${DATASET.publication}/${PUBLICATION_ID}`, { ...publication('PAUSED'), pausedBy: 'ADMIN',
      adminReason: 'Reported as misleading' })
    renderPublication()
    expect(await screen.findByTestId('publication-admin-resume')).toBeInTheDocument()
    expect(screen.queryByTestId('publication-admin-pause')).not.toBeInTheDocument()
    expect(screen.getByTestId('publication-admin-paused')).toBeInTheDocument()
    expect(screen.getByTestId('publication-not-pending')).toHaveTextContent('Reported as misleading')
  })

  it('shows the takedowns of the quiz before the decision (Q10b, review M-d)', async () => {
    server.templates.set(TEMPLATE.quizTakedowns, [{ publicationId: 'p-old', name: 'Old run', status: 'ENDED',
      endReason: 'ADMIN', endedAt: '2026-10-10T00:00:00Z', adminReason: 'Copied answers' },
    { publicationId: 'p-ban', name: 'Banned run', status: 'PAUSED', pausedBy: 'ADMIN',
      pausedAt: '2026-10-11T00:00:00Z', adminReason: 'QB_ACCOUNT_SUSPENDED' }])
    renderPublication()
    const section = await screen.findByTestId('publication-takedowns')
    expect(within(section).getAllByTestId('publication-takedown')).toHaveLength(2)
    expect(section).toHaveTextContent('Copied answers')
    expect(section).toHaveTextContent('Account suspended')
    expect(section).not.toHaveTextContent('QB_ACCOUNT_SUSPENDED')
    expect(await screen.findByTestId('approval-panel')).toBeInTheDocument()
  })

  it('offers no decision while the takedowns of the quiz cannot be read', async () => {
    server.templates.set(TEMPLATE.quizTakedowns, new ApiError(500, { detail: 'Down' }))
    renderPublication()
    expect(await screen.findByTestId('publication-takedowns-failed', {}, { timeout: 5_000 })).toBeInTheDocument()
    expect(screen.queryByTestId('approval-panel')).not.toBeInTheDocument()
    expect(screen.getByTestId('publication-needs-content')).toBeInTheDocument()
  }, 10_000)

  it('words a ban\'s pause without the ban\'s reason (Q10b, review L3)', async () => {
    server.entities.set(`${DATASET.publication}/${PUBLICATION_ID}`, { ...publication('PAUSED'), pausedBy: 'ADMIN',
      adminReason: 'QB_ACCOUNT_SUSPENDED' })
    renderPublication()
    const notPending = await screen.findByTestId('publication-not-pending')
    expect(notPending).toHaveTextContent('Account suspended')
    expect(notPending).not.toHaveTextContent('QB_ACCOUNT_SUSPENDED')
  })

  it('offers nothing to take down without the permission, or once ended', async () => {
    server.entities.set(`${DATASET.publication}/${PUBLICATION_ID}`, publication('LIVE'))
    const { unmount } = renderPublication()
    expect(await screen.findByTestId('publication-not-pending')).toBeInTheDocument()
    expect(screen.queryByTestId('publication-admin')).not.toBeInTheDocument()
    unmount()
    server.permissions = new Set([...server.permissions, 'qb.admin.content.write'])
    server.entities.set(`${DATASET.publication}/${PUBLICATION_ID}`, publication('ENDED'))
    renderPublication()
    expect(await screen.findByTestId('publication-not-pending')).toBeInTheDocument()
    expect(screen.queryByTestId('publication-admin')).not.toBeInTheDocument()
  })

  it('offers no decision once the publication left the review, and shows why it was sent back', async () => {
    server.entities.set(`${DATASET.publication}/${PUBLICATION_ID}`, publication('DRAFT'))
    renderPublication()
    expect(await screen.findByTestId('publication-not-pending')).toHaveTextContent('Too easy.')
    expect(screen.queryByTestId('approval-panel')).not.toBeInTheDocument()
  })

  it('shows no content and no decision to whom the version is refused', async () => {
    server.permissions = new Set(['qb.admin.publication.read', 'qb.admin.finance.read'])
    server.entities.set(`${DATASET.version}/${VERSION_ID}`, new ApiError(403, { detail: 'Forbidden' }))
    renderPublication()
    expect(await screen.findByText(/do not include the quiz content/)).toBeInTheDocument()
    expect(screen.queryByTestId('version-preview')).not.toBeInTheDocument()
    expect(screen.queryByTestId('approval-panel')).not.toBeInTheDocument()
  })

  it('says when there is no such publication', async () => {
    server.entities.delete(`${DATASET.publication}/${PUBLICATION_ID}`)
    renderPublication()
    expect(await screen.findByText('There is no such publication.')).toBeInTheDocument()
  })
})

describe('PublicationReviewPage, what the decision waits for', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('offers no decision while the tiers cannot be read, and says so', async () => {
    server.datasets.delete(DATASET.tier)
    renderPublication()
    expect(await screen.findByTestId('version-preview')).toBeInTheDocument()
    const settings = screen.getByTestId('publication-settings')
    expect(await within(settings).findByRole('alert')).toHaveTextContent('Forbidden')
    expect(screen.queryByTestId('publication-tiers')).not.toBeInTheDocument()
    expect(screen.getByTestId('publication-needs-content')).toBeInTheDocument()
    expect(screen.queryByTestId('approval-panel')).not.toBeInTheDocument()
  })

  it('reads the tiers and the version for the request the publication waits for', async () => {
    renderPublication()
    await screen.findByTestId('approval-panel')
    const tierCalls = server.post.mock.calls.filter(([, o]) => o.params.path.resourceId === DATASET.tier)
    expect(tierCalls).toHaveLength(1)
    expect(server.get.mock.calls.filter(([, o]) => o?.params?.path?.resourceId === DATASET.version)).toHaveLength(1)
  })

  it('shows the sponsor\'s funds to finance only', async () => {
    renderPublication()
    await screen.findByTestId('version-preview')
    expect(screen.queryByTestId('publication-funds')).not.toBeInTheDocument()
    expect(server.runQuery).not.toHaveBeenCalledWith(TEMPLATE.publicationFunds, expect.anything())
  })

  it('shows the funds to finance', async () => {
    server.permissions.add('qb.admin.finance.read')
    renderPublication()
    const funds = await screen.findByTestId('publication-funds')
    expect(funds).toHaveTextContent('Yes')
    expect(funds).toHaveTextContent(/300/)
  })

  it('says when only this level was approved, without waiting for a state', async () => {
    server.decision = 'PENDING'
    renderPublication()
    await userEvent.click(await screen.findByTestId('approval-panel'))
    expect(await screen.findByTestId('decision-level')).toBeInTheDocument()
    expect(screen.queryByTestId('decision-updating')).not.toBeInTheDocument()
    expect(screen.queryByTestId('approval-panel')).not.toBeInTheDocument()
  })

  it('stops waiting after a while, says the state has not updated, and reads again on request', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    renderPublication()
    const panel = await screen.findByTestId('approval-panel')
    await act(async () => panel.click())
    expect(await screen.findByTestId('decision-updating')).toBeInTheDocument()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(31_000)
    })
    expect(await screen.findByTestId('decision-not-updated')).toBeInTheDocument()
    const reads = server.get.mock.calls.filter(([, o]) => o?.params?.path?.resourceId === DATASET.publication).length
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000)
    })
    expect(server.get.mock.calls.filter(([, o]) => o?.params?.path?.resourceId === DATASET.publication))
      .toHaveLength(reads)
    await act(async () => screen.getByTestId('decision-reload').click())
    expect(await screen.findByTestId('decision-updating')).toBeInTheDocument()
  })
})

describe('ReviewsPage paging', () => {
  it('pages the queue and counts all of it', async () => {
    server.templates.set(TEMPLATE.publications, Array.from({ length: 25 }, (_, i) => ({
      ...publication(), publicationId: `p-${i}`, name: `Run ${i}` })))
    renderAt('/qb/reviews', '/qb/reviews', <ReviewsPage />)
    expect(await screen.findAllByTestId('queue-publication')).toHaveLength(20)
    await waitFor(() => expect(screen.getByTestId('tab-publications')).toHaveTextContent('Publications (25)'))
  })
})

describe('SponsorReviewPage', () => {
  it('shows the application with the address and offers the decision while it waits', async () => {
    renderAt(`/qb/reviews/sponsors/${SPONSOR_ID}`, '/qb/reviews/sponsors/:userId', <SponsorReviewPage />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Rhea Co' })).toBeInTheDocument()
    const application = screen.getByTestId('sponsor-application')
    expect(application).toHaveTextContent('Rhea')
    expect(await screen.findByTestId('sponsor-email')).toHaveTextContent('rhea@example.com (verified)')
    expect(within(screen.getByTestId('sponsor-intro')).getByText('questions').tagName).toBe('STRONG')
    expect(within(application).getByTestId('file')).toHaveAttribute('data-file', 'avatar-1')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.users, { params: { userId: SPONSOR_ID }, limit: 1 })
    expect(screen.getByTestId('approval-panel')).toHaveAttribute('data-request', 'sponsor-request')
  })

  it('offers no decision without the permission to review sponsors', async () => {
    server.permissions = new Set(['qb.admin.users.read'])
    renderAt(`/qb/reviews/sponsors/${SPONSOR_ID}`, '/qb/reviews/sponsors/:userId', <SponsorReviewPage />)
    expect(await screen.findByText('Deciding needs the permission to review sponsors.')).toBeInTheDocument()
    expect(screen.queryByTestId('approval-panel')).not.toBeInTheDocument()
    // Q22: the methods are shown to everyone, changed only with the permission; without a setting of their own the
    // parameter's default (Q28).
    expect(await screen.findByTestId('sponsor-methods-current'))
      .toHaveTextContent("Allowed now, by the platform's default: OFFLINE_REDEEM")
    expect(screen.queryByTestId('sponsor-methods-save')).toBeNull()
  })

  it('lets an administrator allow cash rewards, keeping the default\'s prize (Q22, Q28)', async () => {
    server.permissions.add('qb.admin.reward-methods')
    renderAt(`/qb/reviews/sponsors/${SPONSOR_ID}`, '/qb/reviews/sponsors/:userId', <SponsorReviewPage />)
    await userEvent.click(await screen.findByTestId('sponsor-method-SPONSOR_CASH'))
    // Q33: the fourth method is offered too, and stays off unless chosen.
    expect(screen.getByTestId('sponsor-method-DIGITAL_PRIZE')).toHaveAttribute('data-state', 'unchecked')
    await userEvent.type(screen.getByTestId('sponsor-methods-note'), 'Trusted shop')
    await userEvent.click(screen.getByTestId('sponsor-methods-save'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROCESS.rewardMethodsSet,
      { userId: SPONSOR_ID, methods: ['SPONSOR_CASH', 'OFFLINE_REDEEM'], note: 'Trusted shop' }))
  })
})

describe('UserSummaryPage', () => {
  const renderUser = () => renderAt(`/qb/users/${SPONSOR_ID}`, '/qb/users/:userId', <UserSummaryPage />)

  it('asks nothing about an address that names no user id', async () => {
    renderAt('/qb/users/not-a-user', '/qb/users/:userId', <UserSummaryPage />)
    expect(await screen.findByText('There is no such user.')).toBeInTheDocument()
    expect(server.runQuery).not.toHaveBeenCalled()
  })

  it('shows the profile and attempts, and no money without finance', async () => {
    renderUser()
    expect(await screen.findByRole('heading', { level: 1, name: 'Rhea' })).toBeInTheDocument()
    expect(screen.getByTestId('user-profile')).toHaveTextContent('Japan, Kyoto')
    expect(screen.getByTestId('user-ban')).toHaveTextContent('Not banned')
    expect(screen.getByTestId('user-application')).toHaveAttribute('href', `/qb/reviews/sponsors/${SPONSOR_ID}`)
    expect(await within(screen.getByTestId('user-attempts')).findByText('Spring run')).toBeInTheDocument()
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.attempts,
      { filters: [{ field: 'userId', op: 'eq', value: SPONSOR_ID }], offset: 0, limit: 20 })
    expect(screen.queryByTestId('user-finance')).not.toBeInTheDocument()
    expect(screen.getByTestId('user-attempts')).not.toHaveTextContent(/7,777/)
    for (const template of [TEMPLATE.transfers, TEMPLATE.topUps, TEMPLATE.payoutAccounts, TEMPLATE.attemptRewards]) {
      expect(server.runQuery).not.toHaveBeenCalledWith(template, expect.anything())
    }
  })

  it('shows transfers, the payout account by its last four characters and top-ups to finance', async () => {
    server.permissions.add('qb.admin.finance.read')
    renderUser()
    const finance = await screen.findByTestId('user-finance')
    expect(await within(finance).findByText('WXYZ')).toBeInTheDocument()
    expect(finance).not.toHaveTextContent('acct_1234567890WXYZ')
    expect(finance).toHaveTextContent(/1,200/)
    expect(finance).toHaveTextContent(/5,000/)
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.topUps,
      { filters: [{ field: 'sponsorId', op: 'eq', value: SPONSOR_ID }], offset: 0, limit: 20 })
    expect(await within(screen.getByTestId('user-rewards')).findByText(/7,777/)).toBeInTheDocument()
  })

  it('shows the acceptances of the terms (Q7b)', async () => {
    server.templates.set(TEMPLATE.consents, [{ consentId: 'c1', kind: 'TERMS_APP', versionNo: 3,
      acceptedAt: '2026-10-16T00:00:00Z', entry: 'app', source: 'RECONSENT', effectiveAt: '2026-10-15T00:00:00Z' }])
    renderUser()
    const consents = await screen.findByTestId('user-consents')
    expect(await within(consents).findByText('app')).toBeInTheDocument()
    expect(consents).toHaveTextContent('3')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.consents,
      { params: { userId: SPONSOR_ID }, offset: 0, limit: 20 })
  })

  it('shows the ban with its reason', async () => {
    server.templates.set(TEMPLATE.users, [{ ...USER, banned: true }])
    server.datasets.set(DATASET.ban, [{ userId: SPONSOR_ID, banned: true, reason: 'Shared answers.',
      bannedAt: '2026-10-12T00:00:00Z', bannedBy: 'boss' }])
    renderUser()
    expect(await screen.findByText('Shared answers.')).toBeInTheDocument()
    expect(screen.getByTestId('user-ban')).toHaveAttribute('data-banned', 'true')
  })
})

describe('textIn', () => {
  it('shows a multilingual name in the interface language, else English, else Japanese', () => {
    const name = { en: 'Japan', ja: '日本', zh: '日本' }
    expect(textIn(name, 'zh-CN')).toBe('日本')
    expect(textIn(name, 'fr')).toBe('Japan')
    expect(textIn({ ja: '日本' }, 'fr')).toBe('日本')
    expect(textIn(null, 'en')).toBe('')
  })
})
