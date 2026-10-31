import { keepPreviousData, useQuery, type QueryKey } from '@tanstack/react-query'
import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError, EXTENSION_NAMESPACE, formatAmount, formatDateTime, useLanguage, type ColumnDef,
  type PaginationState, type SortingState } from '@jabiz/admin'
import { readDictionary, type Page, type SortSpec } from './api'
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

/**
 * An amount on an operator's own pages (Q48e-2): a grouped whole number and the points' name of the interface
 * language ("1,234 Kudos"), never a currency, as the apps show amounts; finance's pages keep {@link money}.
 */
export function usePoints(): (value: string | null | undefined) => string {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return useCallback((value) => (value == null ? ''
    : t('account.points', { value: formatAmount(value, { scale: 0 }) })), [t])
}

/** A multilingual text in the interface language, else English, else Japanese, else any; empty when there is none. */
export function textIn(text: Record<string, string> | null | undefined, language: string): string {
  if (!text) return ''
  return text[language.split('-')[0]] || text.en || text.ja || Object.values(text)[0] || ''
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
 *
 * Q53a: `sorts` names the columns the user may sort by (column id → the template's `list.sorts` field); the table's
 * headers then sort on the server, a new order starting at the first page. Before a choice the template's own
 * `defaultSort` (or the reader's) stands. Pass the columns through `sortable` so that those headers are buttons.
 */
export function usePaged<Row>(key: QueryKey,
  read: (offset: number, limit: number, sorts?: SortSpec[]) => Promise<Page<Row>>, enabled = true,
  sorts?: Readonly<Record<string, string>>) {
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const [sorting, setSorting] = useState<SortingState>([])
  const order: SortSpec[] | undefined = sorts
    ? sorting.filter((sort) => sorts[sort.id]).map((sort) => ({ field: sorts[sort.id], asc: !sort.desc }))
    : undefined
  // Another list (a filter changed) starts at its first page.
  const keyText = JSON.stringify(key)
  const [listed, setListed] = useState(keyText)
  if (listed !== keyText) {
    setListed(keyText)
    if (pagination.pageIndex !== 0) setPagination({ ...pagination, pageIndex: 0 })
  }
  const query = useQuery({
    queryKey: [...key, pagination.pageIndex, pagination.pageSize, order ?? null],
    queryFn: () => read(pagination.pageIndex * pagination.pageSize, pagination.pageSize,
      order && order.length > 0 ? order : undefined),
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
      ...(sorts ? {
        sorting,
        onSortingChange: (next: SortingState) => {
          setSorting(next)
          setPagination((current) => ({ ...current, pageIndex: 0 }))
        },
      } : {}),
    },
    /** The columns with a header to sort by for each of `sorts` (the DataTable sorts columns with an accessor). */
    sortable: (columns: ColumnDef<Row, unknown>[]): ColumnDef<Row, unknown>[] => sortableColumns<Row>(columns, sorts),
  }
}

/** The columns named in `sorts` made sortable (an accessor; the order itself is the server's). */
export function sortableColumns<Row>(columns: ColumnDef<Row, unknown>[],
  sorts: Readonly<Record<string, string>> | undefined): ColumnDef<Row, unknown>[] {
  if (!sorts) return columns
  return columns.map((column) => (column.id && sorts[column.id] && !('accessorFn' in column && column.accessorFn)
    ? { ...column, accessorFn: () => null } as ColumnDef<Row, unknown> : column))
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
