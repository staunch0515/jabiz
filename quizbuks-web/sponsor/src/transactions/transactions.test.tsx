import { configureClient, session } from '@jabiz/client'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

const ROWS = [
  { entryId: 'a1', type: 'REWARD', status: 'PROCESSING', amount: '600', refunded: null,
    occurredAt: '2026-10-14T02:00:00Z', publicationId: 'p1', publicationName: 'Big prize', userKey: 'k1',
    userName: 'Mia', score: 5, fullScore: 5 },
  { entryId: 'a2', type: 'REWARD', status: 'COMPLETED', amount: '10', refunded: null,
    occurredAt: '2026-10-14T01:30:00Z', publicationId: 'p1', publicationName: 'Big prize', userKey: 'k2',
    userName: null, score: 3, fullScore: 5 },
  { entryId: 't1', type: 'TOPUP', status: 'COMPLETED', amount: '1000', refunded: '200',
    occurredAt: '2026-10-14T01:00:00Z', publicationId: null, publicationName: null, userKey: null, userName: null,
    score: null, fullScore: null },
]

interface Server {
  export?: { status: number; body: unknown }
}

/** The server as the console sees it, for a signed-in, approved sponsor. Records what the page asked for. */
function server(options: Server = {}) {
  const asked: { path: string; body: Record<string, unknown>; authorization: string | null }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const url = new URL(request.url, window.location.origin)
    // Only the export's address keeps its query (the format); the other calls are matched by path.
    const path = decodeURIComponent(url.pathname) + (url.pathname.endsWith('/export') ? url.search : '')
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') asked.push({ path, body, authorization: request.headers.get('Authorization') })
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 's1', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.sponsor.finance.read'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: [{ id: 's', version: 1, attributes: { reviewStatus: 'APPROVED' } }] })
      case '/api/queries/qb.sponsor.publications':
        return json(200, { items: [{ publicationId: 'p1', name: 'Big prize', status: 'LIVE' },
          { publicationId: 'p2', name: 'Still a draft', status: 'DRAFT' }], offset: 0, limit: 200 })
      case '/api/queries/qb.sponsor.transactions':
        return json(200, { items: ROWS, offset: 0, limit: 20, total: 3 })
      case '/api/queries/qb.sponsor.transaction-summary':
        return json(200, { items: [{ totalCount: 3, completedCount: 2, completedAmount: '1010', processingCount: 1,
          processingAmount: '600', failedCount: 0, failedAmount: '0' }], offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.transactions/export?format=csv':
      case '/api/queries/qb.sponsor.transactions/export?format=xlsx':
        if (options.export) return json(options.export.status, options.export.body)
        return new Response('entryId\r\n', { status: 200, headers: { 'Content-Type': 'text/csv',
          'Content-Disposition': 'attachment; filename="qb.sponsor.transactions.csv"' } })
      default:
        return json(404, {})
    }
  })
  const of = (path: string) => asked.filter((a) => a.path === path).map((a) => a.body)
  return {
    asked,
    list: () => of('/api/queries/qb.sponsor.transactions'),
    summary: () => of('/api/queries/qb.sponsor.transaction-summary'),
    exports: () => asked.filter((a) => a.path.includes('/export')),
  }
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => {
  vi.restoreAllMocks()
})

async function choose(user: ReturnType<typeof userEvent.setup>, field: string, option: string) {
  await user.click(screen.getByRole('combobox', { name: field }))
  await user.click(await screen.findByRole('option', { name: option }))
}

describe('the transactions page', () => {
  it('shows the totals and the rows in Kudos, the takers by their names', async () => {
    const { list, summary } = server()
    window.history.replaceState(null, '', '/transactions')
    render(<App />)
    expect(await screen.findByTestId('summary-total')).toHaveTextContent('3')
    expect(screen.getByTestId('summary-completed')).toHaveTextContent('1,010 Kudos (2)')
    expect(screen.getByTestId('summary-processing')).toHaveTextContent('600 Kudos (1)')
    expect(screen.getByTestId('summary-failed')).toHaveTextContent('0 Kudos (0)')
    const rows = await screen.findAllByTestId('transaction-row')
    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByTestId('transaction-user')).toHaveTextContent('Mia')
    expect(within(rows[0]).getByTestId('transaction-status')).toHaveTextContent('Processing')
    expect(within(rows[0]).getByTestId('transaction-amount')).toHaveTextContent('600 Kudos')
    expect(rows[0]).toHaveTextContent('5 / 5')
    expect(within(rows[1]).getByTestId('transaction-user')).toHaveTextContent('Unnamed user')
    expect(rows[2]).toHaveTextContent('Top-up')
    expect(rows[2]).toHaveTextContent('Refunded: 200 Kudos')
    expect(within(rows[2]).queryByTestId('transaction-only-user')).toBeNull()
    // The taker's key is a filter only: never shown.
    expect(document.body).not.toHaveTextContent('k1')
    expect(document.body).not.toHaveTextContent('JPY')
    expect(screen.getByRole('link', { name: 'Transactions' })).toHaveAttribute('aria-current', 'page')
    // The list and the totals were asked with the same (no) parameters, newest first.
    expect(list()[0]).toMatchObject({ params: {}, sorts: [{ field: 'occurredAt', asc: false }], offset: 0, limit: 20 })
    expect(summary()[0]).toMatchObject({ params: {} })
  })

  it('asks the list and the totals again with the filters', async () => {
    const { list, summary } = server()
    window.history.replaceState(null, '', '/transactions')
    const user = userEvent.setup()
    render(<App />)
    await screen.findAllByTestId('transaction-row')
    await choose(user, 'Type', 'Reward')
    await choose(user, 'Status', 'Completed')
    // Drafts have no rewards: they are not offered.
    await user.click(screen.getByRole('combobox', { name: 'Publication' }))
    expect(screen.queryByRole('option', { name: 'Still a draft' })).toBeNull()
    await user.click(await screen.findByRole('option', { name: 'Big prize' }))
    await user.type(screen.getByLabelText('Publication or user'), ' Mia ')
    await user.click(screen.getByRole('button', { name: 'Query' }))
    const expected = { type: 'REWARD', status: 'COMPLETED', publicationId: 'p1', q: 'Mia' }
    await waitFor(() => expect(list().at(-1)).toMatchObject({ params: expected, offset: 0 }))
    await waitFor(() => expect(summary().at(-1)).toEqual({ params: expected, limit: 1 }))
    // Reset asks for everything again.
    await user.click(screen.getByRole('button', { name: 'Reset' }))
    await waitFor(() => expect(list().at(-1)).toMatchObject({ params: {} }))
  })

  it('filters by one user from a row, shown as a chip that can be removed', async () => {
    const { list, summary } = server()
    window.history.replaceState(null, '', '/transactions')
    const user = userEvent.setup()
    render(<App />)
    const rows = await screen.findAllByTestId('transaction-row')
    await user.click(within(rows[0]).getByRole('button', { name: 'Only the transactions of Mia' }))
    expect(await screen.findByTestId('transactions-user-filter')).toHaveTextContent('User: Mia')
    await waitFor(() => expect(list().at(-1)).toMatchObject({ params: { userKey: 'k1' } }))
    await waitFor(() => expect(summary().at(-1)).toMatchObject({ params: { userKey: 'k1' } }))
    // Other filters keep the user.
    await choose(user, 'Type', 'Reward')
    await user.click(screen.getByRole('button', { name: 'Query' }))
    await waitFor(() => expect(list().at(-1)).toMatchObject({ params: { userKey: 'k1', type: 'REWARD' } }))
    await user.click(screen.getByRole('button', { name: 'Show every user' }))
    expect(screen.queryByTestId('transactions-user-filter')).toBeNull()
    await waitFor(() => expect(list().at(-1)).toMatchObject({ params: { type: 'REWARD' } }))
    expect(list().at(-1)?.params).not.toHaveProperty('userKey')
  })

  it('opens for one publication from its address', async () => {
    const { list, summary } = server()
    window.history.replaceState(null, '', '/transactions?publication=p1')
    render(<App />)
    await screen.findAllByTestId('transaction-row')
    expect(list()[0]).toMatchObject({ params: { publicationId: 'p1' } })
    expect(summary()[0]).toMatchObject({ params: { publicationId: 'p1' } })
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Publication' })).toHaveTextContent('Big prize'))
  })

  it('exports the rows shown, with the session, the same parameters and order', async () => {
    const { exports } = server()
    const created = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:export')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const clicked = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    window.history.replaceState(null, '', '/transactions?publication=p1')
    const user = userEvent.setup()
    render(<App />)
    const rows = await screen.findAllByTestId('transaction-row')
    await user.click(within(rows[0]).getByRole('button', { name: 'Only the transactions of Mia' }))
    await user.click(screen.getByTestId('transactions-export-csv'))
    await waitFor(() => expect(exports()).toHaveLength(1))
    expect(exports()[0]).toEqual({ path: '/api/queries/qb.sponsor.transactions/export?format=csv',
      body: { params: { publicationId: 'p1', userKey: 'k1' }, sorts: [{ field: 'occurredAt', asc: false }] },
      authorization: 'Bearer access' })
    await waitFor(() => expect(clicked).toHaveBeenCalled())
    expect(created).toHaveBeenCalled()
    expect(clicked.mock.instances[0]).toHaveProperty('download', 'qb.sponsor.transactions.csv')
    // Excel too; no PDF is offered.
    await user.click(screen.getByTestId('transactions-export-xlsx'))
    await waitFor(() => expect(exports()).toHaveLength(2))
    expect(exports()[1].path).toBe('/api/queries/qb.sponsor.transactions/export?format=xlsx')
    expect(screen.queryByText(/PDF/)).toBeNull()
  })

  it('says why an export was refused', async () => {
    server({ export: { status: 422, body: { code: 'REPORT_TOO_LARGE',
      detail: 'The export has more than 100,000 rows: narrow it down.' } } })
    window.history.replaceState(null, '', '/transactions')
    const user = userEvent.setup()
    render(<App />)
    await screen.findAllByTestId('transaction-row')
    await user.click(screen.getByTestId('transactions-export-csv'))
    expect(await screen.findByTestId('transactions-export-problem'))
      .toHaveTextContent('The export has more than 100,000 rows: narrow it down.')
  })
})
