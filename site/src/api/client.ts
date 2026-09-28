import type { PublicQueryId, QueryFilter, QueryPage, QueryParams, QuerySort } from './public-queries'

/** An outer filter of a public template (docs/design/15-public-access.md section 5). */
export interface QueryFilterSpec<Q extends PublicQueryId> {
  field: QueryFilter<Q>
  op: 'eq' | 'in'
  value: string | readonly string[]
}

export interface QueryOptions<Q extends PublicQueryId> {
  filters?: readonly QueryFilterSpec<Q>[]
  sort?: { field: QuerySort<Q>; direction: 'asc' | 'desc' }
  limit?: number
  offset?: number
}

/** A failed public request; `status` 404 means the template, or the row asked for, is not public. */
export class PublicApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

/** The query string of a public template: parameters as p.<name>, lists repeated, then filters, sort and paging. */
export function queryString<Q extends PublicQueryId>(params: QueryParams<Q>, options: QueryOptions<Q> = {}): string {
  const search = new URLSearchParams()
  for (const [name, value] of Object.entries(params as Record<string, unknown>)) {
    if (value === undefined || value === null) continue
    if (Array.isArray(value)) {
      value.forEach((v) => search.append(`p.${name}`, String(v)))
    } else {
      search.append(`p.${name}`, String(value))
    }
  }
  for (const filter of options.filters ?? []) {
    const value = Array.isArray(filter.value) ? filter.value.join(',') : String(filter.value)
    search.append('filter', `${String(filter.field)}:${filter.op}:${value}`)
  }
  if (options.sort) search.append('sort', `${String(options.sort.field)}:${options.sort.direction}`)
  if (options.limit !== undefined) search.append('limit', String(options.limit))
  if (options.offset !== undefined) search.append('offset', String(options.offset))
  // The site never needs the total count separately from the rows it shows.
  search.append('count', 'false')
  return search.toString()
}

/** Runs a public template anonymously: no Authorization header, no cookies (docs/culture/00-design.md section 9.4). */
export async function fetchPublic<Q extends PublicQueryId>(
  id: Q,
  params: QueryParams<Q>,
  options: QueryOptions<Q> = {},
  locale = 'en',
  signal?: AbortSignal,
): Promise<QueryPage<Q>> {
  const response = await fetch(`/api/public/queries/${encodeURIComponent(id)}?${queryString(params, options)}`, {
    headers: { Accept: 'application/json', 'Accept-Language': locale },
    credentials: 'omit',
    signal,
  })
  if (!response.ok) {
    throw new PublicApiError(response.status, `${id}: ${response.status}`)
  }
  return (await response.json()) as QueryPage<Q>
}

/** The address of a public file or one of its image variants (docs/design/15-public-access.md section 4). */
export function fileUrl(fileId: string, variant?: string): string {
  return `/api/public/files/${encodeURIComponent(fileId)}${variant ? `/${encodeURIComponent(variant)}` : ''}`
}
