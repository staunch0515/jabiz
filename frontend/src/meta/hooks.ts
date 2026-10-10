import { useQueries, useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { api, unwrap } from '../api/client'
import type { components } from '@jabiz/client'
import type { QueryEntry } from './reports'
import type { DatasetEntry, DictItem, EntityMeta, Me, MenuItem, ProcessEntry } from './types'

/**
 * Metadata queries. Labels and messages come in the language of the request, so the language is part of every key:
 * switching the language fetches them again.
 */
export function useLanguage(): string {
  return useTranslation().i18n.language
}

export function useMe() {
  const lang = useLanguage()
  return useQuery({
    queryKey: ['auth', 'me', lang],
    queryFn: () => unwrap(api.GET('/api/auth/me')) as Promise<Me>,
    staleTime: 60_000,
  })
}

export function useMenus() {
  const lang = useLanguage()
  return useQuery({
    queryKey: ['auth', 'menus', lang],
    queryFn: () => unwrap(api.GET('/api/auth/menus')) as Promise<MenuItem[]>,
    staleTime: 60_000,
  })
}

/** An open task of the signed-in user (GET /api/tasks/mine). */
export type MyTask = components['schemas']['Task']

/**
 * The open tasks of the signed-in user, their titles in the current language (docs/design/18-numbering-approvals-tasks.md
 * section 5.3). Asked again every minute, so the count in the header follows new work.
 */
export function useMyTasks() {
  const lang = useLanguage()
  return useQuery({
    queryKey: ['tasks', 'mine', lang],
    queryFn: () => unwrap(api.GET('/api/tasks/mine', { params: { query: { limit: 200 } } })) as Promise<
      components['schemas']['MyTasks']
    >,
    refetchInterval: 60_000,
  })
}

export function useDatasets() {
  const lang = useLanguage()
  return useQuery({
    queryKey: ['meta', 'datasets', lang],
    queryFn: () => unwrap(api.GET('/api/meta/datasets')) as Promise<DatasetEntry[]>,
    staleTime: 60_000,
  })
}

export function useDataset(id: string | undefined) {
  const datasets = useDatasets()
  return { ...datasets, data: datasets.data?.find((d) => d.id === id) }
}

export function useEntityMeta(name: string | undefined) {
  const lang = useLanguage()
  return useQuery({
    queryKey: ['meta', 'entity', name, lang],
    enabled: !!name,
    queryFn: async () =>
      (await unwrap(api.GET('/api/meta/entities/{name}', { params: { path: { name: name! } } }))) as unknown as EntityMeta,
    staleTime: 5 * 60_000,
  })
}

export function useProcesses() {
  const lang = useLanguage()
  return useQuery({
    queryKey: ['meta', 'processes', lang],
    queryFn: () => unwrap(api.GET('/api/meta/processes')) as Promise<ProcessEntry[]>,
    staleTime: 60_000,
  })
}

/** The dictionaries behind the code fields of an entity, by URN (labels in the UI language). */
export function useDictionaries(entity: EntityMeta | undefined): Record<string, DictItem[]> {
  const lang = useLanguage()
  const urns = entity?.dictionaries ?? []
  const results = useQueries({
    queries: urns.map((urn) => ({
      queryKey: ['dictionary', urn, lang],
      queryFn: () =>
        unwrap(api.GET('/api/dictionaries/{urn}', { params: { path: { urn } } })) as Promise<DictItem[]>,
      staleTime: 5 * 60_000,
    })),
  })
  const byUrn: Record<string, DictItem[]> = {}
  urns.forEach((urn, i) => {
    const items = results[i]?.data
    if (items) byUrn[urn] = items
  })
  return byUrn
}

/** SQL templates the signed-in user may run, reports among them (GET /api/meta/queries, 19 section 3.2). */
export function useQueryCatalog() {
  const lang = useLanguage()
  return useQuery({
    queryKey: ['meta', 'queries', lang],
    queryFn: () => unwrap(api.GET('/api/meta/queries')) as Promise<QueryEntry[]>,
    staleTime: 60_000,
  })
}

/** The imports the user may run (docs/design/20-imports.md section 6). */
export function useImportCatalog() {
  const lang = useLanguage()
  return useQuery({
    queryKey: ['meta', 'imports', lang],
    queryFn: () => unwrap(api.GET('/api/meta/imports')),
    staleTime: 60_000,
  })
}
