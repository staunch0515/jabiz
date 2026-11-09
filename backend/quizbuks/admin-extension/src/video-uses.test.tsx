import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import VideoUsesPage from './VideoUsesPage'
import { videoUseMessages } from './video-use-messages'
import { PERMISSION, TEMPLATE } from './ids'
import { messages } from './messages'

/** The server as the page sees it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  runQuery: vi.fn(),
  rows: [] as Record<string, unknown>[],
  provided: [] as Record<string, unknown>[],
  history: [] as Record<string, unknown>[],
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

const ROW = { videoUseId: 'use-1', publicationId: 'pub-1', name: 'Spring quiz', sponsorId: 'u-1',
  sponsorCompany: 'Bakery Co', providerId: 'p-1', providerCompany: null,
  videoTitle: JSON.stringify({ zh: '猫', ja: '猫の動画', en: 'Cats' }), status: 'CONFIRMED', periodFrom: '2026-11-01',
  periodTo: '2026-11-30', pricing: 'PER_MILLE', price: '2000', note: null, rejectReason: null,
  confirmedRevision: '5', revision: '6', editedAt: '2026-11-08T00:00:00Z', opens: null, successes: '3200', goes: '40',
  qualified: null, minGroup: '5', finalisedAt: '2026-11-09T00:00:00Z', amount: '6400' }

beforeEach(() => {
  server.permissions = new Set([PERMISSION.videoUseRead])
  server.rows = [ROW, { ...ROW, videoUseId: 'use-2', status: 'REJECTED', rejectReason: 'Wrong price',
    confirmedRevision: null, finalisedAt: null, amount: null, successes: null }]
  server.provided = []
  server.history = [
    { versionNo: '1', processName: 'QB_VIDEO_USE_SAVE', actorId: 'u-1', recordedAt: '2026-11-01T01:00:00Z',
      status: 'DRAFT', pricing: 'PER_MILLE', price: '2000', periodFrom: '2026-11-01', periodTo: '2026-11-30',
      rejectReason: null, revision: '1' },
    { versionNo: '2', processName: 'QB_VIDEO_USE_SUBMIT', actorId: 'u-1', recordedAt: '2026-11-01T02:00:00Z',
      status: 'SUBMITTED', pricing: 'PER_MILLE', price: '2000', periodFrom: '2026-11-01', periodTo: '2026-11-30',
      rejectReason: null, revision: '2' },
    { versionNo: '3', processName: 'QB_VIDEO_USE_REJECT', actorId: 'p-1', recordedAt: '2026-11-02T02:00:00Z',
      status: 'REJECTED', pricing: 'PER_MILLE', price: '2000', periodFrom: '2026-11-01', periodTo: '2026-11-30',
      rejectReason: 'Wrong price', revision: '3' },
  ]
  server.runQuery.mockReset().mockImplementation(async (id: string) => ({
    items: id === TEMPLATE.videoUses ? server.rows : id === TEMPLATE.videoUsesProvided ? server.provided
      : id === TEMPLATE.videoUseHistory || id === TEMPLATE.videoUseHistoryProvided ? server.history : [],
    offset: 0, limit: 200 }))
})

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={['/qb/video-uses']}>
          <Routes>
            <Route path="/qb/video-uses" element={<VideoUsesPage />} />
          </Routes>
        </MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('the ledger of shared videos\' uses (F-S14)', () => {
  it('has every text in every language', () => {
    const keys = (value: object, prefix = ''): string[] => Object.entries(value).flatMap(([key, child]) =>
      typeof child === 'object' && child !== null ? keys(child, `${prefix}${key}.`) : [`${prefix}${key}`])
    const english = keys(videoUseMessages.en)
    expect(keys(videoUseMessages.zh)).toEqual(english)
    expect(keys(videoUseMessages.ja)).toEqual(english)
    expect(videoUseMessages.ja.description).toContain('運営パートナー')
  })

  it('lists the uses read only, with both sides, the figures below the minimum group held back', async () => {
    const { container } = renderPage()
    const rows = await screen.findAllByTestId('video-use-row')
    expect(rows.map((r) => r.dataset.id)).toEqual(['use-1', 'use-2'])
    expect(within(rows[0]).getByTestId('video-use-user')).toHaveTextContent('Bakery Co')
    expect(within(rows[0]).getByTestId('user-name')).toHaveTextContent('name of p-1')
    expect(within(rows[0]).getByTestId('video-use-figures')).toHaveTextContent('<5 / 3,200 / 40 / <5')
    expect(within(rows[0]).getByTestId('video-use-figures')).toHaveTextContent('final')
    expect(within(rows[0]).getByTestId('video-use-amount')).toHaveTextContent('6,400 yen')
    expect(within(rows[0]).getByTestId('video-use-pricing')).toHaveTextContent('2,000 yen per 1,000 exposures')
    expect(rows[0]).toHaveTextContent('confirmed revision 5')
    expect(within(rows[1]).getByTestId('video-use-status')).toHaveAttribute('data-status', 'REJECTED')
    expect(rows[1]).toHaveTextContent('Sent back: Wrong price')
    expect(within(rows[1]).getByTestId('video-use-amount')).toHaveTextContent('—')
    expect(screen.queryByRole('button', { name: /confirm|edit|delete/i })).toBeNull()
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.videoUses, expect.objectContaining({
      sorts: [{ field: 'editedAt', asc: false }] }))
    await expectAccessible(container)
  })

  it('merges the providers\' operators\' path, each use once, and shows a use\'s history on request', async () => {
    server.rows = [ROW]
    server.provided = [{ ...ROW }, { ...ROW, videoUseId: 'use-3', name: 'Autumn quiz', editedAt: '2026-11-09T00:00:00Z' }]
    const user = userEvent.setup()
    const { container } = renderPage()
    const rows = await screen.findAllByTestId('video-use-row')
    expect(rows.map((r) => r.dataset.id)).toEqual(['use-3', 'use-1'])
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.videoUsesProvided, expect.anything())
    await user.click(within(rows[1]).getByTestId('video-use-history-toggle'))
    const entries = await screen.findAllByTestId('video-use-history-entry')
    expect(entries.map((e) => e.dataset.act)).toEqual(['CREATED', 'SUBMITTED', 'REJECTED'])
    expect(entries[2]).toHaveTextContent('name of p-1')
    expect(within(entries[2]).getByTestId('video-use-history-reason')).toHaveTextContent('Sent back: Wrong price')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.videoUseHistory, expect.objectContaining({
      params: { videoUseId: 'use-1' } }))
    await expectAccessible(container)
    // A use only the providers' path brings reads its history through that path.
    await user.click(within(rows[0]).getByTestId('video-use-history-toggle'))
    await vi.waitFor(() => expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.videoUseHistoryProvided,
      expect.objectContaining({ params: { videoUseId: 'use-3' } })))
  })

  it('says when there is none', async () => {
    server.rows = []
    const { container } = renderPage()
    expect(await screen.findByTestId('video-uses-none')).toHaveTextContent('No ledger entry yet.')
    await expectAccessible(container)
  })
})
