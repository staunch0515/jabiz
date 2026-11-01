import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  Button,
  DataTable,
  EXTENSION_NAMESPACE,
  Label,
  PageHeader,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  paths,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { EmptyState, LoadFailed, StatusBadge } from './components'
import { QUEUE, usePaged, useLabels, when } from './hooks'
import { DICTIONARY, PATH, PERMISSION, REPORT_PROCESS } from './ids'
import { readReportedPublications, readReports, type ReportedPublication } from './report-api'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const OPEN = 'OPEN'
const CLOSED = 'CLOSED'
const ALL = 'ALL'
/** The columns sorted on the server, by the template's `list.sorts` field. */
const SORTS = { quiz: 'name', open: 'openCount', last: 'lastReportedAt' } as const
/** The reasons, as the template counts them. */
const REASONS = [
  ['MISLEADING', 'misleading'],
  ['INAPPROPRIATE', 'inappropriate'],
  ['REWARD_NOT_GIVEN', 'rewardNotGiven'],
  ['SPAM', 'spam'],
  ['OTHER', 'other'],
] as const

/** The process that closes a publication's reports for the caller: headquarters', or the operator's; none else. */
function useResolve(): string | null {
  const { can } = useAuth()
  if (can(PERMISSION.contentWrite)) return REPORT_PROCESS.resolve
  if (can(PERMISSION.operatorUse)) return REPORT_PROCESS.operatorResolve
  return null
}

/** One publication's reports, the latest first: reason, note and state. */
function Reports({ publication }: { publication: ReportedPublication }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const reason = useLabels(DICTIONARY.reportReason)
  const status = useLabels(DICTIONARY.reportStatus)
  const reports = useQuery({ queryKey: [...QUEUE.abuseReports, 'of', publication.publicationId],
    queryFn: () => readReports(publication.publicationId) })
  if (reports.isError) return <LoadFailed error={reports.error} onRetry={() => void reports.refetch()} />
  return (
    <section className="bg-card flex flex-col gap-3 rounded-lg border p-4" aria-labelledby="report-detail-title"
      data-testid="report-detail">
      <h2 id="report-detail-title" className="font-semibold">
        {t('abuseReports.reportsOf', { name: publication.name ?? publication.publicationId })}</h2>
      <ul className="divide-border flex flex-col divide-y">
        {(reports.data ?? []).map((report) => (
          <li key={report.reportId} className="flex flex-col gap-1 py-2" data-testid="report-item">
            <span className="flex flex-wrap items-center gap-2 text-sm">
              <StatusBadge code={report.status} label={status(report.status)} />
              <span className="font-medium">{reason(report.reason)}</span>
              <span className="text-muted-foreground text-xs">{when(report.createdAt)}</span>
            </span>
            {/* Plain text: what a taker wrote is never rendered as markup. */}
            <span className="text-sm whitespace-pre-wrap">{report.note ?? t('abuseReports.noNote')}</span>
            {report.resolution && (
              <span className="text-muted-foreground text-xs">
                {t('abuseReports.resolution', { note: report.resolution })}</span>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}

/**
 * Takers' reports (Q49a; docs/quizbuks/plans/Q49a-contest-report.md): the reported quizzes, those with open reports by
 * default, each with its reports counted by reason; opening one lists its reports. Closing them (dismissed or acted
 * on) is the platform's process page with the quiz filled in and a second factor; pausing or ending the quiz is its
 * own page. An operator's session lists its own sponsors' only (the server limits the template).
 */
export default function AbuseReportsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const reason = useLabels(DICTIONARY.reportReason)
  const resolve = useResolve()
  const [show, setShow] = useState<string>(OPEN)
  const [opened, setOpened] = useState<ReportedPublication | null>(null)
  const open = show === OPEN ? true : show === CLOSED ? false : null
  const { query, table, sortable } = usePaged([...QUEUE.abuseReports, show],
    (offset, limit, sorts) => readReportedPublications(offset, limit, sorts, open), true, SORTS)
  const columns = useMemo<ColumnDef<ReportedPublication, unknown>[]>(() => {
    const link = (process: string, outcome: string, publicationId: string) =>
      `${paths.process(process, REPORT_PROCESS.version)}?${new URLSearchParams({ publicationId, outcome })}`
    return [
      {
        id: 'quiz',
        header: t('abuseReports.columns.quiz'),
        cell: ({ row }) => (
          <Link to={PATH.publication(row.original.publicationId)} className={linkClass} data-testid="report-quiz">
            {row.original.name ?? row.original.publicationId}</Link>
        ),
      },
      { id: 'sponsor', header: t('abuseReports.columns.sponsor'),
        cell: ({ row }) => row.original.company ?? row.original.publisherId },
      { id: 'open', header: t('abuseReports.columns.open'), meta: { className: 'tabular-nums' },
        cell: ({ row }) => t('abuseReports.count', { open: row.original.openCount, total: row.original.totalCount }) },
      {
        id: 'reasons',
        header: t('abuseReports.columns.reasons'),
        cell: ({ row }) => (
          <span className="flex flex-col text-xs">
            {REASONS.filter(([, field]) => row.original[field] > 0).map(([code, field]) => (
              <span key={code}>{t('abuseReports.reason', { label: reason(code), count: row.original[field] })}</span>
            ))}
          </span>
        ),
      },
      { id: 'last', header: t('abuseReports.columns.last'), cell: ({ row }) => when(row.original.lastReportedAt) },
      {
        id: 'actions',
        header: t('abuseReports.columns.actions'),
        cell: ({ row }) => {
          const name = row.original.name ?? row.original.publicationId
          return (
            <span className="flex flex-col items-start gap-1">
              <Button variant="link" className="h-auto p-0" onClick={() => setOpened(row.original)}
                aria-label={t('abuseReports.reportsOf', { name })} data-testid="report-open">
                {t('abuseReports.show')}</Button>
              {resolve && row.original.openCount > 0 && (
                <>
                  <Link to={link(resolve, 'DISMISSED', row.original.publicationId)} className={linkClass}
                    aria-label={t('abuseReports.dismissOf', { name })} data-testid="report-dismiss">
                    {t('abuseReports.dismiss')}</Link>
                  <Link to={link(resolve, 'ACTIONED', row.original.publicationId)} className={linkClass}
                    aria-label={t('abuseReports.actionedOf', { name })} data-testid="report-actioned">
                    {t('abuseReports.actioned')}</Link>
                </>
              )}
            </span>
          )
        },
      },
    ]
  }, [t, reason, resolve])
  return (
    <div className="flex flex-col gap-4" data-testid="qb-abuse-reports">
      <PageHeader title={t('abuseReports.title')} description={t('abuseReports.description')} className="pb-0" />
      <div className="flex items-center gap-2">
        <Label htmlFor="reports-show">{t('abuseReports.filter')}</Label>
        <Select value={show} onValueChange={(value) => { setShow(value); setOpened(null) }}>
          <SelectTrigger id="reports-show" className="w-56" data-testid="reports-show"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value={OPEN}>{t('abuseReports.allOpen')}</SelectItem>
            <SelectItem value={CLOSED}>{t('abuseReports.allClosed')}</SelectItem>
            <SelectItem value={ALL}>{t('abuseReports.all')}</SelectItem>
          </SelectContent>
        </Select>
      </div>
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : <DataTable label={t('abuseReports.table')} columns={sortable(columns)} {...table}
          getRowId={(row) => row.publicationId} empty={<EmptyState art="search" title={t('abuseReports.none')} />}
          rowProps={(row) => ({ 'data-testid': 'report-row', 'data-id': row.publicationId })} />}
      {opened && <Reports key={opened.publicationId} publication={opened} />}
    </div>
  )
}
