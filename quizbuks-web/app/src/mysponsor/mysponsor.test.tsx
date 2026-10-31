import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device' }
const USER = '01a0f4fe-4660-7000-8000-0000000000bb'

const ACCOUNT = { company: 'Capitals Inc', contactName: 'Kai', avatar: null, reviewStatus: 'APPROVED',
  approvedAt: '2026-10-01T00:00:00Z' }
const FIGURES = { live: 1, paused: 1, fundsShort: 0, answering: 3, spentToday: 400, available: 12000,
  pendingCount: 0, pendingAmount: 0, budgetRemaining: 5000, zone: 'Asia/Tokyo', refreshedAt: '2026-10-31T01:00:00Z' }
const pub = (over: Record<string, unknown>) => ({ publicationId: 'pub-1', name: 'Capitals week', cover: null,
  status: 'LIVE', pausedBy: null, rewardMethod: 'PLATFORM', starts: 12, completions: 9, passes: 6, answering: 3,
  spent: 600, budgetCap: 1000, startAt: '2026-10-20T00:00:00Z', endAt: '2026-11-20T00:00:00Z', ...over })

interface Server {
  account?: Record<string, unknown> | null
  figures?: Record<string, unknown>
  publications?: Record<string, unknown>[]
  inbox?: Record<string, unknown>[]
}

/** The server as the app sees it, for a verified taker who may also be a sponsor. Records what was posted. */
function server(options: Server = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  let publications = options.publications ?? [pub({})]
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    const process = /^\/api\/processes\/([A-Z_]+)\/latest$/.exec(path)
    if (process) {
      const id = (body as { publicationId: string }).publicationId
      const status = process[1] === 'QB_PUBLICATION_PAUSE' ? 'PAUSED' : 'LIVE'
      publications = publications.map((p) => (p.publicationId === id
        ? { ...p, status, pausedBy: status === 'PAUSED' ? 'SPONSOR' : null } : p))
      return json(200, { output: { publicationId: id, status } })
    }
    switch (path) {
      case '/api/auth/entries/app':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: USER, displayName: 'Kai', email: 'kai@example.com', roles: ['QB_TAKER'],
          permissions: ['qb.me', 'qb.sponsor.app'], emailVerified: true, idleTimeoutSeconds: 0 })
      case '/api/datasets/urn:jabiz:dataset:me:QbProfile/query':
        return json(200, { items: [{ id: 'p1', version: 1, attributes: { displayName: 'Kai', countryCode: 'JP',
          city: null, preferredCurrency: 'JPY', revision: 1 } }], offset: 0, limit: 1 })
      case '/api/queries/qb.app.inbox-unread':
        return json(200, { items: [{ messages: 0, broadcasts: 0 }], offset: 0, limit: 1 })
      case '/api/queries/qb.app.inbox':
        return json(200, { items: options.inbox ?? [], offset: 0, limit: 20 })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/queries/qb.app.my-sponsor': {
        const account = options.account === undefined ? ACCOUNT : options.account
        return json(200, { items: account ? [account] : [], offset: 0, limit: 1 })
      }
      case '/api/queries/qb.sponsor.dashboard':
        return json(200, { items: [options.figures ?? FIGURES], offset: 0, limit: 1 })
      case '/api/queries/qb.app.sponsor-publications':
        return json(200, { items: publications, offset: 0, limit: 100 })
      case '/api/queries/qb.sponsor.analytics.trend':
        return json(200, { items: [{ day: '2026-10-30', completions: 4, passes: 3, spent: 300 },
          { day: '2026-10-31', completions: 5, passes: 3, spent: 300 }], offset: 0, limit: 10 })
      default:
        return json(404, {})
    }
  })
  const processCalls = (name: string) =>
    posted.filter((p) => p.path === `/api/processes/${name}/latest`).map((p) => p.body)
  return { processCalls }
}

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => {
  vi.restoreAllMocks()
  window.history.replaceState(null, '', '/')
})

describe('a sponsor in the app (Q68)', () => {
  it('offers the sponsor page in the header to a sponsor only, with a dot when funds are short', async () => {
    server({ figures: { ...FIGURES, fundsShort: 1 } })
    window.history.replaceState(null, '', '/inbox')
    render(<App />)
    const entry = await screen.findByTestId('header-my-sponsor')
    expect(entry).toHaveAttribute('href', '/my-sponsor')
    expect(await screen.findByTestId('header-my-sponsor-dot')).toBeInTheDocument()
    expect(entry).toHaveAccessibleName('My sponsor account: Needs your attention')
  })

  it('shows nothing of the kind to a taker who is no sponsor', async () => {
    server({ account: null })
    window.history.replaceState(null, '', '/inbox')
    render(<App />)
    expect(await screen.findByTestId('inbox')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByTestId('header-my-sponsor')).toBeNull())
  })

  it('shows today\'s figures and the publications on, with their last seven days', async () => {
    server()
    window.history.replaceState(null, '', '/my-sponsor')
    render(<App />)
    expect(await screen.findByRole('heading', { level: 1, name: 'My sponsor account' })).toBeInTheDocument()
    expect(screen.getByTestId('my-sponsor-company')).toHaveTextContent('Capitals Inc')
    expect(screen.getByTestId('my-sponsor-status')).toHaveTextContent('Approved')
    expect(await screen.findByTestId('my-sponsor-answering')).toHaveTextContent('3')
    expect(screen.getByTestId('my-sponsor-available')).toHaveTextContent('12,000 Kudos')
    const row = await screen.findByTestId('my-sponsor-publication')
    expect(within(row).getByTestId('my-sponsor-counts')).toHaveTextContent('Answered: 9')
    expect(within(row).getByTestId('my-sponsor-counts')).toHaveTextContent('Spent: 600 Kudos of 1,000 Kudos')
    await userEvent.click(within(row).getByText('Last 7 days'))
    const table = await within(row).findByTestId('my-sponsor-week-table')
    expect(within(table).getAllByRole('row')).toHaveLength(3)
    // Nothing that moves Kudos here: the console is linked for that.
    expect(screen.getByTestId('my-sponsor-console')).toHaveAttribute('href', '/sponsor/')
    await expectAccessible()
  })

  it('pauses and resumes a publication after asking, and not one an administrator paused', async () => {
    const api = server({ publications: [pub({}), pub({ publicationId: 'pub-2', name: 'Rivers', status: 'PAUSED',
      pausedBy: 'ADMIN' })] })
    window.history.replaceState(null, '', '/my-sponsor')
    render(<App />)
    const rows = await screen.findAllByTestId('my-sponsor-publication')
    expect(within(rows[1]).queryByTestId('my-sponsor-resume')).toBeNull()
    expect(within(rows[1]).getByTestId('my-sponsor-admin-paused')).toBeInTheDocument()
    await userEvent.click(within(rows[0]).getByTestId('my-sponsor-pause'))
    await userEvent.click(await screen.findByRole('button', { name: 'Pause' }))
    await waitFor(() => expect(api.processCalls('QB_PUBLICATION_PAUSE')).toEqual([{ publicationId: 'pub-1' }]))
    const resume = await within(screen.getAllByTestId('my-sponsor-publication')[0]).findByTestId('my-sponsor-resume')
    await userEvent.click(resume)
    await userEvent.click(await screen.findByRole('button', { name: 'Resume' }))
    await waitFor(() => expect(api.processCalls('QB_PUBLICATION_RESUME')).toEqual([{ publicationId: 'pub-1' }]))
  })

  it('tells a sponsor in review to finish onboarding on a computer', async () => {
    server({ account: { ...ACCOUNT, reviewStatus: 'PENDING' } })
    window.history.replaceState(null, '', '/my-sponsor')
    render(<App />)
    expect(await screen.findByTestId('my-sponsor-onboarding')).toBeInTheDocument()
    expect(screen.queryByTestId('my-sponsor-figures')).toBeNull()
  })

  it('leads a sponsor\'s own message to the sponsor page and says the console handles it', async () => {
    server({ inbox: [{ itemKind: 'MESSAGE', itemId: 'm1', type: 'REWARD', category: 'SPONSOR', kind: 'CASH_CONFIRMED',
      sender: null, sponsorName: null, title: { en: 'A cash reward was confirmed' }, body: { en: 'Mei confirmed it.' },
      markdown: false, publicationId: 'pub-1', action: 'CASH', actionId: 'c1', at: '2026-10-31T01:00:00Z',
      expiresAt: null, readAt: '2026-10-31T01:01:00Z', clickedAt: null, unread: false }] })
    window.history.replaceState(null, '', '/inbox/message/m1')
    render(<App />)
    expect(await screen.findByTestId('inbox-item-console'))
      .toHaveTextContent('handled in the sponsor console on a computer')
    expect(screen.getByTestId('inbox-item-go')).toHaveTextContent('Open My sponsor account')
  })

  it('gives a sponsor the sponsor\'s category in the inbox', async () => {
    server()
    window.history.replaceState(null, '', '/inbox')
    render(<App />)
    await userEvent.click(await screen.findByTestId('inbox-category'))
    expect(await screen.findByRole('option', { name: 'Sponsor' })).toBeInTheDocument()
  })
})
