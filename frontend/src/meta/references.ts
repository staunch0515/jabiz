import { useQueries, useQuery } from '@tanstack/react-query'
import { api, unwrap } from '../api/client'
import { useLanguage } from './hooks'
import { listViewOf } from './listQuery'
import type { DatasetEntry, EntityMeta, FieldMeta } from './types'

/**
 * References shown by their display field, and the children listed under a parent
 * (docs/design/16-content-authoring.md sections 2 and 4). Pure functions first, then the queries.
 */

/** The default dataset of an entity among the datasets the caller may read. */
export function defaultDatasetOf(entity: string, datasets: DatasetEntry[]): DatasetEntry | undefined {
  return datasets.find((d) => d.entity === entity && d.isDefault)
}

/**
 * Test id of a dataset's link: the entity's name for its default dataset, the dataset id for the others (an entity
 * may have several, such as a public one next to the back office's; docs/design/03-dataset.md section 2.1).
 */
export function datasetTestId(d: Pick<DatasetEntry, 'id' | 'entity' | 'isDefault'>): string {
  return d.isDefault ? `dataset-${d.entity}` : `dataset-${d.id}`
}

/**
 * Where to look up and label the targets of a reference field: the target's default dataset, when the caller may
 * read it and the target declares a display field; otherwise the field stays a plain key.
 */
export function referenceSourceOf(
  field: FieldMeta,
  datasets: DatasetEntry[],
  metas: Record<string, EntityMeta | undefined>,
): DatasetEntry | undefined {
  if (field.type !== 'reference') return undefined
  const dataset = defaultDatasetOf(field.targetEntity, datasets)
  return dataset && metas[field.targetEntity]?.display ? dataset : undefined
}

export interface ChildList {
  dataset: DatasetEntry
  entity: EntityMeta
  /** The child's reference field that points at the parent. */
  field: string
}

/**
 * The lists shown under an instance of {@code parent}: every readable default dataset whose entity refers to the
 * parent through a field its list view lets callers filter by, one list per such field.
 */
export function childListsOf(
  parent: string,
  datasets: DatasetEntry[],
  metas: Record<string, EntityMeta | undefined>,
): ChildList[] {
  const lists: ChildList[] = []
  for (const dataset of datasets) {
    if (!dataset.isDefault) continue
    const entity = metas[dataset.entity]
    if (!entity) continue
    const filters = new Set(listViewOf(entity, dataset.listView)?.filters ?? [])
    for (const field of entity.fields) {
      if (field.type === 'reference' && field.targetEntity === parent && filters.has(field.name)) {
        lists.push({ dataset, entity, field: field.name })
      }
    }
  }
  return lists
}

/** Entity exports by name, fetched together (and cached like useEntityMeta). */
export function useEntityMetas(names: string[]): Record<string, EntityMeta | undefined> {
  const lang = useLanguage()
  const unique = [...new Set(names)].sort()
  const results = useQueries({
    queries: unique.map((name) => ({
      queryKey: ['meta', 'entity', name, lang],
      queryFn: async () =>
        (await unwrap(api.GET('/api/meta/entities/{name}', { params: { path: { name } } }))) as unknown as EntityMeta,
      staleTime: 5 * 60_000,
    })),
  })
  const byName: Record<string, EntityMeta | undefined> = {}
  unique.forEach((name, i) => {
    byName[name] = results[i]?.data
  })
  return byName
}

export interface LookupItem {
  id: string
  label: unknown
}

/** Matches of a reference picker. */
export function useLookup(datasetId: string | undefined, q: string) {
  const lang = useLanguage()
  return useQuery({
    queryKey: ['lookup', datasetId, q, lang],
    enabled: !!datasetId,
    queryFn: async () =>
      (
        await unwrap(
          api.GET('/api/datasets/{resourceId}/lookup', { params: { path: { resourceId: datasetId! }, query: { q } } }),
        )
      ).map((item) => ({ id: String(item.id), label: item.label }) as LookupItem),
    staleTime: 30_000,
  })
}

/** Display texts of the given keys through one dataset (at most 200, as the server allows). */
export function useLabels(datasetId: string | undefined, ids: string[]) {
  const keys = [...new Set(ids)].sort().slice(0, 200)
  return useQuery({
    queryKey: ['labels', datasetId, keys],
    enabled: !!datasetId && keys.length > 0,
    queryFn: async () =>
      (await unwrap(
        api.POST('/api/datasets/{resourceId}/labels', { params: { path: { resourceId: datasetId! } }, body: { ids: keys } }),
      )) as Record<string, unknown>,
    staleTime: 30_000,
  })
}

/**
 * Labels of the reference columns of the loaded rows, by field then key: one labels request per reference field whose
 * target can be labelled.
 */
export function useReferenceLabels(
  entity: EntityMeta | undefined,
  rows: Record<string, unknown>[],
  datasets: DatasetEntry[],
  metas: Record<string, EntityMeta | undefined>,
): Record<string, Record<string, unknown>> {
  const fields = (entity?.fields ?? []).filter((f) => referenceSourceOf(f, datasets, metas))
  const results = useQueries({
    queries: fields.map((field) => {
      const source = referenceSourceOf(field, datasets, metas)!
      const keys = [
        ...new Set(rows.map((r) => r[field.name]).filter((v) => v !== null && v !== undefined).map(String)),
      ]
        .sort()
        .slice(0, 200)
      return {
        queryKey: ['labels', source.id, keys],
        enabled: keys.length > 0,
        queryFn: async () =>
          (await unwrap(
            api.POST('/api/datasets/{resourceId}/labels', {
              params: { path: { resourceId: source.id } },
              body: { ids: keys },
            }),
          )) as Record<string, unknown>,
        staleTime: 30_000,
      }
    }),
  })
  const byField: Record<string, Record<string, unknown>> = {}
  fields.forEach((field, i) => {
    byField[field.name] = results[i]?.data ?? {}
  })
  return byField
}
