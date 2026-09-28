import { useQuery } from '@tanstack/react-query'
import { useLocale } from '../i18n/locale'
import { fetchPublic, PublicApiError, type QueryOptions } from './client'
import type { PublicQueryId, QueryParams, QueryRow } from './public-queries'

/**
 * The rows of a public template; cached by TanStack Query for as long as the server lets browsers cache it. Not
 * `enabled`: nothing is asked (e.g. search words that are too short).
 */
export function usePublicQuery<Q extends PublicQueryId>(
  id: Q,
  params: QueryParams<Q>,
  options: QueryOptions<Q> = {},
  enabled = true,
) {
  const locale = useLocale()
  return useQuery({
    enabled,
    queryKey: ['public', id, params, options, locale],
    queryFn: ({ signal }) => fetchPublic(id, params, options, locale, signal),
    select: (page) => page.items as QueryRow<Q>[],
  })
}

/** The single row of a template looked up by slug; `null` once loaded when there is none (a page that is not public). */
export function usePublicRow<Q extends PublicQueryId>(id: Q, params: QueryParams<Q>) {
  const result = usePublicQuery(id, params, { limit: 1 })
  return { ...result, row: result.data ? (result.data[0] ?? null) : undefined }
}

export function isNotFound(error: unknown): boolean {
  return error instanceof PublicApiError && error.status === 404
}
