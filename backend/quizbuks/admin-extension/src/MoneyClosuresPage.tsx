import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
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
  UserName,
  type ColumnDef,
} from '@jabiz/admin'
import { Download } from 'lucide-react'
import { CLOSURE_SHOWS, exportMoneyClosures, minuteNow, readMoneyClosures, type ClosureShow,
  type MoneyClosure } from './closure-api'
import { EmptyState, LoadFailed, StatusBadge } from './components'
import { tell } from './finance'
import type { ExportFormat } from './finance-api'
import { money, QUEUE, useLabels, usePaged, when } from './hooks'
import { DICTIONARY } from './ids'

const right = { className: 'text-right tabular-nums' }
const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
/** The columns sorted on the server, by the template's `list.sorts` field. */
const SORTS = { erased: 'erasedAt', closed: 'closedAt', owed: 'owed' } as const
const EXPORTS: readonly ExportFormat[] = ['csv', 'xlsx']

/**
 * F-M15 b (docs/quizbuks/plans/impl/F-M15.md §8): the wind-ups of erased Sponsors' platform fee accounts, for
 * headquarters' finance, read only: not done (the default), done with fees owed, taking long, or all (`?show=`, in the
 * address so that a link can point at it), exported whole through the platform. Whether one is taking long is the
 * template's, at the minute the page asks; the page shows what the server gives.
 */
export default function MoneyClosuresPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [search, setSearch] = useSearchParams()
  const asked = search.get('show')
  const show: ClosureShow = (CLOSURE_SHOWS as readonly string[]).includes(asked ?? '') ? asked as ClosureShow : 'OPEN'
  const [at] = useState(() => minuteNow())
  const [busy, setBusy] = useState<ExportFormat | null>(null)
  const status = useLabels(DICTIONARY.sponsorClosureStatus)
  const reason = useLabels(DICTIONARY.sponsorClosureReason)
  const { query, table, sortable } = usePaged([...QUEUE.moneyClosures, show, at],
    (offset, limit, sorts) => readMoneyClosures(show, at, offset, limit, sorts), true, SORTS)
  const columns = useMemo<ColumnDef<MoneyClosure, unknown>[]>(() => [
    { id: 'sponsor', header: t('closures.columns.sponsor'),
      cell: ({ row }) => (
        <Link to={`/qb/users/${encodeURIComponent(row.original.sponsorId)}`} className={linkClass}
          data-testid="closure-sponsor"><UserName id={row.original.sponsorId} /></Link>
      ) },
    { id: 'status', header: t('closures.columns.status'),
      cell: ({ row }) => (
        <span className="flex items-center gap-2">
          <StatusBadge code={row.original.status} label={status(row.original.status)} testId="closure-status" />
          {row.original.slow && <StatusBadge code="SLOW" label={t('closures.slow')} testId="closure-slow" />}
        </span>
      ) },
    { id: 'reason', header: t('closures.columns.reason'),
      cell: ({ row }) => (row.original.reasonCode ? reason(row.original.reasonCode) : '') },
    { id: 'erased', header: t('closures.columns.erased'), cell: ({ row }) => when(row.original.erasedAt) },
    { id: 'lastRun', header: t('closures.columns.lastRun'), cell: ({ row }) => when(row.original.lastRunAt) },
    { id: 'closed', header: t('closures.columns.closed'), cell: ({ row }) => when(row.original.closedAt) },
    { id: 'owed', header: t('closures.columns.owed'), meta: right, cell: ({ row }) => money(row.original.owed) },
  ], [t, status, reason])
  const download = async (format: ExportFormat) => {
    setBusy(format)
    try {
      await exportMoneyClosures(show, at, format)
    } catch (error) {
      tell(error, t('closures.exportFailed'))
    } finally {
      setBusy(null)
    }
  }
  return (
    <div className="flex flex-col gap-4" data-testid="qb-money-closures">
      <PageHeader title={t('closures.title')} description={t('closures.description')} className="pb-0" />
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1">
          <Label htmlFor="closures-show">{t('closures.filter')}</Label>
          <Select value={show} onValueChange={(value) => setSearch({ show: value }, { replace: true })}>
            <SelectTrigger id="closures-show" className="w-52" data-testid="closures-show"><SelectValue /></SelectTrigger>
            <SelectContent>
              {CLOSURE_SHOWS.map((code) => (
                <SelectItem key={code} value={code}>{t(`closures.show.${code}`)}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-wrap gap-2">
          {EXPORTS.map((format) => (
            <Button key={format} variant="outline" size="sm" disabled={busy !== null}
              aria-busy={busy === format || undefined} data-testid={`closures-export-${format}`}
              onClick={() => void download(format)}>
              <Download aria-hidden />{t(format === 'csv' ? 'closures.exportCsv' : 'closures.exportXlsx')}
            </Button>
          ))}
        </div>
      </div>
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : <DataTable label={t('closures.table')} columns={sortable(columns)} {...table}
          getRowId={(row) => row.sponsorId}
          empty={<EmptyState art="money" title={t('closures.none')} />}
          rowProps={(row) => ({ 'data-testid': 'closure-row', 'data-sponsor': row.sponsorId })} />}
    </div>
  )
}
