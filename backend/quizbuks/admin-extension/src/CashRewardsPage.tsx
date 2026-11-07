import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Button,
  DataTable,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  PageHeader,
  UserName,
  type ColumnDef,
} from '@jabiz/admin'
import { StatusFilter } from './finance-components'
import { EmptyState, LoadFailed, PublisherLink, StatusBadge } from './components'
import { money, useLabels, usePaged, when } from './hooks'
import { useMoreFilters } from './moreFilters'
import { DICTIONARY, PATH } from './ids'
import { readCashRewards, type CashReward } from './reward-api'
import { operatorColumn, useOperatorFilter } from './operator-filter'

/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS = { dueAt: 'dueAt', amount: 'amount', status: 'status' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }
const KEY = ['qb-ext', 'cash-rewards'] as const

/**
 * Every cash reward (docs/quizbuks/plans/Q22-reward-methods.md §2), newest first, by state and text: who owes whom
 * how much and where it stands. Nobody here decides a dispute (F-S1): the Sponsor marks it paid again, or the Taker
 * confirms it.
 */
export default function CashRewardsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const status = useLabels(DICTIONARY.cashStatus)
  const [search, setSearch] = useSearchParams()
  const filterStatus = search.get('status')
  const [text, setText] = useState(search.get('q') ?? '')
  const q = search.get('q') ?? ''
  const set = (change: Record<string, string | null>) => {
    const next = new URLSearchParams(search)
    for (const [key, value] of Object.entries(change)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setSearch(next, { replace: true })
  }
  const more = useMoreFilters([
    { kind: 'amount', name: 'amount', label: t('reward.cash.amount') },
    { kind: 'time', name: 'dueAt', label: t('reward.cash.dueAt') },
  ], 'cash-filters')
  const operators = useOperatorFilter()
  const filters = [...more.filters, ...operators.filters]
  const { query, table, sortable } = usePaged([...KEY, filterStatus, q, filters],
    (offset, limit, sorts) => readCashRewards(filterStatus, q, offset, limit, sorts, filters), true, SORTS)
  const columns = useMemo<ColumnDef<CashReward, unknown>[]>(() => [
    { id: 'dueAt', header: t('reward.cash.dueAt'), cell: ({ row }) => when(row.original.dueAt) },
    { id: 'code', header: t('reward.cash.code'),
      cell: ({ row }) => <code className="font-mono" data-testid="cash-row-code">{row.original.code}</code> },
    { id: 'sponsor', header: t('reward.cash.sponsor'),
      cell: ({ row }) => (
        <PublisherLink kind={row.original.publisherKind} id={row.original.publisherId}
          label={row.original.sponsorCompany} />
      ) },
    ...(operators.across ? [operatorColumn<CashReward>(t('pickers.operatorColumn'))] : []),
    { id: 'taker', header: t('reward.cash.taker'),
      cell: ({ row }) => (
        <Link to={PATH.user(row.original.userId)} className={linkClass}>
          {row.original.takerName ?? <UserName id={row.original.userId} />}
        </Link>
      ) },
    { id: 'publication', header: t('reward.cash.publication'), cell: ({ row }) => row.original.publicationName },
    { id: 'amount', header: t('reward.cash.amount'), meta: right, cell: ({ row }) => money(row.original.amount) },
    { id: 'status', header: t('reward.cash.status'),
      cell: ({ row }) => (
        <span className="flex flex-col gap-1">
          <StatusBadge code={row.original.status} label={status(row.original.status)} className="w-fit"
            testId="cash-row-status" />
          {row.original.status === 'DUE' && row.original.payBy && (
            <span className="text-muted-foreground text-xs">{t('reward.cash.payBy', { time: when(row.original.payBy) })}</span>
          )}
          {row.original.remindedAt && (
            <span className="text-muted-foreground text-xs">{t('reward.cash.reminded', { time: when(row.original.remindedAt) })}</span>
          )}
          {row.original.markedPaidAt && (
            <span className="text-muted-foreground text-xs">{t('reward.cash.markedPaid', { time: when(row.original.markedPaidAt) })}</span>
          )}
          {row.original.markedPaidNote && (
            <span className="text-xs">{t('reward.cash.sponsorNote', { note: row.original.markedPaidNote })}</span>
          )}
          {row.original.disputeNote && (
            <span className="text-xs" data-testid="cash-row-dispute">{t('reward.cash.disputeNote', { note: row.original.disputeNote })}</span>
          )}
          {row.original.resolveNote && (
            <span className="text-muted-foreground text-xs">{t('reward.cash.resolveNote', { note: row.original.resolveNote })}</span>
          )}
        </span>
      ) },
  ], [t, status, operators.across])

  return (
    <div className="flex flex-col gap-4" data-testid="cash-rewards">
      <PageHeader title={t('reward.cash.title')} description={t('reward.cash.description')} className="pb-0" />
      <div className="flex flex-wrap items-end gap-4" role="group" aria-label={t('finance.filter.label')}>
        {operators.bar}
        <StatusFilter dictionary={DICTIONARY.cashStatus} value={filterStatus} testId="cash-status"
          onChange={(value) => set({ status: value })} />
        <form role="search" className="flex items-end gap-2" onSubmit={(event) => {
          event.preventDefault()
          set({ q: text.trim() || null })
        }}>
          <div className="flex flex-col gap-1">
            <Label htmlFor="cash-q" className="sr-only">{t('reward.cash.search')}</Label>
            <Input id="cash-q" type="search" value={text} maxLength={100} placeholder={t('reward.cash.search')}
              data-testid="cash-search" onChange={(event) => setText(event.target.value)} />
          </div>
          <Button type="submit" variant="outline">{t('reward.cash.search')}</Button>
        </form>
        {(filterStatus || q) && (
          <Button variant="ghost" size="sm" onClick={() => {
            setText('')
            set({ status: null, q: null })
          }}>{t('reward.cash.all')}</Button>
        )}
      </div>
      {more.bar}
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('reward.cash.table')} columns={sortable(columns)} {...table} getRowId={(row) => row.cashRewardId}
            empty={<EmptyState art="money" title={t('reward.cash.none')} />}
            rowProps={(row) => ({ 'data-testid': 'cash-row', 'data-id': row.cashRewardId, 'data-status': row.status })} />
        )}
    </div>
  )
}
