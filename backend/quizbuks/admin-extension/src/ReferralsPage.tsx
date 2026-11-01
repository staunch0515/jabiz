import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import { Button, DataTable, EXTENSION_NAMESPACE, PageHeader, UserName, type ColumnDef } from '@jabiz/admin'
import { StatusFilter } from './finance-components'
import { EmptyState, LoadFailed, PublisherLink, StatusBadge } from './components'
import { money, usePaged, when } from './hooks'
import { DICTIONARY, PATH } from './ids'
import { readReferrals, type Referral } from './referral-api'

const SORTS = { referredAt: 'referredAt', status: 'status', amount: 'amount' } as const
const KEY = ['qb-ext', 'referrals'] as const
const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }

/**
 * Every referral (Q70), newest first, by state and referrer: who referred whom to which publication, until when and
 * what came of it. Read only: the referrals are the processes' alone; a referrer is checked by their certification.
 */
export default function ReferralsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [search, setSearch] = useSearchParams()
  const status = search.get('status')
  const referrer = search.get('referrer')
  const set = (change: Record<string, string | null>) => {
    const next = new URLSearchParams(search)
    for (const [key, value] of Object.entries(change)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setSearch(next, { replace: true })
  }
  const { query, table, sortable } = usePaged([...KEY, status, referrer],
    (offset, limit, sorts) => readReferrals(status, referrer, offset, limit, sorts), true, SORTS)
  const columns = useMemo<ColumnDef<Referral, unknown>[]>(() => [
    { id: 'referredAt', header: t('referral.referredAt'), cell: ({ row }) => when(row.original.referredAt) },
    { id: 'publication', header: t('referral.publication'), cell: ({ row }) => row.original.publicationName },
    { id: 'sponsor', header: t('referral.sponsor'), cell: ({ row }) => (
      <PublisherLink kind={row.original.publisherKind} id={row.original.publisherId} label={null} />
    ) },
    { id: 'referrer', header: t('referral.referrer'), cell: ({ row }) => row.original.referrerId ? (
      <span className="flex flex-col gap-1">
        <Link to={PATH.user(row.original.referrerId)} className={linkClass}><UserName id={row.original.referrerId} /></Link>
        {!referrer && (
          <Button size="sm" variant="ghost" className="w-fit px-0 text-xs" data-testid="referral-only-referrer"
            onClick={() => set({ referrer: row.original.referrerId })}>{t('referral.onlyReferrer')}</Button>
        )}
      </span>
    ) : '—' },
    { id: 'referee', header: t('referral.referee'), cell: ({ row }) => row.original.refereeId ? (
      <Link to={PATH.user(row.original.refereeId)} className={linkClass}><UserName id={row.original.refereeId} /></Link>
    ) : '—' },
    { id: 'amount', header: t('referral.reward'), meta: right, cell: ({ row }) => money(row.original.amount) },
    { id: 'status', header: t('referral.status'), cell: ({ row }) => (
      <span className="flex flex-col gap-1">
        <StatusBadge code={row.original.status} label={t(`referral.states.${row.original.status}`)} className="w-fit"
          testId="referral-row-status" />
        {row.original.status === 'REFERRED' && (
          <span className="text-muted-foreground text-xs">{t('referral.expiresAt', { time: when(row.original.expiresAt) })}</span>
        )}
        {row.original.fundsShort && <span className="text-xs">{t('referral.fundsShort')}</span>}
        {row.original.voidReason && (
          <span className="text-muted-foreground text-xs">{t('referral.voidReason', { reason: row.original.voidReason })}</span>
        )}
      </span>
    ) },
  // eslint-disable-next-line react-hooks/exhaustive-deps
  ], [t, referrer, search])

  return (
    <div className="flex flex-col gap-4" data-testid="referrals">
      <PageHeader title={t('referral.title')} description={t('referral.description')} className="pb-0" />
      <div className="flex flex-wrap items-end gap-4" role="group" aria-label={t('finance.filter.label')}>
        <StatusFilter dictionary={DICTIONARY.referralStatus} value={status} testId="referral-status"
          onChange={(value) => set({ status: value })} />
        {referrer && (
          <Button variant="outline" data-testid="referral-clear-referrer" onClick={() => set({ referrer: null })}>
            {t('referral.clearReferrer')}
          </Button>
        )}
      </div>
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('referral.title')} columns={sortable(columns)} {...table} getRowId={(row) => row.referralId}
            empty={<EmptyState title={t('referral.none')} />}
            rowProps={(row) => ({ 'data-testid': 'referral-row', 'data-id': row.referralId, 'data-status': row.status })} />
        )}
    </div>
  )
}
