import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import AbuseReportsPage from './AbuseReportsPage'
import { DATASET, PERMISSION, REPORT_PROCESS, TEMPLATE } from './ids'
import { messages } from './messages'
import { abuseReportMessages } from './report-messages'

type Row = Record<string, unknown>

/** The server as the reports page sees it through `@jabiz/admin`. */
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

const PUB = '0192f0a0-0000-7000-8000-00000000d001'

const reported = (change: Row = {}): Row => ({ publicationId: PUB, name: 'Capitals week', status: 'LIVE',
  publisherKind: 'SPONSOR', publisherId: 'sponsor-1', company: 'Acme', openCount: '2', totalCount: '3',
  misleading: '2', inappropriate: '0', rewardNotGiven: '1', spam: '0', other: '0',
  lastReportedAt: '2026-10-30T01:00:00Z', ...change })

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set([PERMISSION.publicationRead, PERMISSION.contentWrite])
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { params?: Row; count?: boolean } = {}) => {
    const items = id === TEMPLATE.abuseReports
      ? [reported(), reported({ publicationId: 'p2', name: 'Done quiz', openCount: '0', totalCount: '1',
        misleading: '0', rewardNotGiven: '0', spam: '1' })].filter((row) => options.params?.open == null
        || (Number(row.openCount) > 0) === options.params.open)
      : []
    return { items, offset: 0, limit: 20, ...(options.count ? { total: items.length } : {}) }
  })
  server.post.mockReset().mockImplementation(async (path: string, request: { params?: { path?: Row } }) => {
    if (path === '/api/datasets/{resourceId}/query' && request.params?.path?.resourceId === DATASET.report) {
      return { items: [
        { id: 'r2', attributes: { reason: 'MISLEADING', note: '<b>No prize</b>', status: 'OPEN',
          createdAt: '2026-10-30T01:00:00Z', resolution: null } },
        { id: 'r1', attributes: { reason: 'REWARD_NOT_GIVEN', note: null, status: 'DISMISSED',
          createdAt: '2026-10-29T01:00:00Z', resolution: 'Paid since' } },
      ] }
    }
    throw new ApiError(404, { detail: 'Not found' })
  })
  server.get.mockReset().mockImplementation(async (path: string, request: { params?: { path?: Row } }) => {
    if (path === '/api/dictionaries/{urn}') {
      return String(request.params?.path?.urn).endsWith('report-status')
        ? [{ code: 'OPEN', label: 'Open' }, { code: 'DISMISSED', label: 'Dismissed' }]
        : [{ code: 'MISLEADING', label: 'False or misleading' }, { code: 'REWARD_NOT_GIVEN', label: 'Reward not given' },
          { code: 'SPAM', label: 'Spam or advertising' }]
    }
    throw new ApiError(404, { detail: 'Not found' })
  })
})

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={['/qb/abuse-reports']}>
          <Routes>
            <Route path="/qb/abuse-reports" element={<AbuseReportsPage />} />
            <Route path="*" element={<p data-testid="elsewhere">elsewhere</p>} />
          </Routes>
        </MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe("takers' reports (Q49a)", () => {
  it('have every text in every language', () => {
    const keys = (value: object, prefix = ''): string[] => Object.entries(value).flatMap(([key, child]) =>
      typeof child === 'object' && child !== null ? keys(child, `${prefix}${key}.`) : [`${prefix}${key}`])
    const english = keys(abuseReportMessages.en)
    expect(keys(abuseReportMessages.zh)).toEqual(english)
    expect(keys(abuseReportMessages.ja)).toEqual(english)
  })

  it('list the quizzes with open reports, counted by reason, and close them through the process page', async () => {
    const { container } = renderPage()
    const rows = await screen.findAllByTestId('report-row')
    expect(rows).toHaveLength(1)
    expect(rows[0]).toHaveTextContent('2 open of 3')
    expect(rows[0]).toHaveTextContent('False or misleading: 2')
    expect(rows[0]).toHaveTextContent('Reward not given: 1')
    expect(within(rows[0]).getByTestId('report-dismiss')).toHaveAttribute('href',
      `/processes/${REPORT_PROCESS.resolve}/1?publicationId=${PUB}&outcome=DISMISSED`)
    expect(within(rows[0]).getByTestId('report-actioned')).toHaveAttribute('href',
      `/processes/${REPORT_PROCESS.resolve}/1?publicationId=${PUB}&outcome=ACTIONED`)
    await expectAccessible(container)

    // Its reports, the note as plain text.
    await userEvent.click(within(rows[0]).getByTestId('report-open'))
    const detail = await screen.findByTestId('report-detail')
    const items = await within(detail).findAllByTestId('report-item')
    expect(items).toHaveLength(2)
    expect(items[0]).toHaveTextContent('<b>No prize</b>')
    expect(items[1]).toHaveTextContent('No note')
    expect(items[1]).toHaveTextContent('Paid since')

    // All handled: no actions on a quiz with nothing open.
    await userEvent.click(screen.getByTestId('reports-show'))
    await userEvent.click(await screen.findByRole('option', { name: 'All handled' }))
    const handled = await screen.findAllByTestId('report-row')
    expect(handled.map((row) => row.dataset.id)).toEqual(['p2'])
    expect(within(handled[0]).queryByTestId('report-dismiss')).toBeNull()
  })

  it("use the operator's process in an operator's session, and none for a reader", async () => {
    server.permissions = new Set([PERMISSION.publicationRead, PERMISSION.operatorUse])
    const { unmount } = renderPage()
    const rows = await screen.findAllByTestId('report-row')
    expect(within(rows[0]).getByTestId('report-dismiss')).toHaveAttribute('href',
      `/processes/${REPORT_PROCESS.operatorResolve}/1?publicationId=${PUB}&outcome=DISMISSED`)
    unmount()
    server.permissions = new Set([PERMISSION.publicationRead])
    renderPage()
    const readOnly = await screen.findAllByTestId('report-row')
    expect(within(readOnly[0]).queryByTestId('report-dismiss')).toBeNull()
  })
})
