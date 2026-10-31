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
import ReviewsPage from './ReviewsPage'
import SponsorPageReviewPage from './SponsorPageReviewPage'
import SponsorPagesPage from './SponsorPagesPage'
import { PERMISSION, SPONSOR_PAGE_PROCESS, TEMPLATE } from './ids'
import { messages } from './messages'
import { pageMessages } from './page-messages'

type Row = Record<string, unknown>

/** The server as the sponsor-page pages see it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  pages: [] as Record<string, unknown>[],
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
    useAuth: () => ({ can: (p: string) => server.permissions.has(p) }),
    ApprovalPanel: ({ requestId }: { requestId: string }) => (
      <div data-testid="approval-panel" data-request={requestId}>decide</div>
    ),
    FilePreview: ({ fileId }: { fileId: string }) => <img data-testid="file-preview" data-file={fileId} alt="" />,
  }
})

const SPONSOR = '0192f0a0-0000-7000-8000-00000000c001'

const page = (change: Row = {}): Row => ({ userId: SPONSOR, handle: 'acme-bakery', language: 'en',
  headline: 'Fresh bread daily', story: 'Since **1990**.', logo: 'f-logo', cover: 'f-cover',
  website: 'https://example.com/shop', status: 'PENDING', approvalRequestId: 'r1',
  submittedAt: '2026-10-30T01:00:00Z', publishedAt: null, rejectReason: null, takenDownAt: null, takedownReason: null,
  reviewRequired: false, editedAt: '2026-10-30T01:00:00Z', company: 'Acme', ...change })

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set([PERMISSION.reviewSponsor, PERMISSION.publicationRead, PERMISSION.contentWrite])
  server.pages = [page()]
  server.runQuery.mockReset().mockImplementation(async (id: string, options: {
    count?: boolean; params?: Row; filters?: { field: string; value: unknown }[]
  } = {}) => {
    let items = id === TEMPLATE.sponsorPages ? server.pages : []
    const status = options.filters?.find((f) => f.field === 'status')?.value
    if (status) items = items.filter((row) => row.status === status)
    if (options.params?.userId) items = items.filter((row) => row.userId === options.params?.userId)
    return { items, offset: 0, limit: 20, ...(options.count ? { total: items.length } : {}) }
  })
  server.get.mockReset().mockImplementation(async (path: string) => {
    if (path === '/api/dictionaries/{urn}') {
      return [{ code: 'PENDING', label: 'In review' }, { code: 'PUBLISHED', label: 'Published' },
        { code: 'TAKEN_DOWN', label: 'Taken down' }, { code: 'en', label: 'English' }]
    }
    throw new ApiError(404, { detail: 'Not found' })
  })
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

describe("sponsors' pages (Q60a-3)", () => {
  it('have every text in every language', () => {
    const keys = (value: object, prefix = ''): string[] => Object.entries(value).flatMap(([key, child]) =>
      typeof child === 'object' && child !== null ? keys(child, `${prefix}${key}.`) : [`${prefix}${key}`])
    const english = keys(pageMessages.en)
    expect(keys(pageMessages.zh)).toEqual(english)
    expect(keys(pageMessages.ja)).toEqual(english)
  })

  it('wait in their own tab of the review queue', async () => {
    renderAt('/qb/reviews?tab=pages', '/qb/reviews', <ReviewsPage />)
    await waitFor(() => expect(screen.getByTestId('tab-sponsor-pages')).toHaveTextContent('Sponsor pages (1)'))
    const row = await screen.findByTestId('queue-sponsor-page')
    expect(within(row).getByTestId('sponsor-page-link')).toHaveAttribute('href', `/qb/sponsor-pages/${SPONSOR}`)
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.sponsorPages, expect.objectContaining({
      filters: [{ field: 'status', op: 'eq', value: 'PENDING' }] }))
  })

  it("are not offered in the queue to a sponsor reviewer who cannot read the publications' list", async () => {
    server.permissions = new Set([PERMISSION.reviewSponsor])
    renderAt('/qb/reviews?tab=pages', '/qb/reviews', <ReviewsPage />)
    expect(await screen.findByTestId('tab-sponsors')).toBeInTheDocument()
    expect(screen.queryByTestId('tab-sponsor-pages')).toBeNull()
    expect(server.runQuery).not.toHaveBeenCalledWith(TEMPLATE.sponsorPages, expect.anything())
  })

  it('are listed, by status', async () => {
    server.pages = [page(), page({ userId: 'u2', handle: 'beta', company: 'Beta', status: 'PUBLISHED' })]
    const { container } = renderAt('/qb/sponsor-pages', '/qb/sponsor-pages', <SponsorPagesPage />)
    expect(await screen.findAllByTestId('sponsor-page-row')).toHaveLength(2)
    await expectAccessible(container)
    const user = userEvent.setup()
    await user.click(screen.getByTestId('sponsor-pages-status'))
    await user.click(await screen.findByRole('option', { name: 'Published' }))
    expect(await screen.findAllByTestId('sponsor-page-row')).toHaveLength(1)
    expect(screen.getByTestId('sponsor-page-row')).toHaveAttribute('data-id', 'u2')
  })

  it('show a waiting page with its content and the decision', async () => {
    const { container } = renderAt(`/qb/sponsor-pages/${SPONSOR}`, '/qb/sponsor-pages/:userId', <SponsorPageReviewPage />)
    expect(await screen.findByTestId('sponsor-page-review')).toHaveAttribute('data-status', 'PENDING')
    expect(screen.getByTestId('sponsor-page-headline')).toHaveTextContent('Fresh bread daily')
    expect(within(screen.getByTestId('sponsor-page-story')).getByText('1990').tagName).toBe('STRONG')
    expect(screen.getByTestId('sponsor-page-website')).toHaveAttribute('href', 'https://example.com/shop')
    expect(screen.getAllByTestId('file-preview').map((image) => image.dataset.file)).toEqual(['f-logo', 'f-cover'])
    // Q65b: the logo and the story come from the sponsor's profile, which the page's review does not cover.
    expect(screen.getByTestId('sponsor-page-from-profile')).toHaveTextContent('reviewed with their onboarding')
    expect(screen.getByTestId('approval-panel')).toHaveAttribute('data-request', 'r1')
    expect(screen.queryByTestId('sponsor-page-takedown')).toBeNull()
    await expectAccessible(container)
  })

  it("take a published page down through headquarters' process, or the operator's", async () => {
    server.pages = [page({ status: 'PUBLISHED', approvalRequestId: null, publishedAt: '2026-10-30T02:00:00Z' })]
    const { unmount } = renderAt(`/qb/sponsor-pages/${SPONSOR}`, '/qb/sponsor-pages/:userId', <SponsorPageReviewPage />)
    expect(await screen.findByTestId('sponsor-page-takedown-link')).toHaveAttribute('href',
      `/processes/${SPONSOR_PAGE_PROCESS.takedown}/1?userId=${SPONSOR}`)
    expect(screen.getByTestId('sponsor-page-not-pending')).toBeInTheDocument()
    unmount()
    server.permissions = new Set([PERMISSION.reviewSponsor, PERMISSION.publicationRead, PERMISSION.operatorUse])
    renderAt(`/qb/sponsor-pages/${SPONSOR}`, '/qb/sponsor-pages/:userId', <SponsorPageReviewPage />)
    expect(await screen.findByTestId('sponsor-page-takedown-link')).toHaveAttribute('href',
      `/processes/${SPONSOR_PAGE_PROCESS.operatorTakedown}/1?userId=${SPONSOR}`)
  })

  it("show a ban's takedown in words, and a page that is not there", async () => {
    server.pages = [page({ status: 'TAKEN_DOWN', approvalRequestId: null, takenDownAt: '2026-10-30T03:00:00Z',
      takedownReason: 'QB_ACCOUNT_SUSPENDED', reviewRequired: true, website: 'https://example.com/"x' })]
    const { unmount } = renderAt(`/qb/sponsor-pages/${SPONSOR}`, '/qb/sponsor-pages/:userId', <SponsorPageReviewPage />)
    expect(await screen.findByText('Account suspended (the sponsor was banned)')).toBeInTheDocument()
    expect(screen.getByText('Taken down: it goes back only after a review.')).toBeInTheDocument()
    // A website not made a link is still shown as written.
    expect(screen.getByTestId('sponsor-page-website-text')).toHaveTextContent('https://example.com/"x')
    unmount()
    server.pages = []
    renderAt('/qb/sponsor-pages/other', '/qb/sponsor-pages/:userId', <SponsorPageReviewPage />)
    expect(await screen.findByText(/There is no such sponsor page/)).toBeInTheDocument()
  })
})
