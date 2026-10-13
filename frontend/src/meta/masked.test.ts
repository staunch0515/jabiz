import { isValidElement, type ReactElement } from 'react'
import { describe, expect, it } from 'vitest'
import i18n from '../i18n'
import { mayCompare, renderCell, toRow } from './cells'
import { dataColumns, filterFields } from './dataColumns'
import { formFieldsOf } from './entityForm'
import type { EntityMeta, FieldMeta, ListViewMeta } from './types'

const base = { immutable: false, required: false, generated: false, systemManaged: false, sensitive: false, processOnly: false, operators: ['EQ'], rules: [] }
const account = {
  ...base,
  name: 'bankAccount',
  type: 'text',
  multiline: false,
  masked: { permission: 'carrier.bank', style: 'LAST4' },
} as FieldMeta
const name = { ...base, name: 'name', type: 'text', multiline: false } as FieldMeta
const supplier: EntityMeta = {
  entity: 'Supplier',
  label: 'Supplier',
  primaryKey: 'id',
  temporal: false,
  allowScheduled: false,
  publishesChanges: false,
  fields: [{ ...base, name: 'id', type: 'semanticIdentity', urn: 'urn:s', generated: true } as FieldMeta, name, account],
  listViews: {},
} as unknown as EntityMeta
const view: ListViewMeta = { name: 'default', columns: ['name', 'bankAccount'], filters: ['name', 'bankAccount'], sorts: ['name', 'bankAccount'] }
const holder = (permission: string) => permission === 'carrier.bank'
const nobody = () => false

describe('masked fields (docs/design/10-security.md section 13.1)', () => {
  it('are filtered and sorted by holders of their permission only', () => {
    expect(mayCompare(name, nobody)).toBe(true)
    expect(mayCompare(account, nobody)).toBe(false)
    expect(mayCompare(account, holder)).toBe(true)
    expect(mayCompare(account)).toBe(false)

    const sortable = (can: (p: string) => boolean) =>
      Object.fromEntries(dataColumns(supplier, view, {}, i18n.t, 'en', { can }).map((c) => [c.id, c.enableSorting]))
    expect(sortable(nobody)).toEqual({ name: true, bankAccount: false })
    expect(sortable(holder)).toEqual({ name: true, bankAccount: true })
    expect(filterFields(supplier, view, {}, i18n.t, nobody).map((f) => f.name)).toEqual(['name'])
    expect(filterFields(supplier, view, {}, i18n.t, holder).map((f) => f.name)).toEqual(['name', 'bankAccount'])
  })

  it('show the masked form, with a button to show the value for holders in a dataset', () => {
    const row = toRow({ id: 's1', version: 1, attributes: { bankAccount: '****4931' } })
    expect(renderCell(account, '****4931', {}, i18n.t, 'en', { can: nobody, datasetId: 'urn:d', row })).toBe('****4931')
    // Without a dataset (a past point in time) there is nothing to ask.
    expect(renderCell(account, '****4931', {}, i18n.t, 'en', { can: holder, row })).toBe('****4931')
    const cell = renderCell(account, '****4931', {}, i18n.t, 'en', { can: holder, datasetId: 'urn:d', row })
    expect(isValidElement(cell)).toBe(true)
    expect((cell as ReactElement<{ datasetId: string; id: unknown; field: string; masked: string }>).props)
      .toEqual({ datasetId: 'urn:d', id: 's1', field: 'bankAccount', masked: '****4931' })
    expect(renderCell(account, null, {}, i18n.t, 'en', { can: holder, datasetId: 'urn:d', row })).toBe('—')
  })

  it('are read-only in the form without their permission', () => {
    const disabled = (can?: (p: string) => boolean) =>
      formFieldsOf(supplier, 'edit', {}, can).find((f) => f.field.name === 'bankAccount')?.disabled
    expect(disabled()).toBe(true)
    expect(disabled(nobody)).toBe(true)
    expect(disabled(holder)).toBe(false)
  })
})
