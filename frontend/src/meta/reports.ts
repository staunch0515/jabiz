import type { components } from '@jabiz/client'
import type { EntityMeta, FieldMeta } from './types'

/**
 * The template catalog of GET /api/meta/queries (docs/design/19-reports.md section 3.2) as the reports pages use it.
 * A report is a template that declares `report`; its result columns are turned into field metadata so that the list
 * helpers of the dataset pages (search inputs, filters, sorts) serve report tables too.
 */
export type QueryEntry = components['schemas']['QueryEntry']

/** The reports among the templates, grouped by the first part of their id, groups and reports by title. */
export function reportGroups(entries: QueryEntry[]): { group: string; reports: QueryEntry[] }[] {
  const groups = new Map<string, QueryEntry[]>()
  for (const entry of entries) {
    if (!entry.report || !entry.id) continue
    const group = entry.id.split('.')[0]
    groups.set(group, [...(groups.get(group) ?? []), entry])
  }
  return [...groups.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([group, reports]) => ({
      group,
      reports: [...reports].sort((a, b) => (a.title ?? '').localeCompare(b.title ?? '')),
    }))
}

/** A result column as field metadata. */
export function resultField(result: components['schemas']['ResultEntry']): FieldMeta {
  const kind = (result.kind ?? { type: 'none' }) as { type: string } & Record<string, unknown>
  return {
    ...kind,
    name: result.name ?? '',
    label: result.label,
    immutable: true,
    required: false,
    generated: false,
    systemManaged: false,
    sensitive: false,
    processOnly: false,
    operators: result.operators ?? [],
    rules: [],
  } as FieldMeta
}

/** The report's columns and whitelist as the entity metadata the list helpers take. */
export function reportEntity(entry: QueryEntry): EntityMeta {
  const fields = (entry.results ?? []).map(resultField)
  return {
    entity: entry.id ?? '',
    label: entry.title ?? entry.id ?? '',
    primaryKey: '',
    temporal: false,
    publishesChanges: false,
    fields,
    references: [],
    listViews: [
      {
        name: 'default',
        columns: fields.map((f) => f.name),
        filters: entry.filters ?? [],
        sorts: entry.sorts ?? [],
        defaultSort: entry.defaultSort?.field
          ? { field: entry.defaultSort.field, asc: entry.defaultSort.asc ?? true }
          : undefined,
      },
    ],
    dictionaries: [],
    unique: [],
    messages: {},
  }
}

/** Whether a run can be given a point in time, and by which means (19 section 2.1). */
export function pointInTimeOf(entry: QueryEntry): 'request' | 'parameters' | 'none' {
  if (entry.timeSlice && (entry.timeSlice.asOf || entry.timeSlice.knownAt)) return 'parameters'
  return entry.timeTravel ? 'request' : 'none'
}
