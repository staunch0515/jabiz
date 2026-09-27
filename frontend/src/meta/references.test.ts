import { describe, expect, it } from 'vitest'
import { childListsOf, defaultDatasetOf, referenceSourceOf } from './references'
import type { DatasetEntry, EntityMeta, FieldMeta } from './types'

const base = { immutable: false, required: false, generated: false, systemManaged: false, sensitive: false, processOnly: false, operators: [], rules: [] }

function entity(name: string, fields: FieldMeta[], filters: string[], display?: string): EntityMeta {
  return {
    entity: name,
    label: name,
    primaryKey: 'id',
    display,
    temporal: false,
    publishesChanges: false,
    fields,
    references: [],
    listViews: [{ name: 'default', columns: [], filters, sorts: [] }],
    dictionaries: [],
    unique: [],
    messages: {},
  }
}

function dataset(id: string, entityName: string, isDefault = true): DatasetEntry {
  return { id, entity: entityName, isDefault, listView: 'default', canWrite: true } as DatasetEntry
}

const supplierRef = { ...base, name: 'supplierId', type: 'reference', targetEntity: 'Supplier' } as FieldMeta
const buyerRef = { ...base, name: 'buyerId', type: 'reference', targetEntity: 'Supplier' } as FieldMeta
const supplier = entity('Supplier', [], [], 'supplierName')
const certification = entity('Certification', [supplierRef], ['supplierId'])
const contract = entity('Contract', [supplierRef, buyerRef], ['supplierId'])

describe('references', () => {
  const datasets = [dataset('d:supplier', 'Supplier'), dataset('d:cert', 'Certification'), dataset('d:cert:report', 'Certification', false), dataset('d:contract', 'Contract')]
  const metas = { Supplier: supplier, Certification: certification, Contract: contract }

  it('are looked up in the target default dataset when it declares a display field', () => {
    expect(defaultDatasetOf('Certification', datasets)?.id).toBe('d:cert')
    expect(referenceSourceOf(supplierRef, datasets, metas)?.id).toBe('d:supplier')
    expect(referenceSourceOf(supplierRef, datasets, { Supplier: entity('Supplier', [], []) })).toBeUndefined()
    expect(referenceSourceOf(supplierRef, datasets.slice(1), metas)).toBeUndefined()
    expect(referenceSourceOf({ ...base, name: 'x', type: 'text', multiline: false } as FieldMeta, datasets, metas)).toBeUndefined()
  })

  it('list the children that refer to the parent through a filterable field of a readable default dataset', () => {
    const lists = childListsOf('Supplier', datasets, metas)
    expect(lists.map((l) => `${l.dataset.id}.${l.field}`)).toEqual(['d:cert.supplierId', 'd:contract.supplierId'])
    expect(childListsOf('Supplier', datasets, { Supplier: supplier })).toEqual([])
    expect(childListsOf('Certification', datasets, metas)).toEqual([])
  })
})
