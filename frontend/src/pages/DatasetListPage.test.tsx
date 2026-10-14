import { ApiError } from '@jabiz/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import type { DatasetEntry, EntityMeta, FieldMeta, ProcessEntry } from '../meta/types'
import { expectAccessible } from '../test/axe'
import { carrier, countries } from '../test/fixtures'
import DatasetListPage from './DatasetListPage'

const DATASET = 'urn:jabiz:dataset:default:Carrier'
const permissions = vi.hoisted(() => ({ value: new Set<string>() }))
const state = vi.hoisted(() => ({
  dataset: undefined as unknown,
  entity: undefined as unknown,
  processes: [] as unknown[],
  total: 0,
  deleted: new Set<string>(),
}))
// Stable, as the real hooks' results are (the columns are built from them).
const stable = vi.hoisted(() => ({
  auth: { can: (p: string) => permissions.value.has(p) },
  metas: {},
  labels: { supplierId: { s1: 'Kanto Tea' } },
}))
const server = vi.hoisted(() => ({ post: vi.fn<(path: string, options: unknown) => unknown>() }))

vi.mock('@jabiz/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@jabiz/client')>()),
  api: { POST: (path: string, options: never) => server.post(path, options) },
  unwrap: async (value: unknown) => {
    const result = await value
    if (result instanceof ApiError) throw result
    return result
  },
  useAuth: () => stable.auth,
}))
vi.mock('../meta/hooks', () => ({
  useDataset: (id: string) => ({ isLoading: false, data: id === DATASET ? state.dataset : undefined }),
  useEntityMeta: (name: string | undefined) => ({ isLoading: false, data: name ? state.entity : undefined }),
  useDatasets: () => ({ data: [state.dataset] }),
  useDictionaries: () => countries,
  useProcesses: () => ({ data: state.processes }),
}))
vi.mock('../meta/references', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../meta/references')>()),
  useEntityMetas: () => stable.metas,
  // References by their display text, fetched by page (useReferenceLabels is tested with the hooks).
  useReferenceLabels: () => stable.labels,
}))

const base = { immutable: false, required: false, generated: false, systemManaged: false, sensitive: false, processOnly: false, rules: [] }
const entity: EntityMeta = {
  ...carrier,
  fields: [
    ...carrier.fields,
    { ...base, operators: ['EQ'], name: 'supplierId', label: 'Supplier', type: 'reference', targetEntity: 'Supplier' } as unknown as FieldMeta,
    { ...base, operators: ['EQ', 'LIKE'], name: 'bankAccount', label: 'Account', type: 'text', multiline: false, masked: { permission: 'carrier.bank', style: 'LAST4' } } as FieldMeta,
  ],
  listViews: [
    {
      ...carrier.listViews[0],
      columns: [...carrier.listViews[0].columns!, 'supplierId', 'bankAccount'],
      filters: [...carrier.listViews[0].filters!, 'secret', 'bankAccount'],
      sorts: [...carrier.listViews[0].sorts!, 'bankAccount'],
    },
  ],
}

const dataset = {
  id: DATASET,
  entity: 'Carrier',
  label: 'Carriers',
  isDefault: true,
  temporal: true,
  allowScheduled: true,
  readOnly: false,
  processOnlyWrites: false,
  allowTimeTravel: true,
  softDelete: false,
  listView: 'default',
  maxQueryBatchSize: 100,
  canWrite: true,
} as DatasetEntry

function item(n: number) {
  return {
    id: `c-${n}`,
    version: 1,
    attributes: { carrierCode: `C${n}`, countryCode: 'JP', creditLimit: 1000 * n, active: true, supplierId: 's1', bankAccount: '****4931' },
  }
}

/** The bodies of the dataset queries sent so far. */
function queries() {
  return server.post.mock.calls
    .filter(([path]) => path === '/api/datasets/{resourceId}/query')
    .map(([, options]) => (options as { body: Record<string, unknown> }).body)
}
const lastQuery = () => queries().at(-1)!

function Address() {
  return <output data-testid="address">{useLocation().search}</output>
}

async function page(search = '') {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[`/data/${encodeURIComponent(DATASET)}${search}`]}>
        <Routes>
          <Route
            path="/data/:datasetId"
            element={
              <>
                <DatasetListPage />
                <Address />
              </>
            }
          />
          <Route path="*" element={<Address />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  await waitFor(() => expect(rows().length).toBeGreaterThan(0))
}

const table = () => screen.getByRole('table', { name: 'Carrier' })
const rows = () => Array.from(table().querySelectorAll<HTMLElement>('[data-slot="data-table-row"]'))

describe('DatasetListPage', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
    permissions.value = new Set()
    state.dataset = dataset
    state.entity = entity
    state.processes = []
    state.total = 45
    state.deleted = new Set()
    server.post.mockReset()
    server.post.mockImplementation(async (path, options) => {
      if (path === '/api/datasets/{resourceId}/query') {
        const body = (options as { body: { offset: number; limit: number } }).body
        const all = Array.from({ length: state.total }, (_, i) => item(i + 1)).filter((x) => !state.deleted.has(x.id))
        return { items: all.slice(body.offset, body.offset + body.limit), total: all.length }
      }
      const change = (options as { body: { changes: { action: string; id: string }[] } }).body.changes[0]
      if (change.action === 'DELETE') state.deleted.add(change.id)
      return [{ id: 'c-1', version: 2 }]
    })
  })

  it('asks for the first page in the view default order and shows it with labels, in its breadcrumb', async () => {
    await page()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Carrier')
    expect(screen.getByRole('navigation', { name: /breadcrumb/i })).toHaveTextContent('Data')
    expect(lastQuery()).toEqual({
      filters: [],
      sorts: [{ field: 'carrierCode', asc: true }],
      offset: 0,
      limit: 20,
      asOf: undefined,
      knownAt: undefined,
    })
    expect(rows()).toHaveLength(20)
    // A code by its dictionary label, an amount with its currency, a reference by its display text.
    expect(rows()[0]).toHaveTextContent('Japan')
    expect(rows()[0]).toHaveTextContent('Kanto Tea')
    expect(rows()[0]).not.toHaveTextContent('s1')
    expect(screen.getByRole('columnheader', { name: /Code/ })).toHaveAttribute('aria-sort', 'ascending')
    expect(screen.getByText('45 items')).toBeInTheDocument()
    await expectAccessible()
  })

  it('pages through the server, 10 / 20 / 50 / 100 rows at a time', async () => {
    await page()
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }))
    await waitFor(() => expect(lastQuery()).toMatchObject({ offset: 20, limit: 20 }))
    await userEvent.click(screen.getByRole('combobox', { name: 'Rows per page' }))
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual(['10', '20', '50', '100'])
    await userEvent.click(screen.getByRole('option', { name: '50' }))
    await waitFor(() => expect(lastQuery()).toMatchObject({ offset: 0, limit: 50 }))
  })

  it('sorts by whitelisted columns only, back at the first page', async () => {
    await page()
    // Not whitelisted: a plain header.
    expect(within(screen.getByRole('columnheader', { name: 'Country' })).queryByRole('button')).toBeNull()
    expect(screen.getByRole('columnheader', { name: 'Country' })).not.toHaveAttribute('aria-sort')
    // A masked field without its permission: not sortable either.
    expect(within(screen.getByRole('columnheader', { name: 'Account' })).queryByRole('button')).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }))
    await userEvent.click(within(screen.getByRole('columnheader', { name: /Credit limit/ })).getByRole('button'))
    await waitFor(() => expect(lastQuery()).toMatchObject({ sorts: [{ field: 'creditLimit', asc: true }], offset: 0 }))
    await userEvent.click(within(screen.getByRole('columnheader', { name: /Credit limit/ })).getByRole('button'))
    await waitFor(() => expect(lastQuery()).toMatchObject({ sorts: [{ field: 'creditLimit', asc: false }] }))
    // A third click returns to the server's default order.
    await userEvent.click(within(screen.getByRole('columnheader', { name: /Credit limit/ })).getByRole('button'))
    await waitFor(() => expect(lastQuery()).toMatchObject({ sorts: [] }))
  })

  it('filters by whitelisted fields only, from the first page; a reset clears them', async () => {
    await page()
    const filters = screen.getByRole('search')
    // Never a sensitive field; a masked one only for holders of its permission.
    expect(within(filters).queryByLabelText('Secret')).toBeNull()
    expect(within(filters).queryByLabelText('Account')).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }))
    await userEvent.type(within(filters).getByLabelText('Code'), 'AB')
    await userEvent.type(within(filters).getByRole('textbox', { name: 'Credit limit, from' }), '1500')
    await userEvent.click(within(filters).getByRole('button', { name: 'Query' }))
    await waitFor(() =>
      expect(lastQuery()).toMatchObject({
        filters: [
          { field: 'carrierCode', op: 'like', value: '%AB%' },
          { field: 'creditLimit', op: 'gte', value: '1500' },
        ],
        offset: 0,
      }),
    )
    await userEvent.click(within(filters).getByRole('button', { name: 'Reset' }))
    await waitFor(() => expect(lastQuery()).toMatchObject({ filters: [], offset: 0 }))
  })

  it('offers holders of its permission to filter, sort and show a masked field', async () => {
    permissions.value = new Set(['carrier.bank'])
    await page()
    expect(within(screen.getByRole('search')).getByLabelText('Account')).toBeInTheDocument()
    expect(within(screen.getByRole('columnheader', { name: /Account/ })).getByRole('button')).toBeInTheDocument()
    expect(within(rows()[0]).getByTestId('reveal-bankAccount')).toBeInTheDocument()
  })

  it('reads the data at a point in time from the address, read-only', async () => {
    const asOf = '2026-01-01T00:00:00Z'
    await page(`?asOf=${encodeURIComponent(asOf)}`)
    expect(lastQuery()).toMatchObject({ asOf, knownAt: undefined })
    expect(screen.getByTestId('time-travel')).toHaveTextContent('Editing is only possible on the current data.')
    expect(screen.queryByTestId('create')).toBeNull()
    expect(within(rows()[0]).queryByTestId('row-edit')).toBeNull()
    expect(within(rows()[0]).queryByTestId('row-delete')).toBeNull()
    expect(within(rows()[0]).getByTestId('row-view')).toBeInTheDocument()

    // Clearing the time returns to the current data, in the address too.
    await userEvent.click(screen.getAllByRole('button', { name: 'Clear' })[0])
    await waitFor(() => expect(screen.getByTestId('address')).toHaveTextContent(/^$/))
    await waitFor(() => expect(lastQuery()).toMatchObject({ asOf: undefined }))
    expect(screen.getByTestId('create')).toBeInTheDocument()
  })

  it('writes the chosen point in time into the address', async () => {
    await page()
    await userEvent.type(screen.getByLabelText('As of'), '2026-01-31 09:00:00{Enter}')
    const expected = new Date(2026, 0, 31, 9).toISOString()
    await waitFor(() => expect(screen.getByTestId('address')).toHaveTextContent(`asOf=${encodeURIComponent(expected)}`))
    await waitFor(() => expect(lastQuery()).toMatchObject({ asOf: expected }))
  })

  it('deletes after a confirmation and reads the page again', async () => {
    await page()
    const before = queries().length
    await userEvent.click(within(rows()[0]).getByTestId('row-delete'))
    const confirm = screen.getByRole('alertdialog', { name: 'Delete this entry?' })
    await expectAccessible()
    await userEvent.click(within(confirm).getByRole('button', { name: 'OK' }))
    await waitFor(() =>
      expect(server.post).toHaveBeenCalledWith('/api/datasets/{resourceId}/commit', {
        params: { path: { resourceId: DATASET } },
        body: { changes: [{ action: 'DELETE', id: 'c-1', version: 1 }] },
      }),
    )
    await waitFor(() => expect(queries().length).toBe(before + 1))
  })

  it('returns to the last page there is when a deletion emptied the page shown, focus on the table', async () => {
    state.total = 21
    await page()
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }))
    await waitFor(() => expect(rows()).toHaveLength(1))
    expect(rows()[0]).toHaveTextContent('C21')
    await userEvent.click(within(rows()[0]).getByTestId('row-delete'))
    await userEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'OK' }))
    await waitFor(() => expect(lastQuery()).toMatchObject({ offset: 0, limit: 20 }))
    await waitFor(() => expect(rows()).toHaveLength(20))
    expect(screen.getByText('Page 1 of 1')).toBeInTheDocument()
    // The deleted row's button went with it: focus is on the table, not lost to the page.
    await waitFor(() => expect(document.activeElement).toBe(table()))
  })

  it('keeps focus on the delete button of a row that is still there after cancelling', async () => {
    await page()
    const button = within(rows()[0]).getByTestId('row-delete')
    await userEvent.click(button)
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(document.activeElement).toBe(button))
  })

  it('keeps the rows of the previous page out of use while the next one loads', async () => {
    await page()
    let answer: (value: unknown) => void = () => {}
    server.post.mockImplementationOnce(() => new Promise((resolve) => { answer = resolve }))
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }))
    const body = table().querySelector('tbody')!
    await waitFor(() => expect(body).toHaveAttribute('inert'))
    expect(table()).toHaveAttribute('aria-busy', 'true')
    await act(async () => answer({ items: [item(21)], total: 21 }))
    await waitFor(() => expect(body).not.toHaveAttribute('inert'))
    expect(rows()[0]).toHaveTextContent('C21')
  })

  it('returns focus to the row button that opened the form when it closes', async () => {
    await page()
    const edit = within(rows()[0]).getByTestId('row-edit')
    await userEvent.click(edit)
    await screen.findByRole('dialog', { name: 'Edit Carrier' })
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(edit))
  })

  it('offers what the user may do: edit, delete, history, audit, actions', async () => {
    permissions.value = new Set(['audit.read'])
    state.processes = [
      { name: 'CARRIER_SUSPEND', version: 1, latest: true, deprecated: false, label: 'Suspend', actsOn: { entity: 'Carrier', input: 'carrierId' } } as unknown as ProcessEntry,
    ]
    await page()
    const row = within(rows()[0])
    expect(row.getByTestId('row-edit')).toBeInTheDocument()
    expect(row.getByTestId('row-delete')).toBeInTheDocument()
    expect(row.getByTestId('row-history')).toHaveAttribute('href', `/data/${encodeURIComponent(DATASET)}/c-1/history`)
    expect(row.getByTestId('row-audit')).toHaveAttribute('href', '/audit?entityType=Carrier&entityId=c-1')
    expect(row.getByTestId('row-action-CARRIER_SUSPEND')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Actions' })).toHaveClass('sticky')
  })

  it('only shows the entries without the right to write, and no history without time travel', async () => {
    state.dataset = { ...dataset, canWrite: false, allowTimeTravel: false }
    await page()
    expect(screen.queryByTestId('create')).toBeNull()
    const row = within(rows()[0])
    expect(row.queryByTestId('row-edit')).toBeNull()
    expect(row.queryByTestId('row-delete')).toBeNull()
    expect(row.queryByTestId('row-history')).toBeNull()
    expect(row.queryByTestId('row-audit')).toBeNull()
    expect(screen.queryByLabelText('As of')).toBeNull()
    await userEvent.click(row.getByTestId('row-view'))
    const sheet = screen.getByRole('dialog', { name: 'Carrier' })
    expect(within(sheet).queryByRole('button', { name: 'Save' })).toBeNull()
  })

  it('never deletes or changes a write-once entry, only adds', async () => {
    state.entity = { ...entity, writeOnce: true }
    await page()
    expect(screen.getByTestId('create')).toBeInTheDocument()
    expect(within(rows()[0]).queryByTestId('row-delete')).toBeNull()
    expect(within(rows()[0]).getByTestId('row-view')).toBeInTheDocument()
  })

  it('opens the form for a new entry and reads the page again once it is saved', async () => {
    await page()
    await userEvent.click(screen.getByTestId('create'))
    const sheet = screen.getByRole('dialog', { name: 'New Carrier' })
    await userEvent.type(within(sheet).getByLabelText(/^Code/), 'NEW')
    await userEvent.type(within(sheet).getByLabelText(/^Credit limit/), '5')
    const before = queries().length
    await userEvent.click(within(sheet).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(queries().length).toBe(before + 1))
  })

  it('says when the dataset does not exist', async () => {
    state.dataset = undefined
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter initialEntries={['/data/urn:none']}>
          <Routes>
            <Route path="/data/:datasetId" element={<DatasetListPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('urn:none')
  })
})
