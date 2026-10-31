import { useQuery } from '@tanstack/react-query'
import { useMemo, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Badge,
  DataTable,
  EXTENSION_NAMESPACE,
  PageHeader,
  PageState,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  UserName,
  type ColumnDef,
} from '@jabiz/admin'
import { byCheck } from './finance'
import { readReconItems, readReconRuns, type ReconItem, type ReconRun } from './finance-api'
import { EmptyState, LoadFailed, Section } from './components'
import { money, useLabels, usePaged, when } from './hooks'
import { useMoreFilters } from './moreFilters'
import { DICTIONARY, isUserId, PAGE_LIMIT, PATH, referenceKind } from './ids'

/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS = { scheduledTime: 'scheduledTime' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

/** What a difference is about: its top-up or transfer, and its party's summary. */
function Subject({ item }: { item: ReconItem }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const kind = referenceKind(item.checkCode)
  const parts: ReactNode[] = []
  if (item.reference && kind === 'topUp') {
    parts.push(<Link key="ref" to={PATH.topUpsWhere({ topUp: item.reference })} className={linkClass}
      data-testid="recon-reference" data-kind="topUp">{t('finance.recon.topUp')}</Link>)
  } else if (item.reference && kind === 'transfer') {
    parts.push(<Link key="ref" to={PATH.transfersWhere({ transfer: item.reference })} className={linkClass}
      data-testid="recon-reference" data-kind="transfer">{t('finance.recon.transfer')}</Link>)
  } else if (item.reference) {
    parts.push(<code key="ref" className="text-xs">{item.reference}</code>)
  }
  if (item.partyId && isUserId(item.partyId)) {
    parts.push(<Link key="party" to={PATH.user(item.partyId)} className={linkClass} data-testid="recon-party">
      <UserName id={item.partyId} />
    </Link>)
  }
  return parts.length > 0 ? <span className="flex flex-wrap gap-x-3">{parts}</span> : <>{t('finance.recon.whole')}</>
}

/**
 * One run's differences, grouped by check (the first {@link PAGE_LIMIT}: a run that finds more says so); read only
 * (C-3: a difference is handled through the money tasks and by hand).
 */
function Items({ runId }: { runId: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const checkLabel = useLabels(DICTIONARY.reconCheck)
  const query = useQuery({ queryKey: ['qb-ext', 'finance', 'recon-items', runId],
    queryFn: () => readReconItems(runId, 0, PAGE_LIMIT) })
  if (query.isError) return <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
  if (query.isPending) return <PageState kind="loading" />
  const groups = byCheck(query.data.items)
  const total = query.data.total ?? query.data.items.length
  return (
    <div className="flex flex-col gap-4" data-testid="recon-items">
      {groups.length === 0 && <p className="text-muted-foreground text-sm">{t('finance.recon.noItems')}</p>}
      {groups.map(([check, items]) => (
        <div key={check} className="flex flex-col gap-2" data-testid="recon-group" data-check={check}>
          <h3 className="font-semibold">{checkLabel(check)}</h3>
          <Table aria-label={t('finance.recon.groupTable', { check: checkLabel(check) })}>
            <TableHeader>
              <TableRow>
                <TableHead>{t('finance.recon.subject')}</TableHead>
                <TableHead className="text-right">{t('finance.recon.expected')}</TableHead>
                <TableHead className="text-right">{t('finance.recon.actual')}</TableHead>
                <TableHead className="text-right">{t('finance.recon.difference')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((item) => (
                <TableRow key={item.itemId} data-testid="recon-item">
                  <TableCell><Subject item={item} /></TableCell>
                  <TableCell className="text-right tabular-nums">{money(item.expected)}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(item.actual)}</TableCell>
                  <TableCell className="text-right tabular-nums">{money(item.difference)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      ))}
      {total > query.data.items.length && (
        <p className="text-muted-foreground text-sm" data-testid="recon-more">
          {t('finance.recon.more', { shown: query.data.items.length, total })}
        </p>
      )}
    </div>
  )
}

/**
 * The daily reconciliation (F-06): its runs, newest first, and a run's differences grouped by check, each leading to
 * the top-up, the transfer or the user it is about. Read only (C-3): a difference is written once and handled through
 * the money tasks and by hand.
 */
export default function ReconciliationPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [search] = useSearchParams()
  const selected = search.get('run')
  const runId = selected && isUserId(selected) ? selected : null
  const more = useMoreFilters([
    { kind: 'choice', name: 'status', label: t('finance.recon.result'),
      options: ['OK', 'DIFF'].map((code) => ({ value: code, label: t(`finance.recon.status.${code}`) })) },
    { kind: 'time', name: 'scheduledTime', label: t('finance.recon.scheduledTime') },
  ], 'recon-filters')
  const { query, table, sortable } = usePaged(['qb-ext', 'finance', 'recon-runs', more.filters],
    (offset, limit, sorts) => readReconRuns(offset, limit, sorts, more.filters), true, SORTS)
  const columns = useMemo<ColumnDef<ReconRun, unknown>[]>(() => [
    {
      id: 'scheduledTime',
      header: t('finance.recon.scheduledTime'),
      cell: ({ row }) => (
        <Link to={PATH.reconRun(row.original.runId)} className={linkClass} data-testid="recon-run-link"
          aria-current={row.original.runId === runId ? 'true' : undefined}>
          {when(row.original.scheduledTime)}
        </Link>
      ),
    },
    {
      id: 'status',
      header: t('finance.recon.result'),
      cell: ({ row }) => (
        <Badge variant={row.original.status === 'DIFF' ? 'warning' : 'success'}>
          {t(`finance.recon.status.${row.original.status === 'DIFF' ? 'DIFF' : 'OK'}`)}
        </Badge>
      ),
    },
    { id: 'items', header: t('finance.recon.itemCount'), meta: { className: 'text-right tabular-nums' },
      cell: ({ row }) => row.original.itemCount.toLocaleString() },
    { id: 'checkedAt', header: t('finance.recon.checkedAt'), cell: ({ row }) => when(row.original.checkedAt) },
  ], [t, runId])
  return (
    <div className="flex flex-col gap-4" data-testid="finance-reconciliation">
      <PageHeader title={t('finance.recon.title')} description={t('finance.recon.description')} className="pb-0" />
      <Section title={t('finance.recon.runs')} testId="recon-runs">
        {more.bar}
        {query.isError
          ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
          : (
            <DataTable label={t('finance.recon.runsTable')} columns={sortable(columns)} {...table}
              getRowId={(row) => row.runId} empty={<EmptyState art="inbox" title={t('finance.recon.noRuns')} />}
              rowProps={(row) => ({ 'data-testid': 'recon-run', 'data-id': row.runId, 'data-status': row.status,
                'data-selected': String(row.runId === runId) })} />
          )}
      </Section>
      {runId && (
        <Section title={t('finance.recon.differences')} testId="recon-run">
          <Items runId={runId} />
        </Section>
      )}
    </div>
  )
}
