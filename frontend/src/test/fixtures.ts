import type { EntityMeta, FieldMeta, HistoryVersion } from '../meta/types'

const base = { immutable: false, required: false, generated: false, systemManaged: false, sensitive: false, rules: [] }
const ops = (...o: string[]) => ({ operators: o })

/** A Carrier-like export: every kind the adapters map. */
export const carrier: EntityMeta = {
  entity: 'Carrier',
  label: 'Carrier',
  primaryKey: 'carrierId',
  temporal: true,
  allowScheduled: true,
  publishesChanges: false,
  fields: [
    { ...base, ...ops('EQ'), name: 'carrierId', label: 'Carrier ID', type: 'semanticIdentity', urn: 'u', required: true, generated: true, immutable: true },
    { ...base, ...ops('EQ', 'LIKE'), name: 'carrierCode', label: 'Code', type: 'text', maxLength: 10, multiline: false, required: true, immutable: true },
    { ...base, ...ops('EQ', 'LIKE'), name: 'note', label: 'note', type: 'text', multiline: true },
    { ...base, ...ops('EQ', 'IN'), name: 'countryCode', label: 'Country', type: 'code', dictUrn: 'urn:country', allowedValues: [] },
    { ...base, ...ops('EQ', 'BETWEEN', 'GTE', 'LTE'), name: 'creditLimit', label: 'Credit limit', type: 'monetary', currency: 'JPY', scale: 0, required: true },
    { ...base, ...ops('EQ', 'BETWEEN'), name: 'ratio', label: 'Ratio', type: 'numeric', precision: 5, scale: 2 },
    { ...base, ...ops('EQ'), name: 'active', label: 'Active', type: 'bool' },
    { ...base, ...ops(), name: 'secret', label: 'Secret', type: 'text', multiline: false, sensitive: true },
    { ...base, ...ops('EQ'), name: 'cell', label: 'Cell', type: 'custom', kindId: 'geo.h3' },
    { ...base, ...ops('BETWEEN', 'GTE'), name: 'effectStartTime', label: 'effectStartTime', type: 'temporal', role: 'VALID_FROM', systemManaged: true },
    { ...base, ...ops('EQ'), name: 'versionNo', label: 'versionNo', type: 'version', systemManaged: true },
  ] as FieldMeta[],
  references: [],
  listViews: [
    {
      name: 'default',
      columns: ['carrierCode', 'countryCode', 'creditLimit', 'active', 'effectStartTime', 'secret'],
      filters: ['carrierCode', 'countryCode', 'creditLimit', 'active', 'effectStartTime', 'note', 'cell'],
      sorts: ['carrierCode', 'creditLimit'],
      defaultSort: { field: 'carrierCode', asc: true },
    },
  ],
  dictionaries: ['urn:country'],
  unique: [],
  messages: {},
}

export function field(name: string): FieldMeta {
  return carrier.fields.find((f) => f.name === name)!
}

export const countries = {
  'urn:country': [
    { code: 'JP', label: 'Japan', sortOrder: 2, enabled: true },
    { code: 'CN', label: 'China', sortOrder: 1, enabled: true },
    { code: 'XX', label: 'Gone', sortOrder: 3, enabled: false },
  ],
}

/** One history entry, for timeline tests. */
export function version(no: number, attributes: Record<string, unknown>, extra: Partial<HistoryVersion> = {}): HistoryVersion {
  return {
    versionNo: no,
    effectStartTime: `2026-01-0${no}T00:00:00Z`,
    createdTime: `2026-01-0${no}T00:00:00Z`,
    deleted: false,
    action: no === 1 ? 'INSERT' : 'UPDATE',
    baseVersionNo: no === 1 ? null : no - 1,
    changedFields: Object.keys(attributes),
    processSeqId: 10 + no,
    actorId: 'admin',
    processName: 'jabiz.dataset.commit',
    opTime: `2026-01-0${no}T00:00:00Z`,
    reason: null,
    attributes,
    ...extra,
  }
}

