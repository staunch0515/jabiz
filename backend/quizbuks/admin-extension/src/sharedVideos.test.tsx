import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import SharedVideoPage from './SharedVideoPage'
import SharedVideosPage from './SharedVideosPage'
import { duration } from './shared-video-api'
import { PERMISSION, SHARED_VIDEO_PROCESS, TEMPLATE } from './ids'
import { messages } from './messages'
import { sharedVideoMessages } from './shared-video-messages'

type Row = Record<string, unknown>

/** The server as the pages see it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  entry: 'operator',
  permissions: new Set<string>(),
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
    useAuth: () => ({ entry: server.entry, can: (p: string) => server.permissions.has(p) }),
    ApprovalPanel: ({ requestId }: { requestId: string }) => (
      <div data-testid="approval-panel" data-request={requestId}>decide</div>
    ),
  }
})

const video = (change: Row = {}): Row => ({ sharedVideoId: 'v1', providerId: 'sponsor-1', company: 'Cat Co',
  operatorId: 'op-kanto', operatorName: 'Kanto Partners',
  title: JSON.stringify({ zh: '猫', ja: '猫の動画', en: 'Cats <b>live</b>' }),
  url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ', durationSec: 95, vertical: true, topics: 'pets,travel',
  status: 'PENDING', approvalRequestId: 'r1', sharedAt: '2026-11-08T00:00:00Z', approvedAt: null, rejectReason: null,
  withdrawnAt: null, takenDownAt: null, takedownReason: null, takedownOperatorId: null, usesRunning: '0', ...change })

let rows: Row[] = []
let uses: Row[] = []

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.entry = 'operator'
  server.permissions = new Set([PERMISSION.sharedVideoRead, PERMISSION.sharedVideoWrite, PERMISSION.reviewPublication,
    PERMISSION.operatorUse])
  rows = [video(), video({ sharedVideoId: 'v2', status: 'APPROVED', approvalRequestId: 'r2', usesRunning: '4',
    vertical: false, durationSec: 3725 })]
  uses = []
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { filters?: Row[]; count?: boolean } = {}) => {
    let items: Row[] = []
    if (id === TEMPLATE.sharedVideos) {
      items = rows.filter((row) => (options.filters ?? []).every((f) => f.field === 'operatorId'
        || row[String(f.field)] === f.value))
    } else if (id === TEMPLATE.sharedVideoUses) {
      items = uses
    } else if (id === TEMPLATE.operators) {
      items = [{ operatorId: 'op-kanto', name: 'Kanto Partners', contactEmail: 'ops@kanto.example' }]
    }
    return { items, offset: 0, limit: 20, ...(options.count ? { total: items.length } : {}) }
  })
  server.get.mockReset().mockImplementation(async () => { throw new ApiError(404, { detail: 'Not found' }) })
})

function renderAt(at: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={[at]}>
          <Routes>
            <Route path="/qb/shared-videos" element={<SharedVideosPage />} />
            <Route path="/qb/shared-videos/:sharedVideoId" element={<SharedVideoPage />} />
            <Route path="*" element={<p data-testid="elsewhere">elsewhere</p>} />
          </Routes>
        </MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('shared videos (F-S12)', () => {
  it('have every text in every language, without the retired names', () => {
    const keys = (value: object, prefix = ''): string[] => Object.entries(value).flatMap(([key, child]) =>
      typeof child === 'object' && child !== null ? keys(child, `${prefix}${key}.`) : [`${prefix}${key}`])
    const english = keys(sharedVideoMessages.en)
    expect(keys(sharedVideoMessages.zh)).toEqual(english)
    expect(keys(sharedVideoMessages.ja)).toEqual(english)
    expect(JSON.stringify(sharedVideoMessages)).not.toMatch(/后台|管理画面|運営会社|back ?office/i)
    expect(duration(95)).toBe('1:35')
    expect(duration(3725)).toBe('1:02:05')
  })

  it('lists those in review first, each linked to its review', async () => {
    const { container } = renderAt('/qb/shared-videos')
    const listed = await screen.findAllByTestId('shared-video-row')
    expect(listed).toHaveLength(1)
    // The title as plain text.
    expect(within(listed[0]).getByTestId('shared-video-link')).toHaveTextContent('Cats <b>live</b>')
    expect(within(listed[0]).getByTestId('shared-video-link')).toHaveAttribute('href', '/qb/shared-videos/v1')
    expect(listed[0]).toHaveTextContent('Vertical · 1:35')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.sharedVideos, expect.objectContaining({
      filters: [{ field: 'status', op: 'eq', value: 'PENDING' }] }))
    // An operator's session chooses no operator.
    expect(screen.queryByTestId('shared-videos-operator-filter')).toBeNull()
    await expectAccessible(container)
    await userEvent.click(screen.getByTestId('shared-videos-show'))
    await userEvent.click(await screen.findByRole('option', { name: 'All' }))
    expect(await screen.findAllByTestId('shared-video-row')).toHaveLength(2)
  })

  it('lets the platform filter by operator, with the provider\'s operator in each row', async () => {
    server.entry = 'admin'
    server.permissions = new Set([PERMISSION.sharedVideoRead, PERMISSION.sharedVideoWrite, PERMISSION.operatorsRead])
    renderAt('/qb/shared-videos?operator=op-kanto')
    const listed = await screen.findAllByTestId('shared-video-row')
    expect(listed[0]).toHaveTextContent('Kanto Partners')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.sharedVideos, expect.objectContaining({
      filters: [{ field: 'operatorId', op: 'eq', value: 'op-kanto' }, { field: 'status', op: 'eq', value: 'PENDING' }] }))
  })

  it('shows a video in review with the approval panel to its operator\'s reviewer', async () => {
    const { container } = renderAt('/qb/shared-videos/v1')
    const page = await screen.findByTestId('shared-video-page')
    expect(page).toHaveAttribute('data-status', 'PENDING')
    expect(screen.getByTestId('approval-panel')).toHaveAttribute('data-request', 'r1')
    expect(screen.getByTestId('shared-video-watch')).toHaveAttribute('href',
      'https://www.youtube.com/watch?v=dQw4w9WgXcQ')
    expect(screen.getByTestId('shared-video-content')).toHaveTextContent('猫の動画')
    // Nothing to take down while it is in review.
    expect(screen.queryByTestId('shared-video-takedown')).toBeNull()
    await expectAccessible(container)
  })

  it('offers the takedown of an approved video on the process page, and no decision', async () => {
    const { container } = renderAt('/qb/shared-videos/v2')
    await screen.findByTestId('shared-video-page')
    expect(screen.getByTestId('shared-video-not-pending')).toHaveTextContent('Shared')
    expect(screen.queryByTestId('approval-panel')).toBeNull()
    expect(screen.getByTestId('shared-video-uses')).toHaveTextContent('4 publications use it now.')
    expect(screen.getByTestId('shared-video-takedown-link')).toHaveAttribute('href',
      `/processes/${SHARED_VIDEO_PROCESS.takedown}/1?sharedVideoId=v2`)
    await expectAccessible(container)
  })

  it('shows who took a video down and why, and offers nothing more', async () => {
    rows = [video({ status: 'TAKEN_DOWN', takenDownAt: '2026-11-08T03:00:00Z', takedownReason: 'Infringes',
      takedownOperatorId: null })]
    renderAt('/qb/shared-videos/v1')
    const content = await screen.findByTestId('shared-video-content')
    expect(content).toHaveTextContent('Infringes')
    expect(content).toHaveTextContent('The platform')
    expect(screen.queryByTestId('shared-video-takedown')).toBeNull()
  })

  it('lists the publications that used it, those the takedown ended with why and when (F-C3)', async () => {
    rows = [video({ status: 'TAKEN_DOWN', takenDownAt: '2026-11-08T03:00:00Z', takedownReason: 'Infringes',
      usesRunning: '0' })]
    uses = [
      { publicationId: 'p1', name: 'Autumn quiz', publisherKind: 'SPONSOR', publisherId: 'u1', status: 'ENDED',
        startAt: '2026-11-01T00:00:00Z', endAt: '2026-12-01T00:00:00Z', endReason: 'SHARED_VIDEO',
        endedAt: '2026-11-08T03:01:00Z' },
      { publicationId: 'p2', name: 'Winter quiz', publisherKind: 'SPONSOR', publisherId: 'u2', status: 'LIVE',
        startAt: '2026-11-02T00:00:00Z', endAt: '2026-12-01T00:00:00Z', endReason: null, endedAt: null },
    ]
    const { container } = renderAt('/qb/shared-videos/v1')
    const listed = await screen.findAllByTestId('shared-video-use')
    expect(listed).toHaveLength(2)
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.sharedVideoUses, expect.objectContaining({
      params: { sharedVideoId: 'v1' } }))
    expect(within(listed[0]).getByTestId('shared-video-use-link')).toHaveAttribute('href',
      '/qb/reviews/publications/p1')
    expect(within(listed[0]).getByTestId('shared-video-use-link')).toHaveTextContent('Autumn quiz')
    expect(listed[0]).toHaveAttribute('data-end-reason', 'SHARED_VIDEO')
    expect(within(listed[0]).getByTestId('shared-video-use-ended').textContent).not.toBe('')
    expect(within(listed[1]).getByTestId('shared-video-use-ended')).toHaveTextContent('')
    // The count of those running is none after the takedown; the list still says which ended.
    expect(screen.getByTestId('shared-video-uses')).toHaveTextContent('0 publications use it now.')
    await expectAccessible(container)
  })

  it('says when no publication the session reads used it', async () => {
    renderAt('/qb/shared-videos/v2')
    expect(await screen.findByTestId('shared-video-uses-none')).toHaveTextContent('No publication you can see')
  })

  it('says a video of another operator\'s Sponsor is not found', async () => {
    rows = []
    renderAt('/qb/shared-videos/v9')
    expect(await screen.findByText(/not one of your Sponsors/)).toBeInTheDocument()
  })

  it('offers neither decision nor takedown to who may only read', async () => {
    server.entry = 'admin'
    server.permissions = new Set([PERMISSION.sharedVideoRead])
    renderAt('/qb/shared-videos/v1')
    await screen.findByTestId('shared-video-page')
    expect(screen.queryByTestId('approval-panel')).toBeNull()
    expect(screen.getByTestId('shared-video-review')).toHaveTextContent('The operator\'s reviewers decide.')
  })
})
