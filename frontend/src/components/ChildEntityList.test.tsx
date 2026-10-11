import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/problem'
import i18n from '../i18n'
import type { ChildList } from '../meta/references'
import type { DatasetEntry, EntityMeta, FieldMeta } from '../meta/types'
import { expectAccessible } from '../test/axe'
import ChildEntityList from './ChildEntityList'

const NO_DICTIONARIES = vi.hoisted(() => ({}))
const server = vi.hoisted(() => ({ post: vi.fn<(path: string, options: unknown) => unknown>() }))

vi.mock('../api/client', () => ({
  api: { POST: (path: string, options: never) => server.post(path, options) },
  unwrap: async (value: unknown) => {
    const result = await value
    if (result instanceof ApiError) throw result
    return result
  },
}))
vi.mock('../auth/AuthContext', () => ({ useAuth: () => ({ can: () => false }) }))
vi.mock('../meta/hooks', () => ({
  useDatasets: () => ({ data: [] }),
  useProcesses: () => ({ data: [] }),
  // Stable, as the real hook's result is.
  useDictionaries: () => NO_DICTIONARIES,
}))
vi.mock('../meta/references', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../meta/references')>()),
  useEntityMetas: () => ({}),
}))

const base = { immutable: false, required: false, generated: false, systemManaged: false, sensitive: false, processOnly: false, rules: [], operators: ['EQ'] }
const certification: EntityMeta = {
  entity: 'Certification',
  label: 'Certification',
  primaryKey: 'certificationId',
  temporal: false,
  allowScheduled: false,
  publishesChanges: false,
  fields: [
    { ...base, name: 'certificationId', label: 'ID', type: 'semanticIdentity', urn: 'u', generated: true } as FieldMeta,
    { ...base, name: 'title', label: 'Title', type: 'text', multiline: false, required: true } as FieldMeta,
    { ...base, name: 'supplierId', label: 'Supplier', type: 'reference', targetEntity: 'Supplier' } as unknown as FieldMeta,
  ],
  references: [],
  listViews: [{ name: 'default', columns: ['title', 'supplierId'], filters: ['supplierId'], sorts: ['title'] }],
  dictionaries: [],
  unique: [],
  messages: {},
} as EntityMeta

const dataset = { id: 'urn:cert', entity: 'Certification', label: 'Certifications', isDefault: true, canWrite: true, listView: 'default' } as DatasetEntry

function list(canWrite = true) {
  const child: ChildList = { dataset: { ...dataset, canWrite }, entity: certification, field: 'supplierId' }
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <ChildEntityList child={child} parentId="s-1" />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return screen.getByTestId('child-list-Certification')
}

describe('ChildEntityList', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
    server.post.mockReset()
    server.post.mockImplementation(async (path, options) => {
      if (path === '/api/datasets/{resourceId}/query') {
        const { offset } = (options as { body: { offset: number } }).body
        return {
          items: Array.from({ length: 5 }, (_, i) => ({ id: `x${offset + i}`, version: 1, attributes: { title: `T${offset + i}`, supplierId: 's-1' } })),
          total: 7,
        }
      }
      return [{ id: 'x9', version: 1 }]
    })
  })

  it('lists the children of the parent five at a time, without the reference to it', async () => {
    const card = list()
    await within(card).findByText('T0')
    expect(server.post).toHaveBeenLastCalledWith('/api/datasets/{resourceId}/query', {
      params: { path: { resourceId: 'urn:cert' } },
      body: { filters: [{ field: 'supplierId', op: 'eq', value: 's-1' }], offset: 0, limit: 5 },
    })
    expect(within(card).getByRole('heading', { name: 'Certification' })).toBeInTheDocument()
    expect(within(card).queryByRole('columnheader', { name: 'Supplier' })).toBeNull()
    // Not sortable here, whatever the view allows.
    expect(within(within(card).getByRole('columnheader', { name: 'Title' })).queryByRole('button')).toBeNull()
    await expectAccessible()
    await userEvent.click(within(card).getByRole('button', { name: 'Next page' }))
    await within(card).findByText('T5')
    expect(server.post).toHaveBeenLastCalledWith('/api/datasets/{resourceId}/query', expect.objectContaining({
      body: expect.objectContaining({ offset: 5, limit: 5 }),
    }))
  })

  it('creates a child with the reference filled in, in a sheet over the parent, and reads the list again', async () => {
    const card = list()
    await within(card).findByText('T0')
    await userEvent.click(within(card).getByTestId('child-create'))
    const sheet = screen.getByRole('dialog', { name: 'New Certification' })
    expect(within(sheet).getByLabelText(/^Supplier/)).toBeDisabled()
    await userEvent.type(within(sheet).getByLabelText(/^Title/), 'Organic')
    const queries = server.post.mock.calls.length
    await userEvent.click(within(sheet).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(server.post.mock.calls[queries]).toEqual([
      '/api/datasets/{resourceId}/commit',
      {
        params: { path: { resourceId: 'urn:cert' } },
        body: {
          reason: undefined,
          changes: [{ action: 'INSERT', id: undefined, version: undefined, attributes: { title: 'Organic', supplierId: 's-1' }, effectiveTime: undefined }],
        },
      },
    ])
    await waitFor(() => expect(server.post.mock.calls.length).toBe(queries + 2))
  })

  it('only shows the children without the right to write', async () => {
    const card = list(false)
    await within(card).findByText('T0')
    expect(within(card).queryByTestId('child-create')).toBeNull()
    expect(within(card).getAllByTestId('child-open')[0]).toHaveTextContent('View')
    await userEvent.click(within(card).getAllByTestId('child-open')[0])
    const sheet = screen.getByRole('dialog', { name: 'Certification' })
    expect(within(sheet).getByLabelText(/^Title/)).toBeDisabled()
    expect(within(sheet).queryByRole('button', { name: 'Save' })).toBeNull()
  })
})
