import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Download, Stamp } from 'lucide-react'
import { useId, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  Badge,
  Button,
  ConfirmDialog,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  notify,
  PageHeader,
  PageState,
  paths,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  useAuth,
} from '@jabiz/admin'
import {
  EXPORT_FORMATS,
  exportReport,
  issueReport,
  monthPeriod,
  previousMonth,
  readBalances,
  readIssuedRuns,
  readMonthly,
  type ExportFormat,
  type Period,
} from './finance-api'
import { failed, tell } from './finance'
import { LoadFailed, Section } from './components'
import { money, when } from './hooks'
import { ACCOUNTS, PERMISSION, TEMPLATE, type ReportId } from './ids'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = 'text-right tabular-nums'

/** The export, issue and archive of one report for the period; each through the platform. */
function ReportActions({ id, period }: { id: ReportId; period: Period }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState<ExportFormat | null>(null)
  const download = async (format: ExportFormat) => {
    setBusy(format)
    try {
      await exportReport(id, period, format)
    } catch (error) {
      tell(error, t('finance.reports.exportFailed'))
    } finally {
      setBusy(null)
    }
  }
  return (
    <div className="flex flex-wrap items-center gap-2">
      {EXPORT_FORMATS.map((format) => (
        <Button key={format} variant="outline" size="sm" disabled={busy !== null} aria-busy={busy === format || undefined}
          data-testid={`export-${format}`} onClick={() => void download(format)}>
          <Download aria-hidden />
          {t(`finance.reports.export.${format}`)}
        </Button>
      ))}
      {can(PERMISSION.reportIssue) && (
        <ConfirmDialog
          title={t('finance.reports.issueTitle')}
          description={t('finance.reports.issueDescription')}
          confirmLabel={t('finance.reports.issue')}
          onConfirm={async () => {
            try {
              const issued = await issueReport(id, period)
              notify.success(t('finance.reports.issued', { hash: issued.contentHash.slice(0, 12) }))
            } catch (error) {
              failed(error, t('common.failed'))
            }
            await queryClient.invalidateQueries({ queryKey: ['qb-ext', 'finance', 'issued', id] })
          }}
          trigger={<Button size="sm" data-testid="report-issue"><Stamp aria-hidden />{t('finance.reports.issue')}</Button>}
        />
      )}
    </div>
  )
}

/** The latest issued runs of a report (the platform's archive; `report.archive.read`). */
function Issued({ id }: { id: ReportId }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const runs = useQuery({ queryKey: ['qb-ext', 'finance', 'issued', id], queryFn: () => readIssuedRuns(id) })
  let body: ReactNode
  if (runs.isError) body = <LoadFailed error={runs.error} onRetry={() => void runs.refetch()} />
  else if (runs.isPending) body = <PageState kind="loading" />
  else if (runs.data.length === 0) body = <p className="text-muted-foreground text-sm">{t('finance.reports.noneIssued')}</p>
  else {
    body = (
      <Table aria-label={t('finance.reports.issuedTable')} data-testid="issued-runs">
        <TableHeader>
          <TableRow>
            <TableHead>{t('finance.reports.issuedTime')}</TableHead>
            <TableHead>{t('finance.reports.period')}</TableHead>
            <TableHead className="text-right">{t('finance.reports.rows')}</TableHead>
            <TableHead>{t('finance.reports.hash')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {runs.data.map((run) => (
            <TableRow key={run.runId} data-testid="issued-run" data-hash={run.contentHash}>
              <TableCell>{when(run.issuedTime)}</TableCell>
              <TableCell>{run.period ?? ''}</TableCell>
              <TableCell className={right}>{run.rowCount.toLocaleString()}</TableCell>
              <TableCell>
                <code className="text-xs">{run.contentHash.slice(0, 12)}</code>
                {run.supersededBy && <Badge variant="outline" className="ml-2">{t('finance.reports.superseded')}</Badge>}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    )
  }
  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="font-semibold">{t('finance.reports.issuedTitle')}</h3>
        <Link to={paths.reportArchive(id)} className={linkClass} data-testid="report-archive-link">
          {t('finance.reports.archive')}
        </Link>
      </div>
      {body}
    </div>
  )
}

function Monthly({ period }: { period: Period }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const rows = useQuery({ queryKey: ['qb-ext', 'finance', 'monthly', period], queryFn: () => readMonthly(period) })
  let body: ReactNode
  if (rows.isError) body = <LoadFailed error={rows.error} onRetry={() => void rows.refetch()} />
  else if (rows.isPending) body = <PageState kind="loading" />
  else {
    body = (
      <Table aria-label={t('finance.reports.monthlyTable')} data-testid="monthly-table">
        <TableHeader>
          <TableRow>
            <TableHead>{t('finance.reports.category')}</TableHead>
            <TableHead className="text-right">{t('finance.reports.count')}</TableHead>
            <TableHead className="text-right">{t('finance.reports.amount')}</TableHead>
            {ACCOUNTS.map((code) => <TableHead key={code} className="text-right">{code}</TableHead>)}
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.data.map((row) => (
            <TableRow key={row.category} data-testid="monthly-row" data-category={row.category}
              data-count={row.transactionCount} data-amount={row.amount}>
              <TableCell>{t(`finance.category.${row.category}`, { defaultValue: row.category })}</TableCell>
              <TableCell className={right}>{row.transactionCount.toLocaleString()}</TableCell>
              <TableCell className={right}>{money(row.amount)}</TableCell>
              {ACCOUNTS.map((code) => <TableCell key={code} className={right}>{money(row.moves[code])}</TableCell>)}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    )
  }
  return (
    <Section title={t('finance.reports.monthly')} testId="report-monthly"
      actions={<ReportActions id={TEMPLATE.monthly} period={period} />}>
      <p className="text-muted-foreground text-sm">{t('finance.reports.monthlyHint')}</p>
      {body}
      {can(PERMISSION.reportArchiveRead) && <Issued id={TEMPLATE.monthly} />}
    </Section>
  )
}

function Balances({ period }: { period: Period }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const rows = useQuery({ queryKey: ['qb-ext', 'finance', 'balances', period], queryFn: () => readBalances(period) })
  let body: ReactNode
  if (rows.isError) body = <LoadFailed error={rows.error} onRetry={() => void rows.refetch()} />
  else if (rows.isPending) body = <PageState kind="loading" />
  else {
    body = (
      <Table aria-label={t('finance.reports.balancesTable')} data-testid="balances-table">
        <TableHeader>
          <TableRow>
            <TableHead>{t('finance.reports.account')}</TableHead>
            <TableHead className="text-right">{t('finance.reports.opening')}</TableHead>
            <TableHead className="text-right">{t('finance.reports.debit')}</TableHead>
            <TableHead className="text-right">{t('finance.reports.credit')}</TableHead>
            <TableHead className="text-right">{t('finance.reports.closing')}</TableHead>
            <TableHead className="text-right">{t('finance.reports.partyTotal')}</TableHead>
            <TableHead>{t('finance.reports.matches')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.data.map((row) => (
            <TableRow key={`${row.rowKind}-${row.code}`} data-testid="balance-row" data-kind={row.rowKind}
              data-code={row.code} data-matches={row.matches == null ? undefined : String(row.matches)}>
              <TableCell>
                {row.rowKind === 'CHECK'
                  ? t(`finance.reports.check.${row.code}`, { defaultValue: row.name })
                  : `${row.code} ${row.name}`}
              </TableCell>
              <TableCell className={right}>{money(row.opening)}</TableCell>
              <TableCell className={right}>{money(row.debit)}</TableCell>
              <TableCell className={right}>{money(row.credit)}</TableCell>
              <TableCell className={right}>{money(row.closing)}</TableCell>
              <TableCell className={right}>{row.partyTotal == null ? '' : money(row.partyTotal)}</TableCell>
              <TableCell>
                {row.matches == null ? '' : row.matches
                  ? <Badge variant="outline">{t('finance.reports.agrees')}</Badge>
                  : <Badge variant="destructive">{t('finance.reports.differs')}</Badge>}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    )
  }
  return (
    <Section title={t('finance.reports.balances')} testId="report-balances"
      actions={<ReportActions id={TEMPLATE.balances} period={period} />}>
      <p className="text-muted-foreground text-sm">{t('finance.reports.balancesHint')}</p>
      {body}
      {can(PERMISSION.reportArchiveRead) && <Issued id={TEMPLATE.balances} />}
    </Section>
  )
}

/**
 * The monthly reports (F-08): for a month (UTC, the reports' zone), the transactions by kind and the balance sheet,
 * both from the ledger alone by booking time. Each is exported (CSV, Excel, PDF) and issued through the platform:
 * an issued report is archived with its content hash and reproduced from the archive, never from today's data.
 */
export default function ReportsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const monthId = useId()
  const [month, setMonth] = useState(previousMonth)
  const period = monthPeriod(month)
  return (
    <div className="flex flex-col gap-4" data-testid="finance-reports">
      <PageHeader title={t('finance.reports.title')} description={t('finance.reports.description')} className="pb-0" />
      <div className="flex flex-wrap items-end gap-4">
        <div className="flex flex-col gap-1">
          <Label htmlFor={monthId}>{t('finance.reports.month')}</Label>
          <Input id={monthId} type="month" className="w-48" value={month} data-testid="report-month"
            onChange={(event) => setMonth(event.target.value)} />
        </div>
        {period && (
          <p className="text-muted-foreground pb-2 text-sm" data-testid="report-period">
            {t('finance.reports.periodValue', { from: period.from.slice(0, 10), to: period.to.slice(0, 10) })}
          </p>
        )}
      </div>
      {period
        ? (
          <>
            <Monthly period={period} />
            <Balances period={period} />
          </>
        )
        : <PageState kind="warning" description={t('finance.reports.noMonth')} />}
    </div>
  )
}
