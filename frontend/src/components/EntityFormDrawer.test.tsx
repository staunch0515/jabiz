import { ApiError } from '@jabiz/client'
import { notify } from '@jabiz/ui'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import type { DatasetEntry, EntityInstance, EntityMeta, FieldMeta, ProcessEntry } from '../meta/types'
import { expectAccessible } from '@jabiz/ui/testing'
import { carrier, countries } from '../test/fixtures'
import EntityFormDrawer from './EntityFormDrawer'

const permissions = vi.hoisted(() => ({ value: new Set<string>() }))
const catalog = vi.hoisted(() => ({
  processes: [] as unknown[],
  datasets: [] as unknown[],
  metas: {} as Record<string, unknown>,
  dictionaries: {},
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
  useAuth: () => ({ can: (p: string) => permissions.value.has(p) }),
}))
vi.mock('../meta/hooks', () => ({
  useDatasets: () => ({ data: catalog.datasets }),
  useProcesses: () => ({ data: catalog.processes }),
  // Stable, as the real hook's result is.
  useDictionaries: () => catalog.dictionaries,
}))
vi.mock('../meta/references', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../meta/references')>()),
  useEntityMetas: () => catalog.metas,
}))

const dataset: DatasetEntry = {
  id: 'urn:d',
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

const base = { immutable: false, required: false, generated: false, systemManaged: false, sensitive: false, rules: [], operators: [] }
/** The Carrier fixture with a process-only status, a masked account and a reference-free set of kinds. */
const entity: EntityMeta = {
  ...carrier,
  fields: [
    ...carrier.fields,
    { ...base, name: 'status', label: 'Status', type: 'text', multiline: false, processOnly: true } as FieldMeta,
    { ...base, name: 'bankAccount', label: 'Account', type: 'text', multiline: false, processOnly: false, masked: { permission: 'carrier.bank', style: 'LAST4' } } as FieldMeta,
    { ...base, name: 'since', label: 'Since', type: 'temporal', role: 'EVENT_TIME', processOnly: false } as FieldMeta,
    {
      ...base,
      name: 'logo',
      label: 'Logo',
      type: 'custom',
      kindId: 'jabiz.file',
      policy: 'carrier.logo',
      accept: ['image/png', '.png'],
      maxBytes: 4096,
      image: true,
      variants: [],
      processOnly: false,
    } as unknown as FieldMeta,
  ],
}

const stored: EntityInstance = {
  id: 'c-1',
  version: 4,
  attributes: {
    carrierId: 'c-1',
    carrierCode: 'ABC',
    note: 'first',
    countryCode: 'JP',
    creditLimit: 1000,
    active: true,
    status: 'DRAFT',
    bankAccount: '****4931',
    // Finer than the picker's milliseconds: unchanged, so not sent.
    since: '2026-01-31T09:00:00.123456Z',
  },
}

type DrawerProps = Partial<Parameters<typeof EntityFormDrawer>[0]>

function form(props: DrawerProps = {}) {
  const onSaved = vi.fn()
  const onOpenChange = vi.fn()
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const drawer = (more: DrawerProps) => (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <EntityFormDrawer
          dataset={dataset}
          entity={entity}
          dictionaries={countries}
          open
          onOpenChange={onOpenChange}
          onSaved={onSaved}
          {...props}
          {...more}
        />
      </MemoryRouter>
    </QueryClientProvider>
  )
  const { rerender } = render(drawer({}))
  return {
    onSaved,
    onOpenChange,
    sheet: screen.getByRole('dialog'),
    /** Renders the same (still mounted) drawer with other props, as a caller that keeps it does. */
    update: (more: DrawerProps) => rerender(drawer(more)),
  }
}

/** The body of the one commit sent. */
function sent() {
  expect(server.post).toHaveBeenCalledTimes(1)
  const [path, options] = server.post.mock.calls[0] as [string, { params: unknown; body: { reason?: string; changes: Record<string, unknown>[] } }]
  expect(path).toBe('/api/datasets/{resourceId}/commit')
  expect(options.params).toEqual({ path: { resourceId: 'urn:d' } })
  return options.body
}

const save = () => userEvent.click(screen.getByRole('button', { name: 'Save' }))

describe('EntityFormDrawer', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
    permissions.value = new Set()
    catalog.processes = []
    catalog.datasets = []
    catalog.metas = {}
    server.post.mockReset()
    server.post.mockResolvedValue([{ id: 'c-1', version: 5 }])
    vi.restoreAllMocks()
  })

  it('creates an entry with what was filled in: "no" for untouched switches, nothing for empty fields', async () => {
    const success = vi.spyOn(notify, 'success')
    const { onSaved, sheet } = form()
    expect(within(sheet).getByRole('heading', { name: 'New Carrier' })).toBeInTheDocument()
    // Not offered: generated, system, sensitive fields; on creation the process-only ones.
    expect(within(sheet).queryByLabelText(/^Carrier ID/)).toBeNull()
    expect(within(sheet).queryByLabelText(/^Secret/)).toBeNull()
    expect(within(sheet).queryByLabelText(/^Status/)).toBeNull()
    await expectAccessible()

    await userEvent.type(within(sheet).getByLabelText(/^Code/), 'NEW1')
    await userEvent.click(within(sheet).getByRole('combobox', { name: /^Country/ }))
    await userEvent.click(screen.getByRole('option', { name: 'China' }))
    await userEvent.type(within(sheet).getByLabelText(/^Credit limit/), '1234.5')
    await userEvent.type(within(sheet).getByLabelText(/^Reason/), '  opening ')
    // A scale the currency does not have is neither refused while typing nor rounded: the rule says so.
    expect(within(sheet).getByLabelText(/^Credit limit/)).toHaveValue('1234.5')
    expect(within(sheet).getByText('MONETARY_SCALE')).toHaveAttribute('data-rule-code', 'MONETARY_SCALE')
    await userEvent.clear(within(sheet).getByLabelText(/^Credit limit/))
    await userEvent.type(within(sheet).getByLabelText(/^Credit limit/), '1234')
    await save()

    const body = sent()
    expect(body.reason).toBe('opening')
    expect(body.changes).toEqual([
      {
        action: 'INSERT',
        id: undefined,
        version: undefined,
        attributes: { carrierCode: 'NEW1', countryCode: 'CN', creditLimit: '1234', active: false },
        effectiveTime: undefined,
      },
    ])
    expect(success).toHaveBeenCalledWith('Saved.')
    expect(onSaved).toHaveBeenCalled()
  })

  it('checks a new entry as a whole before sending, and each field as it changes', async () => {
    const { sheet } = form()
    await save()
    expect(server.post).not.toHaveBeenCalled()
    const code = within(sheet).getByLabelText(/^Code/)
    expect(code).toHaveAttribute('aria-invalid', 'true')
    const describedBy = code.getAttribute('aria-describedby')!
    expect(document.getElementById(describedBy)?.querySelector('[data-rule-code]')).toHaveAttribute('data-rule-code', 'REQUIRED')
    expect(within(sheet).getByLabelText(/^Credit limit/)).toHaveAttribute('aria-invalid', 'true')
    await expectAccessible()

    // Typing replaces the field's problem by what the rules say now.
    await userEvent.type(code, 'ABCDEFGHIJK')
    expect(document.getElementById(describedBy)?.querySelector('[data-rule-code]')).toHaveAttribute('data-rule-code', 'TOO_LONG')
    await userEvent.clear(code)
    await userEvent.type(code, 'OK1')
    expect(code).not.toHaveAttribute('aria-invalid')
    // The other field's problem stays until it changes.
    expect(within(sheet).getByLabelText(/^Credit limit/)).toHaveAttribute('aria-invalid', 'true')
  })

  it('sends only what changed when editing, a cleared field as null, times compared to the millisecond', async () => {
    permissions.value = new Set(['carrier.bank'])
    const { sheet } = form({ instance: stored })
    expect(within(sheet).getByRole('heading', { name: 'Edit Carrier' })).toBeInTheDocument()
    // Immutable fields and the key are fixed once created; process-only fields are shown, not written.
    expect(within(sheet).getByLabelText(/^Code/)).toBeDisabled()
    expect(within(sheet).getByLabelText(/^Status/)).toBeDisabled()
    expect(within(sheet).getByLabelText(/^Account/)).toBeEnabled()

    await userEvent.clear(within(sheet).getByLabelText(/^note/))
    await userEvent.clear(within(sheet).getByLabelText(/^Credit limit/))
    await userEvent.type(within(sheet).getByLabelText(/^Credit limit/), '1000.0')
    await userEvent.click(within(sheet).getByRole('switch', { name: /^Active/ }))
    await save()

    expect(sent().changes).toEqual([
      {
        action: 'UPDATE',
        id: 'c-1',
        version: 4,
        // The amount is the same number; the time and the untouched fields are not sent.
        attributes: { note: null, active: false },
        effectiveTime: undefined,
      },
    ])
  })

  it('says when nothing changed instead of sending', async () => {
    const { sheet } = form({ instance: stored })
    await save()
    expect(server.post).not.toHaveBeenCalled()
    expect(within(sheet).getByTestId('form-error')).toHaveTextContent('Nothing was changed.')
  })

  it('keeps a masked field read-only without its permission, so its masked form is never sent', async () => {
    const { sheet } = form({ instance: stored })
    expect(within(sheet).getByLabelText(/^Account/)).toBeDisabled()
    expect(within(sheet).getByLabelText(/^Account/)).toHaveValue('****4931')
  })

  it('fixes and sends a preset value of a child created from its parent', async () => {
    const { sheet } = form({ preset: { carrierCode: 'PARENT' } })
    expect(within(sheet).getByLabelText(/^Code/)).toBeDisabled()
    expect(within(sheet).getByLabelText(/^Code/)).toHaveValue('PARENT')
    await userEvent.type(within(sheet).getByLabelText(/^Credit limit/), '5')
    await save()
    expect(sent().changes[0].attributes).toEqual({ carrierCode: 'PARENT', creditLimit: '5', active: false })
  })

  it('sends when a change takes effect and why', async () => {
    const { sheet } = form({ instance: stored })
    await userEvent.type(within(sheet).getByLabelText('Takes effect at'), '2031-01-01 09:00:00{Enter}')
    await userEvent.type(within(sheet).getByLabelText(/^Reason/), 'price list 2031')
    await save()
    const body = sent()
    expect(body.reason).toBe('price list 2031')
    // Only the time: a scheduled change of nothing else is still a change.
    expect(body.changes[0]).toMatchObject({ attributes: {}, effectiveTime: new Date(2031, 0, 1, 9).toISOString() })
  })

  it('offers no later effective time when the dataset does not schedule, only backdates', async () => {
    permissions.value = new Set(['temporal.backdate'])
    const { sheet } = form({ instance: stored, dataset: { ...dataset, allowScheduled: false } })
    const effective = within(sheet).getByLabelText('Takes effect at')
    await userEvent.type(effective, '2099-01-01 09:00:00{Enter}')
    expect(effective).toHaveAttribute('aria-invalid', 'true')
    await userEvent.clear(within(sheet).getByLabelText(/^note/))
    await save()
    // Typed text that is no allowed time keeps the form from being sent.
    expect(server.post).not.toHaveBeenCalled()
  })

  it('has no effective time without scheduling or the backdating permission', () => {
    const { sheet } = form({ instance: stored, dataset: { ...dataset, allowScheduled: false } })
    expect(within(sheet).queryByLabelText('Takes effect at')).toBeNull()
    expect(within(sheet).getByLabelText(/^Reason/)).toBeInTheDocument()
  })

  it('puts the server violations at their fields with their rule codes, the others above', async () => {
    server.post.mockResolvedValue(
      new ApiError(422, {
        detail: 'Refused',
        violations: [
          { field: 'carrierCode', ruleCode: 'CARRIER_CODE_TAKEN', message: 'The code is taken.' },
          { field: null, ruleCode: 'LIMIT', message: 'Too many carriers.' },
        ],
      }),
    )
    const { sheet, onSaved } = form()
    await userEvent.type(within(sheet).getByLabelText(/^Code/), 'TAKEN')
    await userEvent.type(within(sheet).getByLabelText(/^Credit limit/), '1')
    await save()
    const code = within(sheet).getByLabelText(/^Code/)
    expect(code).toHaveAttribute('aria-invalid', 'true')
    const errors = document.getElementById(code.getAttribute('aria-describedby')!)!
    expect(errors.querySelector('[data-rule-code]')).toHaveAttribute('data-rule-code', 'CARRIER_CODE_TAKEN')
    expect(errors).toHaveTextContent('The code is taken.')
    expect(within(sheet).getByTestId('form-error')).toHaveTextContent('Too many carriers.')
    expect(onSaved).not.toHaveBeenCalled()
    await expectAccessible()
  })

  it('asks to reload when someone else changed the entry (409)', async () => {
    server.post.mockResolvedValue(new ApiError(409, { detail: 'Conflict' }))
    const { sheet } = form({ instance: stored })
    await userEvent.type(within(sheet).getByLabelText(/^note/), ' more')
    await save()
    expect(within(sheet).getByTestId('form-error')).toHaveTextContent('Someone else changed this entry in the meantime.')
  })

  it('only shows in the read-only mode: no saving, the entry actions, nothing to change', async () => {
    catalog.processes = [
      {
        name: 'CARRIER_SUSPEND',
        latest: true,
        deprecated: false,
        version: 1,
        label: 'Suspend',
        input: { type: 'object', properties: { carrierId: { type: 'string' } } },
        actsOn: { entity: 'Carrier', input: 'carrierId' },
      } as unknown as ProcessEntry,
    ]
    const { sheet } = form({ instance: stored, readOnly: true })
    expect(within(sheet).getByRole('heading', { name: 'Carrier' })).toBeInTheDocument()
    expect(within(sheet).queryByRole('button', { name: 'Save' })).toBeNull()
    expect(within(sheet).getByLabelText(/^note/)).toBeDisabled()
    expect(within(sheet).queryByLabelText('Takes effect at')).toBeNull()
    expect(within(sheet).getByTestId('detail-actions')).toHaveTextContent('Suspend')
  })

  it('shows an entry of the past without actions', () => {
    catalog.processes = [
      { name: 'CARRIER_SUSPEND', latest: true, deprecated: false, version: 1, label: 'Suspend', actsOn: { entity: 'Carrier', input: 'carrierId' } } as unknown as ProcessEntry,
    ]
    const { sheet } = form({ instance: stored, readOnly: true, historical: true })
    expect(within(sheet).queryByTestId('detail-actions')).toBeNull()
  })

  it('starts afresh when reopened or switched to another entry while it stays mounted', async () => {
    const other: EntityInstance = {
      id: 'c-2',
      version: 7,
      attributes: { ...stored.attributes, carrierId: 'c-2', carrierCode: 'XYZ', note: 'second', bankAccount: null },
    }
    const { update } = form({ instance: stored })
    // Edited, then closed without saving; and a problem left behind.
    await userEvent.type(screen.getByLabelText(/^note/), ' edited')
    await userEvent.clear(screen.getByLabelText(/^Credit limit/))
    expect(screen.getByLabelText(/^Credit limit/)).toHaveAttribute('aria-invalid', 'true')
    update({ instance: stored, open: false })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    update({ instance: other, open: true })
    const sheet = await screen.findByRole('dialog')
    expect(within(sheet).getByLabelText(/^note/)).toHaveValue('second')
    expect(within(sheet).getByLabelText(/^Code/)).toHaveValue('XYZ')
    expect(within(sheet).getByLabelText(/^Credit limit/)).not.toHaveAttribute('aria-invalid')

    // Switched to another entry while open: that entry's values.
    update({ instance: stored, open: true })
    expect(within(screen.getByRole('dialog')).getByLabelText(/^note/)).toHaveValue('first')
    update({ instance: other, open: true })
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('switch', { name: /^Active/ }))
    await save()
    expect(sent().changes).toEqual([
      { action: 'UPDATE', id: 'c-2', version: 7, attributes: { active: false }, effectiveTime: undefined },
    ])
  })

  it('closes with Cancel and forgets what was typed', async () => {
    const { onOpenChange, update } = form({ instance: stored })
    await userEvent.type(screen.getByLabelText(/^note/), ' edited')
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(onOpenChange).toHaveBeenLastCalledWith(false)
    update({ instance: stored, open: false })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    update({ instance: stored, open: true })
    expect(within(await screen.findByRole('dialog')).getByLabelText(/^note/)).toHaveValue('first')
  })

  it('takes an integer as typed text, refusing what is no digit, and is not sent while incomplete', async () => {
    const counted = {
      ...entity,
      fields: [...entity.fields, { ...base, name: 'seats', label: 'Seats', type: 'version', processOnly: false } as FieldMeta],
    }
    const { sheet } = form({ instance: stored, entity: counted })
    const seats = within(sheet).getByLabelText(/^Seats/)
    // Not a number field: the mouse wheel does not change it, half-typed text is not dropped.
    expect(seats).not.toHaveAttribute('type', 'number')
    expect(seats).toHaveAttribute('inputmode', 'numeric')
    await userEvent.type(seats, '12e.5')
    expect(seats).toHaveValue('125')
    await userEvent.clear(seats)
    await userEvent.type(seats, '-')
    await save()
    expect(server.post).not.toHaveBeenCalled()
    expect(seats).toHaveAttribute('aria-invalid', 'true')
    await userEvent.type(seats, '3')
    await save()
    expect(sent().changes[0].attributes).toEqual({ seats: '-3' })
  })

  it('is not saved while a file is still uploading, and marks a refused file', async () => {
    let finish: (response: Response) => void = () => {}
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => { finish = resolve })))
    try {
      const { sheet } = form({ instance: stored })
      const input = sheet.querySelector<HTMLInputElement>('input[type=file]')!
      fireEvent.change(input, { target: { files: [new File(['png'], 'logo.png', { type: 'image/png' })] } })
      const saveButton = within(sheet).getByRole('button', { name: /Save/ })
      await waitFor(() => expect(saveButton).toBeDisabled())
      fireEvent.submit(sheet.querySelector('form')!)
      expect(server.post).not.toHaveBeenCalled()
      await act(async () =>
        finish(Response.json({ fileId: '0190b8a0-0000-7000-8000-000000000001', policy: 'carrier.logo', contentType: 'image/png', sizeBytes: 3, variants: [] }, { status: 201 })),
      )
      await waitFor(() => expect(saveButton).toBeEnabled())
      server.post.mockResolvedValue(
        new ApiError(400, { violations: [{ field: 'logo', ruleCode: 'FILE_NOT_FOUND', message: 'No such file.' }] }),
      )
      await save()
      expect(sent().changes[0].attributes).toEqual({ logo: '0190b8a0-0000-7000-8000-000000000001' })
      expect(input).toHaveAttribute('aria-invalid', 'true')
      expect(document.getElementById(input.getAttribute('aria-describedby')!.split(' ')[0])).toHaveTextContent('No such file.')
    } finally {
      vi.unstubAllGlobals()
    }
  })

  describe('with children', () => {
    const certification = {
      entity: 'Certification',
      label: 'Certification',
      primaryKey: 'certificationId',
      temporal: false,
      allowScheduled: false,
      publishesChanges: false,
      fields: [
        { ...base, name: 'certificationId', label: 'ID', type: 'semanticIdentity', urn: 'u', generated: true, processOnly: false } as FieldMeta,
        { ...base, name: 'title', label: 'Title', type: 'text', multiline: false, processOnly: false } as FieldMeta,
        { ...base, operators: ['EQ'], name: 'carrierId', label: 'Carrier', type: 'reference', targetEntity: 'Carrier', processOnly: false } as unknown as FieldMeta,
      ],
      references: [],
      listViews: [{ name: 'default', columns: ['title', 'carrierId'], filters: ['carrierId'], sorts: [] }],
      dictionaries: [],
      unique: [],
      messages: {},
    } as unknown as EntityMeta
    const children = { ...dataset, id: 'urn:cert', entity: 'Certification', label: 'Certifications' } as DatasetEntry

    beforeEach(() => {
      catalog.datasets = [children]
      catalog.metas = { Certification: certification }
      server.post.mockImplementation(async (path) =>
        path === '/api/datasets/{resourceId}/query'
          ? { items: [{ id: 'x1', version: 3, attributes: { title: 'Organic', carrierId: 'c-1' } }], total: 1 }
          : [{ id: 'x1', version: 4 }],
      )
    })

    const commits = () => server.post.mock.calls.filter(([path]) => path === '/api/datasets/{resourceId}/commit')

    it('opens a child over the parent; Escape closes only the child, focus returns to its button', async () => {
      const { sheet } = form({ instance: stored })
      const open = await within(sheet).findByTestId('child-open')
      await userEvent.click(open)
      const child = await screen.findByRole('dialog', { name: 'Edit Certification' })
      expect(within(child).getByLabelText(/^Title/)).toHaveValue('Organic')
      await userEvent.keyboard('{Escape}')
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Edit Certification' })).toBeNull())
      expect(screen.getByRole('dialog', { name: 'Edit Carrier' })).toBeInTheDocument()
      await waitFor(() => expect(document.activeElement).toBe(within(sheet).getByTestId('child-open')))
    })

    it('saves a child with one commit of the child and none of the parent, the parent staying open', async () => {
      const { sheet, onSaved } = form({ instance: stored })
      await userEvent.click(await within(sheet).findByTestId('child-open'))
      const child = await screen.findByRole('dialog', { name: 'Edit Certification' })
      await userEvent.type(within(child).getByLabelText(/^Title/), ' Tea')
      await userEvent.click(within(child).getByRole('button', { name: 'Save' }))
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Edit Certification' })).toBeNull())
      expect(commits()).toEqual([
        [
          '/api/datasets/{resourceId}/commit',
          {
            params: { path: { resourceId: 'urn:cert' } },
            body: {
              reason: undefined,
              changes: [{ action: 'UPDATE', id: 'x1', version: 3, attributes: { title: 'Organic Tea' }, effectiveTime: undefined }],
            },
          },
        ],
      ])
      expect(onSaved).not.toHaveBeenCalled()
      expect(screen.getByRole('dialog', { name: 'Edit Carrier' })).toBeInTheDocument()
    })
  })

  it('closes with Escape and with Cancel', async () => {
    const { onOpenChange } = form()
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(onOpenChange).toHaveBeenLastCalledWith(false)
    onOpenChange.mockClear()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
  })
})
