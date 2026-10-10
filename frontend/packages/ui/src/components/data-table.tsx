import { Fragment, useEffect, useRef, useState, type HTMLAttributes, type ReactNode } from 'react'
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
  type ColumnFiltersState,
  type ExpandedState,
  type OnChangeFn,
  type PaginationState,
  type SortingState,
  type Updater,
} from '@tanstack/react-table'
import { ArrowDownIcon, ArrowUpDownIcon, ArrowUpIcon, ChevronLeftIcon, ChevronRightIcon } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import { UI_NAMESPACE } from '../i18n'
import { cn, UI_SCOPE } from '../lib/utils'
import { Button } from './ui/button'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from './ui/select'
import { Skeleton } from './ui/skeleton'
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from './ui/table'

export type { ColumnDef, ColumnFiltersState, PaginationState, SortingState }

declare module '@tanstack/react-table' {
  // Presentation of a column in a DataTable.
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  interface ColumnMeta<TData, TValue> {
    /** Classes of the column's header and cells (width, alignment). */
    className?: string
    /** Kept at the right edge while the table scrolls sideways (the row actions). */
    pinned?: 'right'
  }
}

/** The classes of a column's header and cells: its own, and those keeping a pinned column at the right edge. */
function columnClass(meta: { className?: string; pinned?: 'right' } | undefined, header = false) {
  return cn(
    meta?.pinned === 'right' &&
      cn('sticky right-0 z-10 border-l shadow-[-4px_0_4px_-4px_var(--border)]', header ? 'bg-background' : 'bg-background group-hover/row:bg-muted'),
    meta?.className,
  )
}

export interface DataTableProps<Row> {
  columns: ColumnDef<Row, unknown>[]
  /** The rows of the current page, as the server returned them: the table neither sorts, filters nor pages. */
  data: Row[]
  /** The table's accessible name. */
  label: string
  getRowId?: (row: Row, index: number) => string
  loading?: boolean
  /** Shown when there are no rows; the "no data" text when absent. */
  empty?: ReactNode
  /** The current page; the pager shows when it and {@link onPaginationChange} are given. */
  pagination?: PaginationState
  onPaginationChange?: (pagination: PaginationState) => void
  /** All rows of all pages, as the server counted them; unknown when the caller did not ask for a count. */
  rowCount?: number
  /**
   * Whether a page follows the current one, when there is no {@link rowCount}. Absent: assumed while the current
   * page is full.
   */
  hasNextPage?: boolean
  pageSizeOptions?: number[]
  /** Sorting (columns with `enableSorting: false` are not sortable); applied by the server. */
  sorting?: SortingState
  onSortingChange?: (sorting: SortingState) => void
  /** Filters set through the columns (`column.setFilterValue` in a header); applied by the server. */
  columnFilters?: ColumnFiltersState
  onColumnFiltersChange?: (filters: ColumnFiltersState) => void
  /** The content under an expanded row; rows get an expand button when given. */
  renderExpanded?: (row: Row) => ReactNode
  /** Further attributes of a row's <tr> (test ids, a selected state). */
  rowProps?: (row: Row) => HTMLAttributes<HTMLTableRowElement> & Record<`data-${string}`, string | undefined>
  className?: string
}

function apply<T>(updater: Updater<T>, current: T): T {
  return typeof updater === 'function' ? (updater as (old: T) => T)(current) : updater
}

/**
 * Whether an element scrolls sideways (its content is wider than it): a region that scrolls must be reachable with
 * the keyboard (WCAG 2.1.1, axe `scrollable-region-focusable`), one that does not should not be an extra tab stop.
 */
function useScrollsSideways<T extends HTMLElement>() {
  const ref = useRef<T>(null)
  const [scrolls, setScrolls] = useState(false)
  useEffect(() => {
    const element = ref.current
    if (!element) return
    const measure = () => setScrolls(element.scrollWidth > element.clientWidth)
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    if (element.firstElementChild) observer.observe(element.firstElementChild)
    return () => observer.disconnect()
  }, [])
  return [ref, scrolls] as const
}

const NO_SORTING: SortingState = []
const NO_FILTERS: ColumnFiltersState = []

/**
 * A table whose paging, sorting and filtering the caller does on the server (TanStack Table, manual mode): the
 * table only reports what the user asked for. Sortable headers are buttons and the header cell carries `aria-sort`;
 * expandable rows have a named button ("Expand row" / "Collapse row") with `aria-expanded`.
 */
export function DataTable<Row>({
  columns,
  data,
  label,
  getRowId,
  loading = false,
  empty,
  pagination,
  onPaginationChange,
  rowCount,
  hasNextPage,
  pageSizeOptions = [10, 20, 50, 100],
  sorting = NO_SORTING,
  onSortingChange,
  columnFilters = NO_FILTERS,
  onColumnFiltersChange,
  renderExpanded,
  rowProps,
  className,
}: DataTableProps<Row>) {
  const { t } = useTranslation(UI_NAMESPACE)
  const [scrollRef, scrolls] = useScrollsSideways<HTMLDivElement>()
  const [expanded, setExpanded] = useState<ExpandedState>({})
  // Other rows (another page, another order) close what was open: without getRowId the keys are row indexes and
  // would open the wrong rows. Reset while rendering, React's pattern for state derived from a prop.
  const [expandedRows, setExpandedRows] = useState(data)
  if (expandedRows !== data) {
    setExpandedRows(data)
    setExpanded({})
  }
  const paged = pagination !== undefined && onPaginationChange !== undefined

  const handleSorting: OnChangeFn<SortingState> = (updater) => onSortingChange?.(apply(updater, sorting))
  const handleFilters: OnChangeFn<ColumnFiltersState> = (updater) =>
    onColumnFiltersChange?.(apply(updater, columnFilters))
  const handlePagination: OnChangeFn<PaginationState> = (updater) => {
    if (pagination) onPaginationChange?.(apply(updater, pagination))
  }

  // eslint-disable-next-line react-hooks/incompatible-library -- the table instance is used in this render only
  const table = useReactTable<Row>({
    data,
    columns,
    getRowId,
    getCoreRowModel: getCoreRowModel(),
    manualPagination: true,
    manualSorting: true,
    manualFiltering: true,
    enableSorting: onSortingChange !== undefined,
    enableMultiSort: false,
    rowCount: rowCount ?? data.length,
    getRowCanExpand: () => renderExpanded !== undefined,
    state: {
      sorting,
      columnFilters,
      expanded,
      ...(pagination ? { pagination } : {}),
    },
    onSortingChange: handleSorting,
    onColumnFiltersChange: handleFilters,
    onPaginationChange: handlePagination,
    onExpandedChange: setExpanded,
  })

  const columnCount = columns.length + (renderExpanded ? 1 : 0)
  const pageCount =
    paged && rowCount !== undefined ? Math.max(1, Math.ceil(rowCount / pagination.pageSize)) : undefined
  const hasNext = !paged
    ? false
    : pageCount !== undefined
      ? pagination.pageIndex + 1 < pageCount
      : (hasNextPage ?? data.length >= pagination.pageSize)

  return (
    <div data-slot="data-table" className={cn(UI_SCOPE, 'flex flex-col gap-3', className)}>
      <div className="rounded-md border">
        {/* shadcn's Table with its container here: a container that scrolls is a named, focusable region. */}
        <div
          ref={scrollRef}
          data-slot="table-container"
          className="focus-visible:ring-ring/50 relative w-full overflow-x-auto rounded-md outline-none focus-visible:ring-[3px]"
          {...(scrolls ? { role: 'region', 'aria-label': label, tabIndex: 0 } : {})}
        >
          <table
            data-slot="table"
            className={cn(UI_SCOPE, 'w-full caption-bottom text-sm')}
            aria-label={label}
            aria-busy={loading || undefined}
          >
            <TableHeader>
              {table.getHeaderGroups().map((group) => (
                <TableRow key={group.id}>
                  {renderExpanded && (
                    <TableHead className="w-10">
                      <span className="sr-only">{t('dataTable.expandRow')}</span>
                    </TableHead>
                  )}
                  {group.headers.map((header) => {
                    const sortable = header.column.getCanSort()
                    const direction = header.column.getIsSorted()
                    const content = header.isPlaceholder
                      ? null
                      : flexRender(header.column.columnDef.header, header.getContext())
                    return (
                      <TableHead
                        key={header.id}
                        colSpan={header.colSpan}
                        className={columnClass(header.column.columnDef.meta, true)}
                        aria-sort={
                          !sortable ? undefined : direction === 'asc' ? 'ascending' : direction === 'desc' ? 'descending' : 'none'
                        }
                      >
                        {sortable ? (
                          <Button
                            variant="ghost"
                            size="sm"
                            className="-ml-3 h-8"
                            onClick={header.column.getToggleSortingHandler()}
                            title={
                              direction === 'asc'
                                ? t('dataTable.sortDescending')
                                : direction === 'desc'
                                  ? t('dataTable.clearSort')
                                  : t('dataTable.sortAscending')
                            }
                          >
                            {content}
                            {direction === 'asc' ? (
                              <ArrowUpIcon aria-hidden />
                            ) : direction === 'desc' ? (
                              <ArrowDownIcon aria-hidden />
                            ) : (
                              <ArrowUpDownIcon aria-hidden className="opacity-60" />
                            )}
                          </Button>
                        ) : (
                          content
                        )}
                      </TableHead>
                    )
                  })}
                </TableRow>
              ))}
            </TableHeader>
            <TableBody>
              {loading && data.length === 0 ? (
                Array.from({ length: 3 }, (_, i) => (
                  <TableRow key={`loading-${i}`}>
                    <TableCell colSpan={columnCount}>
                      <Skeleton className="h-5 w-full" />
                      <span className="sr-only">{i === 0 ? t('loading') : ''}</span>
                    </TableCell>
                  </TableRow>
                ))
              ) : table.getRowModel().rows.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={columnCount} className="text-muted-foreground h-24 text-center">
                    {empty ?? t('dataTable.empty')}
                  </TableCell>
                </TableRow>
              ) : (
                table.getRowModel().rows.map((row) => {
                  const open = row.getIsExpanded()
                  return (
                    <Fragment key={row.id}>
                      <TableRow
                        data-slot="data-table-row"
                        data-state={open ? 'expanded' : undefined}
                        className="group/row"
                        {...rowProps?.(row.original)}
                      >
                        {renderExpanded && (
                          <TableCell className="w-10">
                            <Button
                              variant="ghost"
                              size="icon-sm"
                              aria-expanded={open}
                              aria-label={open ? t('dataTable.collapseRow') : t('dataTable.expandRow')}
                              onClick={row.getToggleExpandedHandler()}
                            >
                              <ChevronRightIcon aria-hidden className={cn(UI_SCOPE, 'transition-transform', open && 'rotate-90')} />
                            </Button>
                          </TableCell>
                        )}
                        {row.getVisibleCells().map((cell) => (
                          <TableCell key={cell.id} className={columnClass(cell.column.columnDef.meta)}>
                            {flexRender(cell.column.columnDef.cell, cell.getContext())}
                          </TableCell>
                        ))}
                      </TableRow>
                      {open && renderExpanded && (
                        <TableRow className="hover:bg-transparent">
                          <TableCell colSpan={columnCount} className="bg-muted/40 whitespace-normal">
                            {renderExpanded(row.original)}
                          </TableCell>
                        </TableRow>
                      )}
                    </Fragment>
                  )
                })
              )}
            </TableBody>
          </table>
        </div>
      </div>
      {paged && (
        <div className="flex flex-wrap items-center justify-end gap-4 text-sm">
          {rowCount !== undefined && <span>{t('dataTable.total', { count: rowCount })}</span>}
          <div className="flex items-center gap-2">
            <Select
              value={String(pagination.pageSize)}
              onValueChange={(value) => onPaginationChange({ pageIndex: 0, pageSize: Number(value) })}
            >
              <SelectTrigger size="sm" aria-label={t('dataTable.rowsPerPage')}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {pageSizeOptions.map((size) => (
                  <SelectItem key={size} value={String(size)}>
                    {size}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <span aria-live="polite">{pageCount !== undefined
              ? t('pagination.page', { page: pagination.pageIndex + 1, pages: pageCount })
              : t('pagination.pageOnly', { page: pagination.pageIndex + 1 })}</span>
          <div className="flex items-center gap-1">
            <Button
              variant="outline"
              size="icon-sm"
              aria-label={t('pagination.previous')}
              disabled={pagination.pageIndex === 0}
              onClick={() => onPaginationChange({ ...pagination, pageIndex: pagination.pageIndex - 1 })}
            >
              <ChevronLeftIcon aria-hidden />
            </Button>
            <Button
              variant="outline"
              size="icon-sm"
              aria-label={t('pagination.next')}
              disabled={!hasNext}
              onClick={() => onPaginationChange({ ...pagination, pageIndex: pagination.pageIndex + 1 })}
            >
              <ChevronRightIcon aria-hidden />
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}
