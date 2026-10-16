import { keepPreviousData, useQuery, type QueryKey } from '@tanstack/react-query'
import { useCallback, useEffect, useState } from 'react'
import { ApiError, formatAmount, formatDateTime, useLanguage, type PaginationState } from '@jabiz/admin'
import { readDictionary, type Page } from './api'
import { CURRENCY } from './ids'

/** A dictionary's labels by code, in the interface language (the code itself until the server answered). */
export function useLabels(urn: string): (code: string | null | undefined) => string {
  const language = useLanguage()
  const items = useQuery({
    queryKey: ['qb-ext', 'dictionary', urn, language],
    queryFn: () => readDictionary(urn),
    staleTime: 5 * 60_000,
  })
  const data = items.data
  return useCallback((code) => (code ? (data?.find((item) => item.code === code)?.label ?? code) : ''), [data])
}

/** An amount of the ledger, in its real currency (administrators see JPY, not the apps' "Kudos"). */
export function money(value: string | null | undefined): string {
  return value == null ? '' : formatAmount(value, { currency: CURRENCY, scale: 0 })
}

/** A time as the administration shows it; empty when there is none. */
export function when(value: string | null | undefined): string {
  return value ? formatDateTime(value) : ''
}

/** Rows per page of the pages' tables. */
export const PAGE_SIZE = 20

/**
 * One page of a list at a time, for the platform's `DataTable`: the previous page stays (inert) while the next loads;
 * the pager knows the total when the server counted it, else whether a full page suggests another.
 */
export function usePaged<Row>(key: QueryKey, read: (offset: number, limit: number) => Promise<Page<Row>>,
  enabled = true) {
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  // Another list (a filter changed) starts at its first page.
  const keyText = JSON.stringify(key)
  const [listed, setListed] = useState(keyText)
  if (listed !== keyText) {
    setListed(keyText)
    if (pagination.pageIndex !== 0) setPagination({ ...pagination, pageIndex: 0 })
  }
  const query = useQuery({
    queryKey: [...key, pagination.pageIndex, pagination.pageSize],
    queryFn: () => read(pagination.pageIndex * pagination.pageSize, pagination.pageSize),
    placeholderData: keepPreviousData,
    enabled,
  })
  return {
    query,
    table: {
      data: query.data?.items ?? [],
      loading: query.isPending,
      stale: query.isPlaceholderData,
      pagination,
      onPaginationChange: setPagination,
      rowCount: query.data?.total,
      hasNextPage: (query.data?.items.length ?? 0) === pagination.pageSize,
    },
  }
}

/** How often the page reads again after a decision until the state follows it, and for how long at most. */
export const POLL_INTERVAL_MS = 1_000
export const POLL_LIMIT_MS = 30_000

/** The states of an approval request after which nothing more is decided on it. */
const FINAL = new Set(['APPROVED', 'REJECTED'])

/**
 * After a decision. When it closed the request (`APPROVED` / `REJECTED`), the subject's state changes once the
 * platform delivers the approval's event (a moment later): the page reads again every {@link POLL_INTERVAL_MS} while
 * the state still shows the review, for at most {@link POLL_LIMIT_MS}, then says it has not updated yet and offers to
 * read again ({@link retry}). When it only approved this level (`PENDING`: another level decides), nothing follows to
 * wait for. The interval is the query's `refetchInterval`.
 */
export function useAfterDecision() {
  const [decision, setDecision] = useState<{ at: number; final: boolean } | null>(null)
  const [expiredAt, setExpiredAt] = useState<number | null>(null)
  useEffect(() => {
    if (!decision?.final) return
    const timer = setTimeout(() => setExpiredAt(decision.at), Math.max(0, decision.at + POLL_LIMIT_MS - Date.now()))
    return () => clearTimeout(timer)
  }, [decision])
  const expired = decision !== null && expiredAt === decision.at
  return {
    decided: decision !== null,
    /** Only this level was approved: another level decides. */
    levelOnly: decision !== null && !decision.final,
    /** Waited {@link POLL_LIMIT_MS} and the state did not follow. */
    expired,
    decide: (status?: string) => setDecision({ at: Date.now(), final: FINAL.has(status ?? '') }),
    retry: () => setDecision({ at: Date.now(), final: true }),
    interval: (stillWaiting: boolean): number | false =>
      decision?.final && stillWaiting && !expired ? POLL_INTERVAL_MS : false,
  }
}

/** Whether a read was refused for want of a permission (403): a part of the page the user may not see. */
export const forbidden = (error: unknown) => error instanceof ApiError && error.status === 403

/** Query keys of the two review queues (pages and counts): a decision on a review page refreshes them. */
export const QUEUE = {
  publications: ['qb-ext', 'queue', 'publications'],
  sponsors: ['qb-ext', 'queue', 'sponsors'],
} as const
