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
import AiPage from './AiPage'
import { HelpGapsPage, HelpManualsPage } from './HelpContentPages'
import { HelpThreadPage, HelpThreadsPage } from './HelpDeskPages'
import { helpMessages } from './help-messages'
import { HELP } from './ids'
import { messages } from './messages'

type Row = Record<string, unknown>

/** The server as the help's pages see it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  threads: [] as Row[],
  messages: [] as Row[],
  gaps: [] as Row[],
  usage: [] as Row[],
  manuals: [] as Row[],
  materials: [] as Row[],
  runQuery: vi.fn(),
  runProcess: vi.fn(),
  post: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    runQuery: server.runQuery,
    runProcess: server.runProcess,
    api: { POST: server.post },
    unwrap: async (value: unknown) => value,
    useAuth: () => ({ can: (p: string) => server.permissions.has(p) }),
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
  }
})

const T1 = '0192f0a0-0000-7000-8000-0000000000t1'
const USER = '0192f0a0-0000-7000-8000-0000000000u1'

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

function thread(over: Row = {}): Row {
  return { threadId: T1, userId: USER, entry: 'sponsor', name: 'Acme', language: 'en', status: 'ESCALATED',
    operatorId: 'o1', operatorName: 'Kanto partners', waitingFor: 'STAFF', waitingSince: '2026-11-09T01:00:00Z',
    escalatedAt: '2026-11-09T01:00:00Z', closeReason: null, lastActivityAt: '2026-11-09T01:00:00Z', ...over }
}

beforeEach(() => {
  server.permissions = new Set(['qb.admin.help', 'qb.admin.help.gaps', 'qb.admin.ai.read', 'qb.admin.content.write'])
  server.threads = [thread(), thread({ threadId: 't2', entry: 'app', name: 'Mei', operatorId: null,
    operatorName: null, waitingFor: 'USER' })]
  server.messages = [
    { messageId: 'm1', kind: 'QUESTION', body: 'How do I **publish**?', links: null, page: 'publications',
      jobStatus: 'ANSWERED', rating: null, createdAt: '2026-11-09T00:50:00Z' },
    { messageId: 'm2', kind: 'ANSWER', body: 'Open Publications.', links: '/sponsor/publications', page: null,
      jobStatus: null, rating: 'UNSOLVED', createdAt: '2026-11-09T00:51:00Z' },
    { messageId: 'm3', kind: 'USER', body: '<b>Still</b> stuck', links: null, page: null, jobStatus: null,
      rating: null, createdAt: '2026-11-09T01:00:00Z' },
  ]
  server.gaps = [
    { rowKey: 'U:m1', reason: 'UNSOLVED', threadId: T1, entry: 'sponsor', language: 'en', page: 'publications',
      question: 'How do I publish?', at: '2026-11-03T00:00:00Z' },
    { rowKey: 'E:t2', reason: 'ESCALATED', threadId: 't2', entry: 'app', language: 'ja', page: null,
      question: '出金は？', at: '2026-11-04T00:00:00Z' },
  ]
  server.usage = [{ month: '2026-11', questions: 120, failedJobs: 3, cancelledJobs: 1, inputTokens: 900000,
    outputTokens: 50000, cacheReadTokens: 700000, cacheWriteTokens: 40000, estimatedUsd: '1.23', people: 40,
    monthlyTokens: 20000000, percentUsed: 1.4, checkJobs: 2, checksUsd: '6.00', totalUsd: '7.23', alertUsd: '8.00',
    percentOfAlert: 90.4, reachedAlert: false }]
  server.manuals = [
    { id: 'man-app', version: 3, attributes: { entry: 'app', body: { zh: '学者', ja: 'テイカー', en: 'Takers' },
      publishedBody: { zh: '学者', ja: 'テイカー', en: 'Takers' }, publishedAt: '2026-11-08T00:00:00Z',
      sourceHash: 'h' } },
    { id: 'man-sp', version: 5, attributes: { entry: 'sponsor', body: { zh: '发起方', ja: '主催者', en: 'Sponsors 2' },
      publishedBody: { zh: '发起方', ja: '主催者', en: 'Sponsors' }, publishedAt: null, sourceHash: 'h' } },
  ]
  server.materials = [{ attributes: { entry: 'app', language: 'en', bytes: 20480, builtAt: '2026-11-08T00:00:00Z',
    latestBytes: 20480, latestAt: '2026-11-08T00:00:00Z', pendingHash: null } }]
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { params?: Row; filters?: Row[] }) => {
    const filter = options.filters?.[0]
    // The waiting filter is the server's: the rows given are what it answers; one conversation is found by its key.
    const items = id === HELP.threads ? server.threads.filter((t) => filter?.field !== 'threadId'
      || t.threadId === filter.value)
      : id === HELP.messages ? server.messages
        : id === HELP.gaps ? server.gaps
          : id === HELP.usage ? server.usage
            : []
    return { items, offset: 0, limit: 100 }
  })
  server.runProcess.mockReset().mockResolvedValue({ threadId: T1, messageId: 'm4', status: 'ESCALATED' })
  server.post.mockReset().mockImplementation(async (_path: string, init: { params: { path: { resourceId: string } } }) =>
    ({ items: init.params.path.resourceId === HELP.manual ? server.manuals : server.materials }))
})

function renderAt(path: string, routes: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>{routes}</Routes>
        </MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

const threadPage = () => renderAt(`/qb/help/${T1}`, <>
  <Route path="/qb/help/:threadId" element={<HelpThreadPage />} />
  <Route path="/qb/help" element={<HelpThreadsPage />} />
</>)

const conflict = () => new ApiError(409, { title: 'Conflict', detail: 'Changed meanwhile' })

describe('the help desk', () => {
  it('lists the conversations handed to a person, with who asked by name, and passes axe', async () => {
    renderAt('/qb/help', <Route path="/qb/help" element={<HelpThreadsPage />} />)
    const rows = await screen.findAllByTestId('help-thread-row')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('Acme')
    expect(within(rows[0]).getByTestId('user-name')).toHaveTextContent(USER)
    expect(rows[0]).toHaveTextContent('Kanto partners')
    expect(rows[1]).toHaveTextContent('Headquarters')
    expect(rows[1]).toHaveTextContent('Taker')
    // Those waiting for a reply first: the filter starts there.
    expect(server.runQuery).toHaveBeenCalledWith(HELP.threads, expect.objectContaining({ params: { open: true },
      filters: [{ field: 'waitingFor', op: 'eq', value: 'STAFF' }] }))
    await userEvent.click(screen.getByTestId('help-filter-all'))
    await waitFor(() => expect(server.runQuery).toHaveBeenLastCalledWith(HELP.threads,
      expect.objectContaining({ params: {}, filters: undefined })))
    await expectAccessible()
  })

  it('shows a conversation as plain text and replies', async () => {
    threadPage()
    const page = await screen.findByTestId('help-thread')
    expect(within(page).getByTestId('help-thread-user')).toHaveTextContent('Acme')
    const bodies = within(page).getAllByTestId('help-message-body')
    expect(bodies[0]).toHaveTextContent('How do I **publish**?')
    expect(bodies[2]).toHaveTextContent('<b>Still</b> stuck')
    expect(page.querySelector('b, strong')).toBeNull()
    expect(page).toHaveTextContent('Rated not solved')
    await expectAccessible()
    await userEvent.type(screen.getByTestId('help-reply-text'), 'Open Publications, then New.')
    await userEvent.click(screen.getByTestId('help-reply-send'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(HELP.reply, { threadId: T1,
      text: 'Open Publications, then New.', close: false }))
    expect(await screen.findByTestId('help-reply-done')).toHaveTextContent('Reply sent.')
    expect(screen.getByTestId('help-reply-text')).toHaveValue('')
  })

  it('replies and closes; or closes without a reply after asking', async () => {
    threadPage()
    await userEvent.type(await screen.findByTestId('help-reply-text'), 'Done.')
    await userEvent.click(screen.getByTestId('help-reply-close'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(HELP.reply, { threadId: T1, text: 'Done.',
      close: true }))
    await userEvent.click(screen.getByTestId('help-close-only'))
    await userEvent.click(await screen.findByRole('button', { name: 'Close' }))
    await waitFor(() => expect(server.runProcess).toHaveBeenLastCalledWith(HELP.reply, { threadId: T1, text: null,
      close: true }))
  })

  it('asks for the reply\'s text before sending', async () => {
    threadPage()
    await userEvent.click(await screen.findByTestId('help-reply-send'))
    expect(await screen.findByTestId('help-reply-problem')).toHaveTextContent('Write the reply first.')
    expect(server.runProcess).not.toHaveBeenCalled()
  })

  it('on 409, says the content was updated, reads the conversation again and keeps the reply', async () => {
    server.runProcess.mockRejectedValueOnce(conflict())
    threadPage()
    await userEvent.type(await screen.findByTestId('help-reply-text'), 'My reply')
    const reads = server.runQuery.mock.calls.filter(([id]) => id === HELP.messages).length
    server.messages.push({ messageId: 'm9', kind: 'STAFF', body: 'A colleague answered', links: null, page: null,
      jobStatus: null, rating: null, createdAt: '2026-11-09T02:00:00Z' })
    await userEvent.click(screen.getByTestId('help-reply-send'))
    expect(await screen.findByTestId('help-reply-problem')).toHaveTextContent(
      'The content was updated. Please try again.')
    await waitFor(() => expect(server.runQuery.mock.calls.filter(([id]) => id === HELP.messages).length)
      .toBeGreaterThan(reads))
    expect(await screen.findByText('A colleague answered')).toBeVisible()
    expect(screen.getByTestId('help-reply-text')).toHaveValue('My reply')
  })

  it('says when the conversation is not the caller\'s, and offers no reply to a closed one', async () => {
    server.threads = [thread({ status: 'CLOSED', closeReason: 'RESOLVED', waitingFor: null })]
    const { unmount } = threadPage()
    expect(await screen.findByTestId('help-thread-closed')).toBeVisible()
    expect(screen.getByTestId('help-thread-status')).toHaveTextContent('Closed (solved)')
    expect(screen.queryByTestId('help-reply-form')).toBeNull()
    unmount()
    server.threads = []
    threadPage()
    expect(await screen.findByTestId('help-thread-none')).toBeVisible()
  })
})

describe('the help gaps', () => {
  it('lists the month\'s unsolved and handed-over questions, never who asked', async () => {
    renderAt('/qb/help-gaps?month=2026-11', <Route path="/qb/help-gaps" element={<HelpGapsPage />} />)
    const rows = await screen.findAllByTestId('help-gap')
    expect(rows.map((r) => r.dataset.reason)).toEqual(['UNSOLVED', 'ESCALATED'])
    expect(rows[0]).toHaveTextContent('How do I publish?')
    expect(rows[1]).toHaveTextContent('Handed to a person')
    expect(screen.queryByTestId('user-name')).toBeNull()
    expect(server.runQuery).toHaveBeenCalledWith(HELP.gaps, expect.objectContaining({
      params: { month: '2026-11' } }))
    await expectAccessible()
  })

  it('asks for the month itself: the server splits months by the deployment\'s accounting time zone', async () => {
    renderAt('/qb/help-gaps?month=2026-12', <Route path="/qb/help-gaps" element={<HelpGapsPage />} />)
    await screen.findAllByTestId('help-gap')
    expect(server.runQuery).toHaveBeenLastCalledWith(HELP.gaps, expect.objectContaining({
      params: { month: '2026-12' } }))
  })
})

describe('the AI page\'s help usage', () => {
  it('shows the help\'s usage apart from the Sponsors\' AI usage, with the cache and the share of the cap', async () => {
    renderAt('/qb/ai', <Route path="/qb/ai" element={<AiPage />} />)
    const section = await screen.findByTestId('help-usage-section')
    const row = await within(section).findByTestId('help-usage-row')
    expect(within(row).getByTestId('help-usage-cache-read')).toHaveTextContent('700,000')
    expect(within(row).getByTestId('help-usage-cache-write')).toHaveTextContent('40,000')
    expect(within(row).getByTestId('help-usage-percent')).toHaveTextContent('1.4% of 20,000,000')
    expect(row).toHaveTextContent('1.23')
    // F-M10: the reviewers' translation checks, the dollars together, the alert and the share of it.
    expect(within(row).getByTestId('help-usage-checks')).toHaveTextContent('2 · 6.00 USD')
    expect(within(row).getByTestId('help-usage-total')).toHaveTextContent('7.23')
    expect(within(row).getByTestId('help-usage-alert')).toHaveTextContent('8.00')
    expect(within(row).getByTestId('help-usage-of-alert')).toHaveTextContent('90.4%')
    expect(screen.queryByTestId('help-usage-reached')).not.toBeInTheDocument()
    expect(screen.getByTestId('ai-usage-section')).not.toContainElement(section)
    expect(screen.getByTestId('help-gaps-link')).toHaveAttribute('href', expect.stringMatching(/\/qb\/help-gaps\?month=/))
    await expectAccessible()
  })
})

describe('the AI costs against the dollar alert (F-M10)', () => {
  it('says when a month reached the alert, and shows no share (empty, not 0) when there is no alert', async () => {
    server.usage = [
      { ...server.usage[0], month: '2026-11', totalUsd: '9.10', percentOfAlert: 113.8, reachedAlert: true },
      { ...server.usage[0], month: '2026-10', checkJobs: 0, checksUsd: '0.00', totalUsd: '1.23', alertUsd: '0.00',
        percentOfAlert: null, reachedAlert: false },
    ]
    renderAt('/qb/ai', <Route path="/qb/ai" element={<AiPage />} />)
    const section = await screen.findByTestId('help-usage-section')
    expect(await within(section).findByTestId('help-usage-reached')).toHaveTextContent('2026-11')
    expect(within(section).getByTestId('help-usage-reached')).not.toHaveTextContent('2026-10')
    const rows = await within(section).findAllByTestId('help-usage-row')
    expect(within(rows[0]).getByTestId('help-usage-of-alert')).toHaveTextContent('113.8%')
    expect(within(rows[1]).getByTestId('help-usage-alert')).toHaveTextContent('No alert')
    expect(within(rows[1]).getByTestId('help-usage-of-alert')).toBeEmptyDOMElement()
    expect(within(rows[1]).getByTestId('help-usage-checks')).toBeEmptyDOMElement()
    await expectAccessible()
  })
})

describe('the AI help manuals', () => {
  const manualsPage = () => renderAt('/qb/help-manuals',
    <Route path="/qb/help-manuals" element={<HelpManualsPage />} />)

  it('publishes a draft with the version read, and rebuilds the materials', async () => {
    server.runProcess.mockResolvedValue({ limit: 204800, sizes: [] })
    manualsPage()
    const cards = await screen.findAllByTestId('help-manual')
    expect(cards[1]).toHaveTextContent('The draft differs from the published manual.')
    expect(cards[0]).toHaveTextContent('The draft is the published manual.')
    expect(await screen.findByTestId('help-materials')).toHaveTextContent('20.0 KB')
    await expectAccessible()
    await userEvent.click(within(cards[1]).getByTestId('help-manual-publish'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(HELP.publish, { manualId: 'man-sp',
      baseRevision: 5 }))
    await userEvent.click(screen.getByTestId('help-rebuild'))
    await waitFor(() => expect(server.runProcess).toHaveBeenLastCalledWith(HELP.publish, {}))
  })

  it('loads the repository\'s text, and asks to keep or replace a draft edited here', async () => {
    server.runProcess.mockRejectedValueOnce(new ApiError(422, { violations: [{ field: null,
      ruleCode: 'QB_HELP_MANUAL_EDITED', message: 'Edited' }] }))
      .mockResolvedValue({ manualId: 'man-app', entry: 'app', replaced: false })
    manualsPage()
    const card = (await screen.findAllByTestId('help-manual'))[0]
    await userEvent.click(within(card).getByTestId('help-manual-load'))
    expect(server.runProcess).toHaveBeenCalledWith(HELP.load, { manualId: 'man-app', baseRevision: 3 })
    const edited = await within(card).findByTestId('help-manual-edited')
    await expectAccessible()
    await userEvent.click(within(edited).getByTestId('help-manual-keep'))
    await waitFor(() => expect(server.runProcess).toHaveBeenLastCalledWith(HELP.load, { manualId: 'man-app',
      baseRevision: 3, keepDraft: true }))
    expect(await screen.findByTestId('help-manual-done')).toHaveTextContent('recorded')
  })

  it('replaces the draft when chosen', async () => {
    server.runProcess.mockRejectedValueOnce(new ApiError(422, { violations: [{ field: null,
      ruleCode: 'QB_HELP_MANUAL_EDITED', message: 'Edited' }] }))
      .mockResolvedValue({ manualId: 'man-app', entry: 'app', replaced: true })
    manualsPage()
    const card = (await screen.findAllByTestId('help-manual'))[0]
    await userEvent.click(within(card).getByTestId('help-manual-load'))
    await userEvent.click(await within(card).findByTestId('help-manual-replace'))
    await waitFor(() => expect(server.runProcess).toHaveBeenLastCalledWith(HELP.load, { manualId: 'man-app',
      baseRevision: 3, replace: true }))
  })

  it('on a draft changed meanwhile, says the content was updated and reads it again', async () => {
    server.runProcess.mockRejectedValueOnce(new ApiError(422, { violations: [{ field: null,
      ruleCode: 'QB_HELP_MANUAL_CHANGED', message: 'Changed' }] }))
    manualsPage()
    const card = (await screen.findAllByTestId('help-manual'))[1]
    const reads = server.post.mock.calls.length
    await userEvent.click(within(card).getByTestId('help-manual-publish'))
    await waitFor(() => expect(server.post.mock.calls.length).toBeGreaterThan(reads))
    expect(await screen.findByTestId('help-manual-problem')).toHaveTextContent('The content was updated.')
  })
})

describe('the help\'s texts', () => {
  it('have the same keys in every language and the product\'s words', () => {
    const keys = (value: unknown, prefix = ''): string[] => typeof value !== 'object' || value === null ? [prefix]
      : Object.entries(value).flatMap(([k, v]) => keys(v, prefix ? `${prefix}.${k}` : k)).sort()
    expect(keys(helpMessages.zh)).toEqual(keys(helpMessages.en))
    expect(keys(helpMessages.ja)).toEqual(keys(helpMessages.en))
    expect(helpMessages.zh.thread.conflict).toBe('内容已更新，请重试')
    expect(JSON.stringify(helpMessages)).not.toMatch(/后台|管理画面|console|Administration/)
  })
})
