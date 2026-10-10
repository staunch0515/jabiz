import { api, unwrap } from '../api/client'
import type { components } from '../api/schema'

export type Filter = components['schemas']['Filter']
export type Sort = components['schemas']['Sort']

/** One page of a SQL template's result, as `POST /api/queries/{id}` answers it. */
export interface QueryPage<Row> {
  items: Row[]
  offset: number
  limit: number
  /** Present when the request asked for a count. */
  total?: number
}

export interface QueryOptions {
  params?: Record<string, unknown>
  filters?: Filter[]
  sorts?: Sort[]
  offset?: number
  limit?: number
  /** Also count all rows (a second query on the server). */
  count?: boolean
}

/**
 * Runs a SQL template (docs/design/05-sql-template.md) with the template's own permission check, scope and paging.
 * Throws an `ApiError` with the server's violations when the call fails.
 */
export async function runQuery<Row = Record<string, unknown>>(
  queryId: string,
  options: QueryOptions = {},
): Promise<QueryPage<Row>> {
  const answer = await unwrap(
    api.POST('/api/queries/{queryId}', {
      params: { path: { queryId } },
      body: {
        params: options.params,
        filters: options.filters,
        sorts: options.sorts,
        offset: options.offset,
        limit: options.limit,
        count: options.count,
      },
    }),
  )
  return {
    items: (answer.items ?? []) as Row[],
    offset: answer.offset ?? 0,
    limit: answer.limit ?? 0,
    total: answer.total,
  }
}

export interface ProcessOptions {
  /** A version number, or "latest" (the default). */
  version?: number | 'latest'
  /**
   * Makes a retried call run the process at most once (docs/design/06-process.md): pass the same key when the user
   * retries the same submission. A fresh key is used when absent.
   */
  idempotencyKey?: string
}

/**
 * Runs a process with the same validation, permission check and transaction as any other client
 * (docs/design/06-process.md) and returns its output. Throws an `ApiError` with every violation when refused.
 */
export async function runProcess<Output = Record<string, unknown>>(
  name: string,
  input: Record<string, unknown>,
  options: ProcessOptions = {},
): Promise<Output> {
  const answer = await unwrap(
    api.POST('/api/processes/{name}/{version}', {
      params: {
        path: { name, version: String(options.version ?? 'latest') },
        header: { 'Idempotency-Key': options.idempotencyKey ?? crypto.randomUUID() },
      },
      body: input,
    }),
  )
  return (answer as { output?: Output }).output as Output
}
