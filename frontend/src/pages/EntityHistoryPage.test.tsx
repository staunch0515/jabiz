import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/problem'
import i18n from '../i18n'
import { expectAccessible } from '../test/axe'
import { carrier, version } from '../test/fixtures'
import EntityHistoryPage from './EntityHistoryPage'

const DATASET = 'urn:jabiz:dataset:default:Carrier'
const permissions = vi.hoisted(() => ({ value: new Set<string>() }))
const server = vi.hoisted(() => ({
  get: vi.fn<(path: string, options: { params: { query?: Record<string, string | undefined> } }) => unknown>(),
  post: vi.fn<(path: string, options: unknown) => unknown>(),
}))

vi.mock('../api/client', () => ({
  api: {
    GET: (path: string, options: never) => server.get(path, options),
    POST: (path: string, options: never) => server.post(path, options),
  },
  unwrap: async (value: unknown) => {
    const result = await value
    if (result instanceof ApiError) throw result
    return result
  },
}))
vi.mock('../auth/AuthContext', () => ({ useAuth: () => ({ can: (p: string) => permissions.value.has(p) }) }))
vi.mock('../components/UserName', () => ({ default: ({ id }: { id: string }) => <span>{id}</span> }))
vi.mock('../meta/hooks', () => ({
  useDataset: (id: string) => ({
    isLoading: false,
    data: id === DATASET ? { id: DATASET, entity: 'Carrier', label: 'Carrier' } : undefined,
  }),
  useEntityMeta: (name: string | undefined) => ({ isLoading: false, data: name ? carrier : undefined }),
  useDictionaries: () => ({}),
}))

const versions = [
  version(1, { carrierCode: 'A', creditLimit: 1000 }),
  version(2, { creditLimit: 2000 }, { reason: 'raise' }),
  version(3, { creditLimit: 3000 }, { effectStartTime: '2099-01-01T00:00:00Z' }),
]

function Address() {
  return <output data-testid="address">{useLocation().search}</output>
}

function page(search = '') {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[`/data/${encodeURIComponent(DATASET)}/c-1/history${search}`]}>
        <Routes>
          <Route
            path="/data/:datasetId/:entityId/history"
            element={
              <>
                <EntityHistoryPage />
                <Address />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('EntityHistoryPage', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
    permissions.value = new Set()
    server.get.mockReset()
    server.post.mockReset()
    server.get.mockImplementation(async (path, options) => {
      if (path.endsWith('/history')) return versions
      if (path === '/api/processes/executions/{processSeqId}') {
        return {
          processSeqId: 12, parentSeqId: null, revertsSeqId: null, processName: 'jabiz.dataset.commit',
          processVersion: 1, actorId: 'admin', reason: 'raise', opTime: '2026-01-02T00:00:00Z',
          items: [{ entityId: 'c-1', versionNo: 2 }], children: [],
        }
      }
      const asOf = options.params.query?.asOf
      if (asOf && asOf < '2026-01-01') return new ApiError(404, { violations: [] })
      if (asOf) return { id: 'c-1', version: 1, attributes: { carrierCode: 'A', creditLimit: 1000 } }
      return { id: 'c-1', version: 2, attributes: { carrierCode: 'A', creditLimit: 2000 } }
    })
  })

  it('shows the versions in two columns with a breadcrumb, and no actions the user may not take', async () => {
    page()
    expect(await screen.findByTestId('history-timeline')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('History of Carrier')
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' })
    expect(within(crumbs).getByRole('link', { name: 'Data' })).toHaveAttribute('href', '/data')
    expect(within(crumbs).getByRole('link', { name: 'Carrier' })).toHaveAttribute('href', `/data/${encodeURIComponent(DATASET)}`)
    expect(within(crumbs).getByText('History')).toHaveAttribute('aria-current', 'page')
    expect(screen.getAllByTestId(/^version-/).map((v) => v.dataset.testid)).toEqual(['version-3', 'version-2', 'version-1'])
    expect(within(screen.getByTestId('version-3')).getByText('Scheduled')).toBeInTheDocument()
    expect(screen.queryByTestId('revert')).toBeNull()
    expect(screen.queryByRole('button', { name: /Operation details/ })).toBeNull()
    expect(screen.queryByTestId('history-audit')).toBeNull()
    await expectAccessible()
  })

  it('views a version as of its time, writes the point into the address and marks what differs from now', async () => {
    page()
    await userEvent.click(within(await screen.findByTestId('version-1')).getByTestId('view-at'))
    const point = await screen.findByTestId('point-in-time')
    expect(point.querySelector('[data-field="creditLimit"]')).toHaveTextContent('1,000')
    expect(point).toHaveTextContent('Differs from now')
    expect(screen.getByTestId('address')).toHaveTextContent('asOf=2026-01-01T00%3A00%3A00Z')
    expect(screen.getByTestId('address')).toHaveTextContent('knownAt=')
    expect(screen.getByTestId('point-as-of')).toHaveValue(
      new Date('2026-01-01T00:00:00Z').toLocaleString('sv-SE').slice(0, 19),
    )

    // Typed into the field: a point before the entry existed.
    const asOf = screen.getByLabelText('State at')
    await userEvent.clear(asOf)
    await userEvent.type(asOf, '2025-06-01 00:00{Enter}')
    expect(await screen.findByTestId('point-not-existing')).toHaveTextContent('The entry did not exist at that time.')
    expect(screen.getByTestId('address')).toHaveTextContent(`asOf=${encodeURIComponent(new Date(2025, 5, 1).toISOString())}`)

    // Cleared: back to no point in time.
    await userEvent.click(screen.getAllByRole('button', { name: 'Clear' })[0])
    await userEvent.click(screen.getAllByRole('button', { name: 'Clear' })[0])
    expect(screen.getByTestId('address')).toBeEmptyDOMElement()
    expect(screen.queryByTestId('point-in-time')).toBeNull()
  })

  it('reads the point in time from the address', async () => {
    page('?asOf=2026-01-01T12:00:00Z')
    const point = await screen.findByTestId('point-in-time')
    expect(point.querySelector('[data-field="creditLimit"]')).toHaveTextContent('1,000')
    expect(server.get).toHaveBeenCalledWith('/api/datasets/{resourceId}/entities/{id}', {
      params: { path: { resourceId: DATASET, id: 'c-1' }, query: { asOf: '2026-01-01T12:00:00Z', knownAt: undefined } },
    })
  })

  it('opens the operation details in a sheet', async () => {
    permissions.value = new Set(['operation.read', 'audit.read'])
    page()
    expect(await screen.findByTestId('history-audit')).toHaveAttribute('href', '/audit?entityType=Carrier&entityId=c-1')
    await userEvent.click(within(screen.getByTestId('version-2')).getByRole('button', { name: 'Operation details #12' }))
    const sheet = await screen.findByRole('dialog', { name: 'Operation details #12' })
    expect(await within(sheet).findByTestId('operation-detail')).toHaveTextContent('jabiz.dataset.commit v1')
    expect(within(sheet).getByRole('table', { name: 'Written versions' })).toHaveTextContent('c-1')
    await expectAccessible()
    await userEvent.click(within(sheet).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('reverts an operation only with a reason, then shows the new history', async () => {
    permissions.value = new Set(['temporal.revert'])
    server.post.mockResolvedValue({})
    page()
    await userEvent.click(within(await screen.findByTestId('version-2')).getByTestId('revert'))
    const dialog = screen.getByRole('dialog', { name: 'Revert operation #12' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Revert' }))
    expect(within(dialog).getByText('Enter why it is reverted.')).toBeInTheDocument()
    expect(screen.getByTestId('revert-reason')).toHaveAttribute('aria-invalid', 'true')
    expect(server.post).not.toHaveBeenCalled()
    await expectAccessible()

    await userEvent.type(screen.getByTestId('revert-reason'), 'mistake')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Revert' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(server.post).toHaveBeenCalledWith('/api/processes/executions/{processSeqId}/revert', {
      params: { path: { processSeqId: 12 } },
      body: { reason: 'mistake' },
    })
    // The history and the entry are read again.
    await waitFor(() => expect(server.get.mock.calls.filter(([p]) => p.endsWith('/history'))).toHaveLength(2))
  })

  it('offers no revert on a write-once entity', async () => {
    permissions.value = new Set(['temporal.revert'])
    carrier.writeOnce = true
    try {
      page()
      await screen.findByTestId('history-timeline')
      expect(screen.queryByTestId('revert')).toBeNull()
    } finally {
      carrier.writeOnce = undefined
    }
  })

  it('says when the dataset is not known', async () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter initialEntries={['/data/nope/c-1/history']}>
          <Routes>
            <Route path="/data/:datasetId/:entityId/history" element={<EntityHistoryPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('nope')
  })
})
