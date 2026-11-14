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
  useAuth,
  UserName,
  type ColumnDef,
} from '@jabiz/admin'
import { byCheck, levelCounts } from './finance'
import { attentionKey, readAttentionDays, readReconItems, readReconRuns, type ReconItem, type ReconRun } from './finance-api'
import { EmptyState, LoadFailed, Section } from './components'
import { money, useLabels, usePaged, when } from './hooks'
import { useMoreFilters } from './moreFilters'
import { DICTIONARY, isUserId, PAGE_LIMIT, PATH, PERMISSION, reconLevel, referenceKind, type ReconLevel,
  type ReferenceKind } from './ids'

/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS = { scheduledTime: 'scheduledTime' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

/**
 * What a difference is about: what its reference names (a top-up, a transfer, a publication, a month's settlements,
 * an invoice, a credit note: `referenceKind`) and its party's summary. A link is shown only to someone who holds the
 * permission of the page it leads to (the server decides all the same; this only spares a dead link): the top-ups,
 * transfers, settlements and fees are finance's, a publication the publications' readers', a user's summary the users'.
 */
function Subject({ item }: { item: ReconItem }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const kind = referenceKind(item.checkCode)
  const parts: ReactNode[] = []
  const reference = item.reference
  if (reference && kind) {
    const target = REFERENCE_TARGET[kind](reference)
    const label = t(`finance.recon.${kind}`, { month: reference, no: reference })
    parts.push(can(target.permission)
      ? <Link key="ref" to={target.path} className={linkClass} data-testid="recon-reference" data-kind={kind}>
          {label}</Link>
      : <span key="ref" data-testid="recon-reference" data-kind={kind}>{label}</span>)
  } else if (reference) {
    parts.push(<code key="ref" className="text-xs">{reference}</code>)
  }
  if (item.partyId && isUserId(item.partyId)) {
    parts.push(can(PERMISSION.usersRead)
      ? <Link key="party" to={PATH.user(item.partyId)} className={linkClass} data-testid="recon-party">
          <UserName id={item.partyId} />
        </Link>
      : <span key="party" data-testid="recon-party"><UserName id={item.partyId} /></span>)
  }
  return parts.length > 0 ? <span className="flex flex-wrap gap-x-3">{parts}</span> : <>{t('finance.recon.whole')}</>
}

/** Where each kind of reference leads, and who may open it. */
const REFERENCE_TARGET: Record<ReferenceKind, (reference: string) => { path: string; permission: string }> = {
  topUp: (ref) => ({ path: PATH.topUpsWhere({ topUp: ref }), permission: PERMISSION.financeRead }),
  transfer: (ref) => ({ path: PATH.transfersWhere({ transfer: ref }), permission: PERMISSION.financeRead }),
  publication: (ref) => ({ path: PATH.publication(ref), permission: PERMISSION.publicationRead }),
  distributionMonth: (ref) => ({ path: PATH.distributionSettlementsOf(ref), permission: PERMISSION.financeRead }),
  exposureMonth: (ref) => ({ path: PATH.exposureSettlementsOf(ref), permission: PERMISSION.financeRead }),
  invoice: () => ({ path: PATH.feesWhere({ tab: 'invoices' }), permission: PERMISSION.financeRead }),
  creditNote: () => ({ path: PATH.feesWhere({ tab: 'creditNotes' }), permission: PERMISSION.financeRead }),
}

/** The badge variant of a level: a difference warns, the others are quieter (nothing is an error here). */
const LEVEL_VARIANT = { DIFFERENCE: 'warning', ATTENTION: 'secondary', NOTE: 'outline' } as const satisfies
  Record<ReconLevel, 'warning' | 'secondary' | 'outline'>

/**
 * One run's differences, grouped by check (the first {@link PAGE_LIMIT}: a run that finds more says so); read only
 * (C-3: a difference is handled through the money tasks and by hand).
 */
function Items({ runId }: { runId: string }) {
  const { t, i18n } = useTranslation(EXTENSION_NAMESPACE)
  const checkLabel = useLabels(DICTIONARY.reconCheck)
  const levelLabel = useLabels(DICTIONARY.reconLevel)
  const query = useQuery({ queryKey: ['qb-ext', 'finance', 'recon-items', runId],
    queryFn: () => readReconItems(runId, 0, PAGE_LIMIT) })
  // Asked for only when the run has an item for attention; a failure only leaves the days out.
  const attention = query.data?.items.some((item) => reconLevel(item.checkCode) === 'ATTENTION') ?? false
  const days = useQuery({ queryKey: ['qb-ext', 'finance', 'recon-attention-days', runId], enabled: attention,
    queryFn: () => readAttentionDays(runId) })
  if (query.isError) return <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
  if (query.isPending) return <PageState kind="loading" />
  const groups = byCheck(query.data.items)
  const total = query.data.total ?? query.data.items.length
  return (
    <div className="flex flex-col gap-4" data-testid="recon-items">
      {groups.length === 0 && <p className="text-muted-foreground text-sm">{t('finance.recon.noItems')}</p>}
      {groups.map(([check, items]) => (
        <div key={check} className="flex flex-col gap-2" data-testid="recon-group" data-check={check}>
          <h3 className="flex flex-wrap items-center gap-2 font-semibold">
            {checkLabel(check)}
            <Badge variant={LEVEL_VARIANT[reconLevel(check)]} data-testid="recon-level"
              data-level={reconLevel(check)}>{levelLabel(check)}</Badge>
          </h3>
          <p className="text-muted-foreground text-sm" data-testid="recon-help">
            {i18n.exists(`finance.recon.help.${check}`, { ns: EXTENSION_NAMESPACE })
              ? `${t(`finance.recon.help.${check}`)} ` : ''}{t('finance.recon.helpMore')}
          </p>
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
                  <TableCell>
                    <Subject item={item} />
                    {reconLevel(check) === 'ATTENTION' && days.data?.has(attentionKey(item)) && (
                      <span className="text-muted-foreground block text-xs" data-testid="recon-days">
                        {t('finance.recon.attentionDays', { count: days.data.get(attentionKey(item)) })}
                      </span>
                    )}
                  </TableCell>
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
    // F-M14 (review L10): the differences only; what needs attention beside them (notes are not counted here).
    { id: 'items', header: t('finance.recon.itemCount'), meta: { className: 'text-right tabular-nums' },
      cell: ({ row }) => {
        const counts = levelCounts(row.original.summary)
        return (
          <span data-testid="recon-run-counts">
            {counts.differences.toLocaleString()}
            {counts.attention > 0 && (
              <span className="text-muted-foreground ml-2 text-xs">
                {t('finance.recon.attentionCount', { count: counts.attention })}
              </span>
            )}
          </span>
        )
      } },
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
