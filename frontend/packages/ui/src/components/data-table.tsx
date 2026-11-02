import { Fragment, useEffect, useRef, useState, type HTMLAttributes, type ReactNode, type Ref } from 'react'
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
import { ArrowDownIcon, ArrowUpDownIcon, ArrowUpIcon, ChevronLeftIcon, ChevronRightIcon, LayoutGridIcon, TableIcon } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import { useIsMobile } from '../hooks/use-mobile'
import { UI_NAMESPACE } from '../i18n'
import { cn } from '../lib/utils'
import { DescriptionList, type DescriptionItem } from './description-list'
import { SegmentedControl } from './segmented-control'
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
    /**
     * The column's place on a card (17zc): the card's title, its subtitle, a badge beside the title, the buttons at
     * the card's foot (without one, a `pinned` column), or not shown (an ID). Columns without a place are listed as
     * label and value. A table whose columns have no places has no card view.
     */
    card?: 'title' | 'subtitle' | 'badge' | 'actions' | 'hidden'
  }
}

export type DataTableView = 'table' | 'cards'

const VIEW_STORAGE_PREFIX = 'jabiz.data-table.view.'

function storedView(key: string | undefined): DataTableView | null {
  if (!key) return null
  try {
    const value = window.localStorage.getItem(VIEW_STORAGE_PREFIX + key)
    return value === 'table' || value === 'cards' ? value : null
  } catch {
    return null
  }
}

function storeView(key: string | undefined, view: DataTableView) {
  if (!key) return
  try {
    window.localStorage.setItem(VIEW_STORAGE_PREFIX + key, view)
  } catch {
    // A browser without storage (private mode): the choice lasts the page.
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
  /**
   * The rows shown are those of the previous request while the next loads (another page, order or filter): they
   * cannot be used (`inert`) and the table is busy, so nothing is done on a row that is about to go.
   */
  stale?: boolean
  /**
   * The <table>, which can take focus from a script (`tabIndex={-1}`, not a tab stop): where focus goes when what had
   * it disappeared with its row (a deleted entry's button).
   */
  tableRef?: Ref<HTMLTableElement>
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
  /**
   * Further attributes of a row's <tr> (test ids, a selected state, classes added to the row's own). The row's
   * `data-slot` is the table's; `data-state` is "expanded" while the row is open.
   */
  rowProps?: (row: Row) => HTMLAttributes<HTMLElement> & Record<`data-${string}`, string | undefined>
  className?: string
  /**
   * The card view (17zc): `'auto'` (the default when a column has a `meta.card` place or {@link renderCard} is given)
   * shows cards below the `md` breakpoint and the table from it, with a switch for the user; `true` always offers the
   * switch and starts with cards; `false` never shows cards.
   */
  cards?: boolean | 'auto'
  /** The view, when the caller holds it; otherwise the table remembers the user's choice under {@link storageKey}. */
  view?: DataTableView
  onViewChange?: (view: DataTableView) => void
  /** Remembers the user's choice of view in the browser (`localStorage`) under this key; none: for the page only. */
  storageKey?: string
  /** A card of the caller's own, instead of the one built from the columns' places. */
  renderCard?: (row: Row) => ReactNode
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
  stale = false,
  tableRef,
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
  cards,
  view: controlledView,
  onViewChange,
  storageKey,
  renderCard,
}: DataTableProps<Row>) {
  const { t } = useTranslation(UI_NAMESPACE)
  const [scrollRef, scrolls] = useScrollsSideways<HTMLDivElement>()
  const [expanded, setExpanded] = useState<ExpandedState>({})
  // 17zc: whether cards are on offer, and which view shows: the caller's, else the user's remembered choice, else
  // cards on a phone and the table elsewhere (or cards everywhere when asked for).
  const placed = renderCard !== undefined || columns.some((column) => column.meta?.card !== undefined)
  const cardsOffered = cards === undefined ? placed : cards !== false
  const mobile = useIsMobile()
  const [chosenView, setChosenView] = useState<DataTableView | null>(() => storedView(storageKey))
  const view: DataTableView = !cardsOffered ? 'table'
    : controlledView ?? chosenView ?? (cards === true || mobile ? 'cards' : 'table')
  const chooseView = (next: DataTableView) => {
    setChosenView(next)
    storeView(storageKey, next)
    onViewChange?.(next)
  }
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
  const pager = paged && (
    <Pager t={t} pagination={pagination} onPaginationChange={onPaginationChange} rowCount={rowCount}
      pageCount={pageCount} hasNext={hasNext} pageSizeOptions={pageSizeOptions} />
  )

  const sortable = onSortingChange !== undefined
    ? table.getAllLeafColumns().filter((column) => column.getCanSort()) : []
  const sortValue = sorting[0] ? `${sorting[0].id}:${sorting[0].desc ? 'desc' : 'asc'}` : 'none'
  const headerOf = (columnId: string): ReactNode => {
    const header = table.getFlatHeaders().find((h) => h.column.id === columnId)
    return header && !header.isPlaceholder ? flexRender(header.column.columnDef.header, header.getContext()) : null
  }

  const toolbar = cardsOffered && (
    <div className="flex flex-wrap items-center justify-between gap-2" data-slot="data-table-toolbar">
      {view === 'cards' && sortable.length > 0 ? (
        <Select value={sortValue} onValueChange={(value) => {
          if (value === 'none') onSortingChange?.([])
          else {
            const at = value.lastIndexOf(':')
            onSortingChange?.([{ id: value.slice(0, at), desc: value.slice(at + 1) === 'desc' }])
          }
        }}>
          <SelectTrigger size="sm" aria-label={t('dataTable.sortBy')} data-slot="data-table-sort">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="none">{t('dataTable.sortNone')}</SelectItem>
            {sortable.flatMap((column) => [
              <SelectItem key={`${column.id}:asc`} value={`${column.id}:asc`}>
                {headerOf(column.id)} <ArrowUpIcon aria-hidden className="inline size-3" />
                <span className="sr-only">{t('dataTable.sortAscending')}</span>
              </SelectItem>,
              <SelectItem key={`${column.id}:desc`} value={`${column.id}:desc`}>
                {headerOf(column.id)} <ArrowDownIcon aria-hidden className="inline size-3" />
                <span className="sr-only">{t('dataTable.sortDescending')}</span>
              </SelectItem>,
            ])}
          </SelectContent>
        </Select>
      ) : <span />}
      <SegmentedControl<DataTableView> value={view} onChange={chooseView} aria-label={t('dataTable.view')}
        data-testid="data-table-view" options={[
          { value: 'table', label: <><TableIcon aria-hidden className="size-4" /><span className="sr-only sm:not-sr-only">{t('dataTable.viewTable')}</span></> },
          { value: 'cards', label: <><LayoutGridIcon aria-hidden className="size-4" /><span className="sr-only sm:not-sr-only">{t('dataTable.viewCards')}</span></> },
        ]} />
    </div>
  )

  if (view === 'cards') {
    const leaf = table.getAllLeafColumns()
    const place = (column: typeof leaf[number]) => column.columnDef.meta?.card
    const titleColumn = leaf.find((c) => place(c) === 'title') ?? leaf.find((c) => place(c) === undefined && c.columnDef.meta?.pinned !== 'right')
    const actionColumns = leaf.some((c) => place(c) === 'actions') ? leaf.filter((c) => place(c) === 'actions')
      : leaf.filter((c) => c.columnDef.meta?.pinned === 'right')
    const listed = leaf.filter((c) => c !== titleColumn && !actionColumns.includes(c)
      && !['subtitle', 'badge', 'hidden'].includes(place(c) ?? ''))
    return (
      <div data-slot="data-table" data-view="cards" className={cn('flex flex-col gap-3', className)}>
        {toolbar}
        {loading && data.length === 0 ? (
          <ul aria-label={label} aria-busy="true" className="flex flex-col gap-3">
            {Array.from({ length: 3 }, (_, i) => (
              <li key={`loading-${i}`} className="bg-card rounded-md border p-4">
                <Skeleton className="h-5 w-2/3" />
                <Skeleton className="mt-3 h-4 w-full" />
                <span className="sr-only">{i === 0 ? t('loading') : ''}</span>
              </li>
            ))}
          </ul>
        ) : table.getRowModel().rows.length === 0 ? (
          <p aria-label={label} className="text-muted-foreground rounded-md border py-10 text-center text-sm">
            {empty ?? t('dataTable.empty')}
          </p>
        ) : (
          <ul aria-label={label} aria-busy={loading || stale || undefined} inert={stale || undefined}
            data-stale={stale || undefined} className={cn('flex flex-col gap-3', stale && 'opacity-60')}>
            {table.getRowModel().rows.map((row) => {
              const open = row.getIsExpanded()
              const extra = rowProps?.(row.original) ?? {}
              const cellOf = (column: typeof leaf[number]) => {
                const cell = row.getAllCells().find((c) => c.column.id === column.id)
                return cell ? flexRender(cell.column.columnDef.cell, cell.getContext()) : null
              }
              return (
                <li
                  {...extra}
                  key={row.id}
                  data-slot="data-table-card"
                  data-state={open ? 'expanded' : extra['data-state']}
                  className={cn('bg-card flex flex-col gap-3 rounded-md border p-4', extra.className)}
                >
                  {renderCard ? renderCard(row.original) : (
                    <>
                      <div className="flex flex-wrap items-start justify-between gap-2">
                        <div className="flex min-w-0 flex-col gap-1">
                          {titleColumn && <div className="font-medium" data-slot="data-table-card-title">{cellOf(titleColumn)}</div>}
                          {leaf.filter((c) => place(c) === 'subtitle').map((c) => (
                            <div key={c.id} className="text-muted-foreground text-sm">{cellOf(c)}</div>
                          ))}
                        </div>
                        {leaf.some((c) => place(c) === 'badge') && (
                          <div className="flex flex-wrap items-center gap-1">
                            {leaf.filter((c) => place(c) === 'badge').map((c) => <Fragment key={c.id}>{cellOf(c)}</Fragment>)}
                          </div>
                        )}
                      </div>
                      {listed.length > 0 && (
                        <DescriptionList className="text-sm" items={listed.map((c): DescriptionItem => ({
                          key: c.id, label: headerOf(c.id), value: cellOf(c) }))} />
                      )}
                      {(actionColumns.length > 0 || renderExpanded) && (
                        <div className="flex flex-wrap items-center justify-end gap-2">
                          {renderExpanded && (
                            <Button variant="ghost" size="sm" aria-expanded={open} onClick={row.getToggleExpandedHandler()}>
                              <ChevronRightIcon aria-hidden className={cn('transition-transform', open && 'rotate-90')} />
                              {open ? t('dataTable.collapseRow') : t('dataTable.expandRow')}
                            </Button>
                          )}
                          {actionColumns.map((c) => <Fragment key={c.id}>{cellOf(c)}</Fragment>)}
                        </div>
                      )}
                    </>
                  )}
                  {open && renderExpanded && (
                    <div className="bg-muted/40 rounded-md p-3">{renderExpanded(row.original)}</div>
                  )}
                </li>
              )
            })}
          </ul>
        )}
        {pager}
      </div>
    )
  }

  return (
    <div data-slot="data-table" data-view="table" className={cn('flex flex-col gap-3', className)}>
      {toolbar}
      <div className="rounded-md border">
        {/* shadcn's Table with its container here: a container that scrolls is a named, focusable region. */}
        <div
          ref={scrollRef}
          data-slot="table-container"
          className="focus-visible:ring-ring/50 relative w-full overflow-x-auto rounded-md outline-none focus-visible:ring-[3px]"
          {...(scrolls ? { role: 'region', 'aria-label': label, tabIndex: 0 } : {})}
        >
          <table
            ref={tableRef}
            tabIndex={-1}
            data-slot="table"
            className="w-full caption-bottom text-sm outline-none"
            aria-label={label}
            aria-busy={loading || stale || undefined}
          >
            <TableHeader>
              {table.getHeaderGroups().map((group) => (
                <TableRow key={group.id}>
                  {renderExpanded && (
                    <TableHead scope="col" className="w-10">
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
                        // A column header for every role computation: without the scope, Playwright's took these
                        // header cells (holding sort buttons) for plain cells.
                        scope="col"
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
            <TableBody
              inert={stale || undefined}
              data-stale={stale || undefined}
              className={cn(stale && 'opacity-60')}
            >
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
                  const extra = rowProps?.(row.original) ?? {}
                  return (
                    <Fragment key={row.id}>
                      {/* The caller's attributes first: the row's own mark, hover group and expanded state stay. */}
                      <TableRow
                        {...extra}
                        data-slot="data-table-row"
                        data-state={open ? 'expanded' : extra['data-state']}
                        className={cn('group/row', extra.className)}
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
                              <ChevronRightIcon aria-hidden className={cn('transition-transform', open && 'rotate-90')} />
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
      {pager}
    </div>
  )
}

/** The footer shared by both views: the count, the page size, the page and the arrows. */
function Pager({ t, pagination, onPaginationChange, rowCount, pageCount, hasNext, pageSizeOptions }: {
  t: (key: string, options?: Record<string, unknown>) => string
  pagination: PaginationState
  onPaginationChange: (pagination: PaginationState) => void
  rowCount: number | undefined
  pageCount: number | undefined
  hasNext: boolean
  pageSizeOptions: number[]
}) {
  return (
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
  )
}
