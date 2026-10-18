import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE, Toaster } from '@jabiz/admin'
import BroadcastsPage from './BroadcastsPage'
import { instantOf, localOf, rate, userIdsOf } from './broadcasts-api'
import { BROADCAST, TEMPLATE } from './ids'
import { messages } from './messages'

type Row = Record<string, unknown>

/** The server as the announcements' page sees it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  rows: [] as Row[],
  datasetQuery: vi.fn(),
  runQuery: vi.fn(),
  runProcess: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { POST: server.datasetQuery, GET: async () => ({ data: [] }) },
    unwrap: async (call: Promise<{ data?: unknown }>) => (await call).data,
    runQuery: server.runQuery,
    runProcess: server.runProcess,
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
  }
})

const ADMIN = '0192f0a0-0000-7000-8000-00000000ad01'
const SPONSOR = '0192f0a0-0000-7000-8000-0000000005a1'
const TAKER = '0192f0a0-0000-7000-8000-0000000000e1'

function row(id: string, change: Row = {}): Row {
  return { broadcastId: id, senderKind: 'PLATFORM', senderId: ADMIN, audience: 'APP', type: 'SYSTEM',
    status: 'SENT', title: { en: `${id} title`, ja: `${id} 件名` }, body: { en: 'Text' }, targetMode: 'ALL',
    regions: '', sentAt: '2026-10-10T00:00:00Z', audienceCount: 200, reads: 100, clicks: 20, conversions: 5,
    revision: 1, editedAt: '2026-10-10T00:00:00Z', ...change }
}

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.rows = [
    row('sent'),
    row('draft', { status: 'DRAFT', sentAt: null, audienceCount: null, reads: null, clicks: null, conversions: null }),
    row('notice', { senderKind: 'SPONSOR', senderId: SPONSOR, sponsorCompany: 'Acme', type: 'NOTICE',
      publicationName: 'Capitals' }),
    row('banned', { senderKind: 'SPONSOR', senderId: SPONSOR, sponsorCompany: 'Acme', type: 'NOTICE',
      status: 'WITHDRAWN', withdrawReason: 'QB_ACCOUNT_SUSPENDED' }),
  ]
  server.datasetQuery.mockReset().mockResolvedValue({ data: { items: [{ attributes: { userId: TAKER } }] } })
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { params: Row }) => ({
    items: id === TEMPLATE.broadcasts
      ? server.rows.filter((r) => !options.params.senderKind || r.senderKind === options.params.senderKind)
      : [],
    offset: 0, limit: 20, total: id === TEMPLATE.broadcasts ? server.rows.length : 0 }))
  server.runProcess.mockReset().mockImplementation(async (name: string) => {
    if (name === BROADCAST.save) return { broadcastId: 'new', status: 'DRAFT', revision: 1, sendsAt: '', targetCount: 0,
      problems: [] }
    if (name === BROADCAST.send) return { broadcastId: 'new', status: 'SENT' }
    return { broadcastId: 'sent', status: 'WITHDRAWN' }
  })
})

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={['/qb/broadcasts']}>
          <BroadcastsPage />
        </MemoryRouter>
        <Toaster />
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

const rowOf = async (id: string) => {
  const rows = await screen.findAllByTestId('broadcast-row')
  const found = rows.find((r) => r.dataset.id === id)
  if (!found) throw new Error(`no row ${id}`)
  return found
}

describe('broadcasts-api', () => {
  it('reads pasted ids once each, rates in whole per cent and times both ways', () => {
    expect(userIdsOf(`${TAKER}\n${SPONSOR}, ${TAKER} ;`)).toEqual([TAKER, SPONSOR])
    expect(rate(1, 3)).toBe(33)
    expect(rate(1, 0)).toBeNull()
    expect(rate(null, 3)).toBeNull()
    expect(instantOf('')).toBeNull()
    expect(localOf(instantOf('2026-10-18T09:30'))).toBe('2026-10-18T09:30')
  })
})

describe('BroadcastsPage', () => {
  it('lists announcements and notices with their statistics and the offered actions', async () => {
    renderPage()
    const sent = await rowOf('sent')
    expect(sent).toHaveTextContent('100 (50 %)')
    expect(sent).toHaveTextContent('20 (20 %)')
    expect(within(sent).getByTestId('broadcast-withdraw-form')).toBeInTheDocument()
    const draft = await rowOf('draft')
    expect(within(draft).getByTestId('broadcast-edit')).toBeInTheDocument()
    expect(within(draft).queryByTestId('broadcast-withdraw-form')).not.toBeInTheDocument()
    const notice = await rowOf('notice')
    expect(notice).toHaveTextContent('Acme')
    expect(notice).toHaveTextContent('Publication: Capitals')
    // A sponsor's notice is never edited here, only withdrawn.
    expect(within(notice).queryByTestId('broadcast-edit')).not.toBeInTheDocument()
    expect(within(notice).getByTestId('broadcast-withdraw-form')).toBeInTheDocument()
    expect(within(await rowOf('banned')).getByTestId('broadcast-withdrawn'))
      .toHaveTextContent('the sponsor was banned')
  })

  it('filters by sender through the template', async () => {
    renderPage()
    await rowOf('sent')
    await userEvent.click(screen.getByTestId('broadcasts-sender'))
    await userEvent.click(await screen.findByRole('option', { name: 'Sender' }))
    await waitFor(() => expect(server.runQuery).toHaveBeenLastCalledWith(TEMPLATE.broadcasts, expect.objectContaining({
      params: expect.objectContaining({ senderKind: 'SPONSOR' }) })))
    await waitFor(() => expect(screen.getAllByTestId('broadcast-row')).toHaveLength(2))
  })

  it('saves a draft, shows the server\'s problems and sends only a clean saved draft', async () => {
    server.runProcess.mockImplementationOnce(async () => ({ broadcastId: 'new', status: 'DRAFT', revision: 1,
      sendsAt: '', targetCount: 0, problems: [{ field: 'title', code: 'QB_BROADCAST_TEXT_REQUIRED',
        message: 'An English title and text are required.' }] }))
    renderPage()
    await userEvent.click(await screen.findByTestId('broadcast-new'))
    expect(screen.getByTestId('broadcast-send')).toBeDisabled()
    await userEvent.click(screen.getByTestId('broadcast-save'))
    expect(await screen.findByText('An English title and text are required.')).toBeInTheDocument()
    expect(screen.getByTestId('broadcast-send')).toBeDisabled()
    await userEvent.type(screen.getByTestId('broadcast-title-en'), 'Maintenance')
    await userEvent.type(screen.getByTestId('broadcast-body-en'), 'On **Sunday**.')
    // The preview renders the Markdown.
    expect(within(screen.getByTestId('broadcast-preview-en')).getByText('Sunday').tagName).toBe('STRONG')
    // Changed since saving: not to be sent before saving again.
    expect(screen.getByTestId('broadcast-send')).toBeDisabled()
    await userEvent.click(screen.getByTestId('broadcast-save'))
    expect(await screen.findByTestId('broadcast-ready')).toBeInTheDocument()
    expect(server.runProcess).toHaveBeenLastCalledWith(BROADCAST.save, expect.objectContaining({
      broadcastId: 'new', baseRevision: 1, audience: 'APP', type: 'SYSTEM', targetMode: 'ALL',
      title: { en: 'Maintenance' }, body: { en: 'On **Sunday**.' }, scheduledAt: null, regions: [] }))
    await userEvent.click(screen.getByTestId('broadcast-send'))
    await userEvent.click(await screen.findByRole('button', { name: 'Send' }))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(BROADCAST.send,
      { broadcastId: 'new', baseRevision: 1 }))
    expect(await screen.findByText('Sent.')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByTestId('broadcast-editor')).not.toBeInTheDocument())
  })

  it('names users by pasting their ids and sends the complete list', async () => {
    renderPage()
    await userEvent.click(await screen.findByTestId('broadcast-new'))
    await userEvent.click(screen.getByTestId('broadcast-target'))
    await userEvent.click(await screen.findByRole('option', { name: 'USERS' }))
    await userEvent.type(screen.getByTestId('broadcast-paste'), `${TAKER} ${SPONSOR} ${TAKER}`)
    await userEvent.tab()
    expect(screen.getByTestId('broadcast-user-count')).toHaveTextContent('Named: 2')
    await userEvent.click(screen.getByTestId('broadcast-save'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(BROADCAST.save, expect.objectContaining({
      targetMode: 'USERS', userIds: [TAKER, SPONSOR] })))
  })

  it('opens a draft with its named users', async () => {
    server.rows = [row('draft', { status: 'DRAFT', targetMode: 'USERS', targetCount: 1 })]
    renderPage()
    await userEvent.click(within(await rowOf('draft')).getByTestId('broadcast-edit'))
    await waitFor(() => expect(screen.getByTestId('broadcast-user-count')).toHaveTextContent('Named: 1'))
    expect(within(screen.getByTestId('broadcast-users')).getByText(TAKER)).toBeInTheDocument()
  })

  it('withdraws with the reason, after asking, and says why the server refused', async () => {
    renderPage()
    const form = within(await rowOf('notice')).getByTestId('broadcast-withdraw-form')
    expect(within(form).getByTestId('broadcast-withdraw')).toBeDisabled()
    await userEvent.type(within(form).getByTestId('broadcast-withdraw-reason'), 'Misleading')
    server.runProcess.mockRejectedValueOnce(new ApiError(422, { detail: 'Refused', violations: [{ field: 'broadcastId',
      ruleCode: 'QB_BROADCAST_NOT_SENT', message: 'It is no longer in the inboxes.' }] }))
    await userEvent.click(within(form).getByTestId('broadcast-withdraw'))
    await userEvent.click(await screen.findByRole('button', { name: 'Withdraw' }))
    expect(await screen.findByText('It is no longer in the inboxes.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Withdraw' }))
    await waitFor(() => expect(server.runProcess).toHaveBeenLastCalledWith(BROADCAST.withdraw,
      { broadcastId: 'notice', reason: 'Misleading' }))
    expect(await screen.findByText('Done.')).toBeInTheDocument()
  })
})
