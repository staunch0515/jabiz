import type { PaginationState, SortingState } from '@jabiz/ui'
import { useMemo, useState } from 'react'

/** A column's value to sort a row by; text sorts by the language's collation, numbers and times by value. */
export type SortValue = string | number | boolean | null | undefined

export interface LocalListOptions<Row> {
  /** The texts a search looks in; a row matches when one of them contains every word of the query. */
  text: (row: Row) => (string | null | undefined)[]
  /** Per sortable column (its id): the value to sort by. */
  sortBy?: Record<string, (row: Row) => SortValue>
  /** The order before the user picks one. */
  defaultSort?: SortingState
  pageSize?: number
  /** The interface language, for the collation of texts. */
  locale?: string
}

/** Lower case and without diacritics for matching: "Erasure" matches "erasure", "É" matches "e". */
function fold(text: string): string {
  return text.normalize('NFKD').replace(/\p{M}/gu, '').toLocaleLowerCase()
}

/** Whether a row's texts hold every word of the query (none: every row). */
export function matches(texts: (string | null | undefined)[], query: string): boolean {
  const words = fold(query).split(/\s+/).filter(Boolean)
  if (words.length === 0) return true
  const haystack = fold(texts.filter((text): text is string => !!text).join('\n'))
  return words.every((word) => haystack.includes(word))
}

/** Compares two sort values: empty last whatever the direction is left to the caller; texts by the collator. */
export function compareValues(a: SortValue, b: SortValue, collator: Intl.Collator): number {
  const emptyA = a === null || a === undefined || a === ''
  const emptyB = b === null || b === undefined || b === ''
  if (emptyA || emptyB) return emptyA === emptyB ? 0 : emptyA ? 1 : -1
  if (typeof a === 'number' && typeof b === 'number') return a - b
  if (typeof a === 'boolean' && typeof b === 'boolean') return Number(a) - Number(b)
  return collator.compare(String(a), String(b))
}

/**
 * A list held in full in the browser (the catalogs, the latest runs; phase 17w): searched by a query, sorted by the
 * column the user picks, then paged. The DataTable's sorting is manual, so the order is made here; empty values go
 * last either way. The props for the DataTable: `sorting`, `onSortingChange`, the paging (only with more than one
 * page) and `data`.
 */
export function useLocalList<Row>(rows: Row[] | undefined, options: LocalListOptions<Row>) {
  const pageSize = options.pageSize ?? 50
  const [query, setQueryState] = useState('')
  const [sorting, setSortingState] = useState<SortingState>(options.defaultSort ?? [])
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize })
  const { text, sortBy, locale } = options

  const shown = useMemo(() => {
    const all = (rows ?? []).filter((row) => matches(text(row), query))
    const sort = sorting[0]
    const value = sort ? sortBy?.[sort.id] : undefined
    if (!sort || !value) return all
    const collator = new Intl.Collator(locale, { numeric: true, sensitivity: 'base' })
    return all
      .map((row, index) => ({ row, index, key: value(row) }))
      .sort((x, y) => {
        const emptyX = x.key === null || x.key === undefined || x.key === ''
        const emptyY = y.key === null || y.key === undefined || y.key === ''
        // Empty values last, in both directions; ties keep the server's order.
        if (emptyX || emptyY) return emptyX === emptyY ? x.index - y.index : emptyX ? 1 : -1
        const order = compareValues(x.key, y.key, collator)
        return (sort.desc ? -order : order) || x.index - y.index
      })
      .map(({ row }) => row)
    // `text` and `sortBy` are read anew each render by design; the rows, query and sorting decide the result.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, query, sorting, locale])

  const pages = Math.max(1, Math.ceil(shown.length / pagination.pageSize))
  // The list may have shrunk under the current page (a narrower search, fewer rows).
  const pageIndex = Math.min(pagination.pageIndex, pages - 1)
  const start = pageIndex * pagination.pageSize
  return {
    query,
    /** A new query starts at the first page. */
    setQuery: (next: string) => {
      setQueryState(next)
      setPagination((p) => ({ ...p, pageIndex: 0 }))
    },
    /** How many rows match (before paging), and how many there are. */
    matched: shown.length,
    total: rows?.length ?? 0,
    table: {
      data: shown.slice(start, start + pagination.pageSize),
      sorting,
      onSortingChange: (next: SortingState) => {
        setSortingState(next)
        setPagination((p) => ({ ...p, pageIndex: 0 }))
      },
      ...(shown.length > pagination.pageSize
        ? {
            pagination: { ...pagination, pageIndex },
            onPaginationChange: setPagination,
            rowCount: shown.length,
            pageSizeOptions: [pageSize],
          }
        : {}),
    },
  }
}
