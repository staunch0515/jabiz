import type { components } from '../api/schema'

/**
 * The entity export of GET /api/meta/entities/{name} (docs/design/02-metamodel.md section 8). The server returns it
 * as a free-form map, so its shape is declared here; the backend's MetaModelExportTest pins the same keys.
 */
export type RuleKind = 'RANGE' | 'SCALE' | 'LENGTH' | 'PATTERN' | 'NOT_FUTURE' | 'REQUIRED'

export interface RuleSpec {
  code: string
  kind: RuleKind
  params: Record<string, unknown>
}

interface FieldBase {
  name: string
  label?: string
  immutable: boolean
  required: boolean
  generated: boolean
  systemManaged: boolean
  sensitive: boolean
  operators: string[]
  rules: RuleSpec[]
}

export type FieldMeta = FieldBase &
  (
    | { type: 'semanticIdentity'; urn: string }
    | { type: 'monetary'; currency: string; scale: number }
    | { type: 'temporal'; role: 'EVENT_TIME' | 'SYSTEM_RECORDED' | 'VALID_FROM' | 'VALID_TO' }
    | { type: 'code'; dictUrn: string; allowedValues: string[] }
    | { type: 'version' }
    | { type: 'text'; maxLength?: number; multiline: boolean }
    | { type: 'numeric'; precision: number; scale: number }
    | { type: 'bool' }
    | { type: 'reference'; targetEntity: string }
    | { type: 'custom'; kindId: string; [param: string]: unknown }
    | { type: 'none' }
  )

export interface ListViewMeta {
  name: string
  columns: string[]
  filters: string[]
  sorts: string[]
  defaultSort?: { field: string; asc: boolean }
}

export interface EntityMeta {
  entity: string
  label: string
  primaryKey: string
  temporal: boolean
  allowScheduled?: boolean
  publishesChanges: boolean
  stateField?: string
  fields: FieldMeta[]
  references: { field: string; targetEntity: string }[]
  listViews: ListViewMeta[]
  dictionaries: string[]
  unique: { name: string; fields: string[] }[]
  /** Message templates by rule code, in the language of the request. */
  messages: Record<string, string>
}

export type DatasetEntry = Required<components['schemas']['DatasetEntry']>
export type ProcessEntry = Required<components['schemas']['ProcessEntry']>
export type EntityInstance = components['schemas']['EntityInstance']
export type DictItem = Required<components['schemas']['DictItem']>
export type MenuItem = components['schemas']['MenuItem']
export type Me = components['schemas']['Me']

/** One reported problem, the same shape as the server's ProblemDetail violations. */
export interface Violation {
  field: string | null
  ruleCode: string
  message: string
  params?: Record<string, unknown>
}

/** A version in the history of a temporal entity (docs/design/03-dataset.md section 3). */
export interface HistoryVersion {
  versionNo: number
  effectStartTime: string
  createdTime: string
  deleted: boolean
  action: 'INSERT' | 'UPDATE' | 'DELETE' | 'REBASE' | 'REVERT' | 'CANCEL'
  baseVersionNo: number | null
  changedFields: string[]
  processSeqId: number
  actorId: string
  processName: string
  opTime: string
  reason: string | null
  attributes: Record<string, unknown>
}
