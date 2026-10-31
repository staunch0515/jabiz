import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Button,
  DataTable,
  EXTENSION_NAMESPACE,
  PageHeader,
  UserName,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { EmptyState, LoadFailed, StatusBadge } from './components'
import { StatusFilter } from './finance-components'
import { money, useLabels, usePaged, when } from './hooks'
import { useMoreFilters } from './moreFilters'
import { DICTIONARY, PATH, PERMISSION } from './ids'
import { attemptFilterOf, readAllAttempts, readAttemptKudos, type AttemptListRow } from './payout-api'

/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS = { startedAt: 'startedAt', status: 'status', score: 'score' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }

/**
 * Every taker's attempts (Q14), newest first, by taker, publication, state or reward state (the address carries the
 * filter: a user's page and a publication link here). Neither answers nor results: support needs the outcome. Finance
 * also sees each paid attempt's Kudos (the taker's share of the reward, as posted), read for the page shown.
 */
export default function AttemptsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const finance = can(PERMISSION.financeRead)
  const statusLabel = useLabels(DICTIONARY.attemptStatus)
  const rewardLabel = useLabels(DICTIONARY.rewardStatus)
  const [search, setSearch] = useSearchParams()
  const filter = attemptFilterOf(search)
  const set = (change: Record<string, string | null>) => {
    const next = new URLSearchParams(search)
    for (const [key, value] of Object.entries(change)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setSearch(next, { replace: true })
  }
  const more = useMoreFilters([
    { kind: 'time', name: 'startedAt', label: t('attempts.startedAt') },
    { kind: 'time', name: 'submittedAt', label: t('attempts.submittedAt') },
  ], 'attempts-filters')
  const { query, table, sortable } = usePaged(['qb-ext', 'attempts', filter, more.filters],
    (offset, limit, sorts) => readAllAttempts(filter, offset, limit, sorts, more.filters), true, SORTS)
  const ids = (query.data?.items ?? []).map((row) => row.attemptId)
  const kudos = useQuery({ queryKey: ['qb-ext', 'attempt-kudos', ids], queryFn: () => readAttemptKudos(ids),
    enabled: finance && ids.length > 0 })

  const columns = useMemo<ColumnDef<AttemptListRow, unknown>[]>(() => [
    { id: 'startedAt', header: t('attempts.startedAt'), cell: ({ row }) => when(row.original.startedAt) },
    {
      id: 'user',
      header: t('attempts.user'),
      cell: ({ row }) => (
        <Link to={PATH.user(row.original.userId)} className={linkClass} data-testid="attempt-user">
          <UserName id={row.original.userId} />
        </Link>
      ),
    },
    {
      id: 'publication',
      header: t('attempts.publication'),
      cell: ({ row }) => (
        <Link to={PATH.attemptsOf({ publication: row.original.publicationId })} className={linkClass}>
          {row.original.name ?? row.original.publicationId}
        </Link>
      ),
    },
    { id: 'status', header: t('attempts.status'),
      cell: ({ row }) => <StatusBadge code={row.original.status} label={statusLabel(row.original.status)} /> },
    { id: 'score', header: t('attempts.score'), meta: right,
      cell: ({ row }) => (row.original.score === null ? '' : `${row.original.score} / ${row.original.fullScore ?? '?'}`) },
    { id: 'passed', header: t('attempts.passed'),
      cell: ({ row }) => (row.original.score === null ? '' : row.original.passed ? t('common.yes') : t('common.no')) },
    { id: 'reward', header: t('attempts.reward'),
      cell: ({ row }) => (row.original.rewardStatus ? rewardLabel(row.original.rewardStatus) : '') },
    ...(finance ? [{ id: 'kudos', header: t('attempts.kudos'), meta: right,
      cell: ({ row }: { row: { original: AttemptListRow } }) => {
        const value = row.original.rewardStatus === 'PAID' ? kudos.data?.get(row.original.attemptId) : undefined
        return value === undefined ? '' : <span data-testid="attempt-kudos">{money(value)}</span>
      } }] : []),
  ], [t, statusLabel, rewardLabel, finance, kudos.data])

  const filtered = filter.userId || filter.publicationId || filter.status || filter.rewardStatus
  return (
    <div className="flex flex-col gap-4" data-testid="qb-attempts">
      <PageHeader title={t('attempts.title')} description={t('attempts.description')} className="pb-0" />
      <div className="flex flex-wrap items-end gap-4" role="group" aria-label={t('finance.filter.label')}>
        <StatusFilter dictionary={DICTIONARY.attemptStatus} value={filter.status} testId="attempts-status"
          onChange={(value) => set({ status: value })} />
        <StatusFilter dictionary={DICTIONARY.rewardStatus} value={filter.rewardStatus} testId="attempts-reward"
          onChange={(value) => set({ reward: value })} />
        {(filter.userId || filter.publicationId) && (
          <span className="text-sm" data-testid="attempts-one">
            {filter.userId && <UserName id={filter.userId} />}
          </span>
        )}
        {filtered && (
          <Button variant="ghost" size="sm" onClick={() => set({ user: null, publication: null, status: null,
            reward: null })} data-testid="attempts-all">
            {t('attempts.all')}
          </Button>
        )}
      </div>
      {more.bar}
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('attempts.table')} columns={sortable(columns)} {...table} getRowId={(row) => row.attemptId}
            empty={<EmptyState art="content" title={t('attempts.none')} />}
            rowProps={(row) => ({ 'data-testid': 'attempt-row', 'data-id': row.attemptId, 'data-status': row.status })} />
        )}
    </div>
  )
}
