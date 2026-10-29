import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Badge,
  Button,
  DataTable,
  EXTENSION_NAMESPACE,
  PageHeader,
  UserName,
  type ColumnDef,
} from '@jabiz/admin'
import { topUpAddressOf, topUpFilterOf } from './finance'
import { readFinanceTopUps, type FinanceTopUp, type TopUpFilter } from './finance-api'
import { DayRange, StatusFilter } from './finance-components'
import { LoadFailed } from './components'
import { money, useLabels, usePaged, when } from './hooks'
import { DICTIONARY, PATH } from './ids'

/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS = { createdAt: 'createdAt', amount: 'amount', status: 'status' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }

const positive = (value: string | null) => value != null && Number(value) > 0

/**
 * Every sponsor's top-ups (F-04, F-06), newest first, page by page: filtered by state, period, sponsor or one top-up
 * (the address carries the filter: the reconciliation links here). Each shows what the sponsor paid, Stripe's fee
 * and the net, the customer's own currency, what was refunded or taken back by a lost dispute, and what could not be
 * taken back (a shortfall, marked: the money left the sponsor's wallet before).
 */
export default function TopUpsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const status = useLabels(DICTIONARY.topUpStatus)
  const [search, setSearch] = useSearchParams()
  const filter = topUpFilterOf(search)
  const set = (change: Partial<TopUpFilter>) => setSearch(topUpAddressOf({ ...filter, ...change }), { replace: true })
  const { query, table, sortable } = usePaged(['qb-ext', 'finance', 'topups', filter],
    (offset, limit, sorts) => readFinanceTopUps(filter, offset, limit, sorts), true, SORTS)
  const columns = useMemo<ColumnDef<FinanceTopUp, unknown>[]>(() => [
    { id: 'createdAt', header: t('finance.topUps.createdAt'), cell: ({ row }) => when(row.original.createdAt) },
    {
      id: 'sponsor',
      header: t('finance.topUps.sponsor'),
      cell: ({ row }) => (
        <Link to={PATH.user(row.original.sponsorId)} className={linkClass} data-testid="topup-sponsor">
          {row.original.company ?? <UserName id={row.original.sponsorId} />}
        </Link>
      ),
    },
    { id: 'amount', header: t('finance.topUps.amount'), meta: right, cell: ({ row }) => money(row.original.amount) },
    { id: 'fee', header: t('finance.topUps.fee'), meta: right, cell: ({ row }) => money(row.original.stripeFee) },
    { id: 'net', header: t('finance.topUps.net'), meta: right, cell: ({ row }) => money(row.original.netAmount) },
    {
      id: 'presentment',
      header: t('finance.topUps.presentment'),
      meta: right,
      cell: ({ row }) => (row.original.presentmentCurrency && row.original.presentmentAmount
        ? `${row.original.presentmentAmount} ${row.original.presentmentCurrency.toUpperCase()}` : ''),
    },
    {
      id: 'refunded',
      header: t('finance.topUps.refunded'),
      meta: right,
      cell: ({ row }) => (positive(row.original.refundedAmount) ? money(row.original.refundedAmount) : ''),
    },
    {
      id: 'dispute',
      header: t('finance.topUps.dispute'),
      cell: ({ row }) => [row.original.disputeStatus,
        positive(row.original.disputeClawback) ? money(row.original.disputeClawback) : null]
        .filter(Boolean).join(' · '),
    },
    {
      id: 'shortfall',
      header: t('finance.topUps.shortfall'),
      meta: right,
      cell: ({ row }) => {
        const shortfall = Number(row.original.refundShortfall) + Number(row.original.disputeShortfall)
        return shortfall > 0
          ? <Badge variant="warning" data-testid="topup-shortfall">{money(String(shortfall))}</Badge>
          : ''
      },
    },
    {
      id: 'status',
      header: t('finance.topUps.status'),
      cell: ({ row }) => (
        <span className="flex flex-wrap items-center gap-1">
          <Badge variant="outline">{status(row.original.status)}</Badge>
          {row.original.checkReason && <Badge variant="warning">{t('finance.topUps.check')}</Badge>}
        </span>
      ),
    },
    {
      id: 'payment',
      header: t('finance.topUps.payment'),
      cell: ({ row }) => (row.original.paymentIntentId
        ? <code className="text-xs break-all">{row.original.paymentIntentId}</code> : ''),
    },
  ], [t, status])

  return (
    <div className="flex flex-col gap-4" data-testid="finance-topups">
      <PageHeader title={t('finance.topUps.title')} description={t('finance.topUps.description')}
        className="pb-0" />
      <div className="flex flex-wrap items-end gap-4" role="group" aria-label={t('finance.filter.label')}>
        <StatusFilter dictionary={DICTIONARY.topUpStatus} value={filter.status} testId="topups-status"
          onChange={(value) => set({ status: value })} />
        <DayRange from={filter.from} to={filter.to} onChange={(from, to) => set({ from, to })} />
        {(filter.sponsorId || filter.topUpId) && (
          <Button variant="ghost" size="sm" data-testid="topups-clear-one"
            onClick={() => set({ sponsorId: null, topUpId: null })}>
            {t('finance.topUps.all')}
          </Button>
        )}
      </div>
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('finance.topUps.table')} columns={sortable(columns)} {...table}
            getRowId={(row) => row.topUpId} empty={t('finance.topUps.none')}
            rowProps={(row) => ({ 'data-testid': 'topup-row', 'data-id': row.topUpId, 'data-status': row.status })} />
        )}
    </div>
  )
}
