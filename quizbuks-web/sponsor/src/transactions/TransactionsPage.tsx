import { formatDateTime } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  DataTable,
  DescriptionList,
  FilterBar,
  PageHeader,
  PageState,
  type ColumnDef,
  type FilterField,
  type FilterValues,
  type PaginationState,
  type SortingState,
} from '@jabiz/ui'
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query'
import { Download, X } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { useLocation } from '../qb/router'
import {
  exportTransactions,
  filtersOf,
  NO_FILTERS,
  paramsOf,
  readPublicationOptions,
  readSummary,
  searchTransactions,
  TRANSACTION_STATUSES,
  TRANSACTION_TYPES,
  TRANSACTIONS_QUERY,
  type ExportFormat,
  type TransactionFilters,
  type TransactionRow,
  type Total,
  type TransactionStatus,
  type TransactionType,
} from './api'
import './messages'

const PAGE_SIZE = 20

const STATUS_BADGES: Record<TransactionStatus, 'success' | 'warning' | 'destructive'> = {
  COMPLETED: 'success',
  PROCESSING: 'warning',
  FAILED: 'destructive',
}

/** The filter bar's values for the filters (the user is a chip of its own, not a field of the bar). */
function barValues(filters: TransactionFilters): FilterValues {
  return {
    type: filters.type,
    status: filters.status,
    publicationId: filters.publicationId,
    period: filters.from || filters.to ? { from: filters.from, to: filters.to } : null,
    q: filters.q,
  }
}

const oneOf = <T extends string>(value: unknown, allowed: readonly T[]): T | null =>
  typeof value === 'string' && (allowed as readonly string[]).includes(value) ? (value as T) : null

interface SummaryItem {
  key: string
  label: string
  /** A count and its amount, or an amount alone. */
  total?: Total
  amount?: string
}

/** One kind of money in the totals: rewards (paid out) or top-ups (paid in), never added to each other. */
function SummaryGroup({ title, items }: { title: string; items: SummaryItem[] }) {
  const { t } = useTranslation(SPONSOR)
  return (
    <div className="flex flex-col gap-2">
      <h3 className="text-sm font-medium">{title}</h3>
      <DescriptionList columns={4} items={items.map((item) => ({
        key: item.key,
        label: item.label,
        value: (
          <span data-testid={`summary-${item.key}`} data-count={item.total?.count}>
            {item.total
              ? t('transactions.summary.value', { amount: formatKudos(item.total.amount),
                count: item.total.count.toLocaleString() })
              : formatKudos(item.amount)}
          </span>
        ),
      }))} />
    </div>
  )
}

/**
 * The sponsor's transactions (M-50 – M-52): the totals of the rows shown (`qb.sponsor.transaction-summary`), the
 * filters, the rows themselves (`qb.sponsor.transactions`, paged and sorted by the server) and the export of exactly
 * those rows through the platform. Takers are shown by their profile's name; "Only this user" filters by the row's
 * taker. `?publication=<id>` opens the page for one publication (from its page).
 */
export function TransactionsPage() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const { search } = useLocation()
  const [filters, setFilters] = useState<TransactionFilters>(() => filtersOf(search))
  const [userName, setUserName] = useState<string | null>(null)
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const [sorting, setSorting] = useState<SortingState>([{ id: 'occurredAt', desc: true }])
  const params = useMemo(() => paramsOf(filters), [filters])
  const sorts = useMemo(() => sorting.map((s) => ({ field: s.id, asc: !s.desc })), [sorting])
  const values = useMemo(() => barValues(filters), [filters])

  const publications = useQuery({ queryKey: [...TRANSACTIONS_QUERY, 'publications'], queryFn: readPublicationOptions })
  const summary = useQuery({
    queryKey: [...TRANSACTIONS_QUERY, 'summary', params],
    queryFn: () => readSummary(params),
    placeholderData: keepPreviousData,
  })
  const rows = useQuery({
    queryKey: [...TRANSACTIONS_QUERY, 'list', params, pagination, sorts],
    queryFn: () => searchTransactions({
      params,
      sorts,
      offset: pagination.pageIndex * pagination.pageSize,
      limit: pagination.pageSize,
    }),
    placeholderData: keepPreviousData,
  })
  const exporting = useMutation({
    mutationFn: (format: ExportFormat) => exportTransactions(format, params, sorts),
  })

  const filter = (next: TransactionFilters) => {
    setFilters(next)
    setPagination((p) => (p.pageIndex === 0 ? p : { ...p, pageIndex: 0 }))
    exporting.reset()
  }
  const onlyUser = (row: TransactionRow) => {
    if (!row.userKey) return
    setUserName(row.userName)
    filter({ ...filters, userKey: row.userKey })
  }

  const fields = useMemo<FilterField[]>(() => [
    { kind: 'select', name: 'type', label: t('transactions.filters.type'),
      options: TRANSACTION_TYPES.map((code) => ({ value: code, label: t(`transactions.types.${code}`) })) },
    { kind: 'select', name: 'status', label: t('transactions.filters.status'),
      options: TRANSACTION_STATUSES.map((code) => ({ value: code, label: t(`transactions.statuses.${code}`) })) },
    { kind: 'date-range', name: 'period', label: t('transactions.filters.period') },
    { kind: 'select', name: 'publicationId', label: t('transactions.filters.publication'),
      options: (publications.data ?? []).map((p) => ({ value: p.publicationId, label: p.name })) },
    { kind: 'text', name: 'q', label: t('transactions.filters.search'), placeholder: t('transactions.filters.searchHint') },
  ], [t, publications.data])

  // Not memoized: the row action extends the filters in force.
  const columns: ColumnDef<TransactionRow, unknown>[] = [
    {
      id: 'occurredAt',
      header: t('transactions.occurredAt'),
      cell: ({ row }) => formatDateTime(row.original.occurredAt),
    },
    {
      id: 'type',
      header: t('transactions.type'),
      enableSorting: false,
      cell: ({ row }) => t(`transactions.types.${row.original.type}`),
    },
    {
      id: 'user',
      header: t('transactions.user'),
      enableSorting: false,
      cell: ({ row }) => row.original.type !== 'REWARD' ? '—'
        : <span data-testid="transaction-user">{row.original.userName ?? t('transactions.noUser')}</span>,
    },
    {
      id: 'publication',
      header: t('transactions.publication'),
      enableSorting: false,
      cell: ({ row }) => row.original.publicationName ?? '—',
    },
    {
      id: 'score',
      header: t('transactions.score'),
      enableSorting: false,
      meta: { className: 'text-right' },
      cell: ({ row }) => row.original.score === null ? '—'
        : t('transactions.scoreValue', { score: row.original.score, full: row.original.fullScore ?? '—' }),
    },
    {
      id: 'amount',
      header: t('transactions.amount'),
      meta: { className: 'text-right' },
      cell: ({ row }) => (
        <span className="flex flex-col items-end gap-1">
          <span data-testid="transaction-amount">{formatKudos(row.original.amount)}</span>
          {row.original.refunded !== null && Number(row.original.refunded) > 0 && (
            <span className="text-muted-foreground text-xs">
              {t('transactions.refunded', { amount: formatKudos(row.original.refunded) })}
            </span>
          )}
        </span>
      ),
    },
    {
      id: 'status',
      header: t('transactions.status'),
      enableSorting: false,
      cell: ({ row }) => (
        <Badge variant={STATUS_BADGES[row.original.status] ?? 'outline'} data-testid="transaction-status">
          {t(`transactions.statuses.${row.original.status}`)}
        </Badge>
      ),
    },
    {
      id: 'actions',
      header: () => <span className="sr-only">{t('transactions.onlyUser')}</span>,
      enableSorting: false,
      cell: ({ row }) => row.original.userKey && row.original.userKey !== filters.userKey ? (
        <Button size="sm" variant="outline" data-testid="transaction-only-user" onClick={() => onlyUser(row.original)}
          aria-label={t('transactions.onlyUserLabel', { name: row.original.userName ?? t('transactions.noUser') })}>
          {t('transactions.onlyUser')}
        </Button>
      ) : null,
    },
  ]

  const filtered = Object.keys(params).length > 0
  const totals = summary.data
  return (
    <section className="flex flex-col gap-6" data-testid="transactions">
      <PageHeader title={t('transactions.title')} description={t('transactions.intro')}
        actions={
          <div className="flex flex-wrap gap-2" role="group" aria-label={t('transactions.export.hint')}>
            {(['csv', 'xlsx'] as const).map((format) => (
              <Button key={format} variant="outline" disabled={exporting.isPending}
                aria-busy={exporting.isPending && exporting.variables === format ? true : undefined}
                data-testid={`transactions-export-${format}`} onClick={() => exporting.mutate(format)}>
                <Download aria-hidden />
                {t(`transactions.export.${format}`)}
              </Button>
            ))}
          </div>
        } />
      {exporting.isError && (
        <Alert variant="destructive" data-testid="transactions-export-problem">
          <AlertDescription>{problemText(exporting.error)}</AlertDescription>
        </Alert>
      )}

      <section aria-labelledby="transactions-summary" className="flex flex-col gap-3">
        <h2 id="transactions-summary" className="text-lg font-semibold">{t('transactions.summary.label')}</h2>
        {summary.isError ? (
          <PageState kind="error" description={tQb('failed')}
            action={<Button variant="outline" onClick={() => void summary.refetch()}>{tQb('retry')}</Button>} />
        ) : !totals ? (
          <PageState kind="loading" />
        ) : (
          <div className="flex flex-col gap-4" data-testid="transactions-summary">
            <p className="text-sm">
              {t('transactions.summary.total')}:{' '}
              <span className="font-semibold" data-testid="summary-total">{totals.totalCount.toLocaleString()}</span>
            </p>
            {filters.type !== 'TOPUP' && (
              <SummaryGroup title={t('transactions.summary.rewards')} items={[
                { key: 'reward-completed', label: t('transactions.summary.rewardCompleted'), total: totals.reward.completed },
                { key: 'reward-processing', label: t('transactions.summary.rewardProcessing'),
                  total: totals.reward.processing },
              ]} />
            )}
            {filters.type !== 'REWARD' && (
              <SummaryGroup title={t('transactions.summary.topUps')} items={[
                { key: 'topup-completed', label: t('transactions.summary.topUpCompleted'), total: totals.topUp.completed },
                { key: 'topup-refunded', label: t('transactions.summary.topUpRefunded'), amount: totals.topUp.refunded },
                { key: 'topup-processing', label: t('transactions.summary.topUpProcessing'),
                  total: totals.topUp.processing },
                { key: 'topup-failed', label: t('transactions.summary.topUpFailed'), total: totals.topUp.failed },
              ]} />
            )}
          </div>
        )}
      </section>

      <section aria-labelledby="transactions-list" className="flex flex-col gap-3">
        <h2 id="transactions-list" className="text-lg font-semibold">{t('transactions.table')}</h2>
        <FilterBar label={t('transactions.filters.label')} fields={fields} values={values}
          onSubmit={(next) => filter({
            ...filters,
            type: oneOf<TransactionType>(next.type, TRANSACTION_TYPES),
            status: oneOf<TransactionStatus>(next.status, TRANSACTION_STATUSES),
            publicationId: typeof next.publicationId === 'string' ? next.publicationId : null,
            from: typeof next.period === 'object' && next.period ? next.period.from ?? null : null,
            to: typeof next.period === 'object' && next.period ? next.period.to ?? null : null,
            q: typeof next.q === 'string' ? next.q : '',
          })}
          onReset={() => {
            setUserName(null)
            filter(NO_FILTERS)
          }} />
        {filters.userKey && (
          <div className="flex items-center gap-2">
            <Badge variant="secondary" className="gap-1 pr-1" data-testid="transactions-user-filter">
              {t('transactions.userFilter', { name: userName ?? t('transactions.noUser') })}
              <Button size="icon" variant="ghost" className="size-5" aria-label={t('transactions.clearUser')}
                data-testid="transactions-user-clear" onClick={() => {
                  setUserName(null)
                  filter({ ...filters, userKey: null })
                }}>
                <X aria-hidden />
              </Button>
            </Badge>
          </div>
        )}
        {rows.isError
          ? <PageState kind="error" description={tQb('failed')}
            action={<Button variant="outline" onClick={() => void rows.refetch()}>{tQb('retry')}</Button>} />
          : (
            <DataTable label={t('transactions.table')} columns={columns} data={rows.data?.items ?? []}
              getRowId={(row) => `${row.type}:${row.entryId}`} loading={rows.isPending} stale={rows.isPlaceholderData}
              empty={filtered ? t('transactions.noMatch') : t('transactions.none')}
              pagination={pagination} onPaginationChange={setPagination} rowCount={rows.data?.total}
              sorting={sorting} onSortingChange={(next) => {
                setSorting(next)
                setPagination((p) => ({ ...p, pageIndex: 0 }))
              }}
              rowProps={(row) => ({ 'data-testid': 'transaction-row', 'data-entry': row.entryId,
                'data-type': row.type, 'data-status': row.status })} />
          )}
      </section>
    </section>
  )
}
