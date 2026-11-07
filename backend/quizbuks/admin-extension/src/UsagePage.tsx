import { useQuery } from '@tanstack/react-query'
import { useId, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Button,
  DataTable,
  EXTENSION_NAMESPACE,
  Input,
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
import { readDictionary } from './api'
import { EmptyState, LoadFailed } from './components'
import { tell } from './finance'
import type { ExportFormat } from './finance-api'
import { useLabels, usePaged } from './hooks'
import { DICTIONARY, isByteMeter, PATH } from './ids'
import { exportUsage, formatUsage, monthOf, readUsage, validRange, type UsageFilter, type UsageRow } from './usage-api'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }
const ALL = 'ALL'
/** The columns sorted on the server, by the template's `list.sorts` field. */
const SORTS = { sponsor: 'company', month: 'month', meter: 'meter', average: 'average', peak: 'peak' } as const
const EXPORTS: readonly ExportFormat[] = ['csv', 'xlsx']

/**
 * The sponsors' usage by month (F-C2, docs/quizbuks/plans/impl/F-C2.md §8): for each sponsor, month and meter of the
 * daily snapshots, the days recorded, the average, the peak and the last value (what charging will be based on later;
 * nothing is charged in the first launch), for a range of months, one meter and one sponsor (`?sponsor=`, from the
 * sponsor's page) when chosen; exported whole through the platform. Read only. Headquarters sees every sponsor, an
 * operator's members their own (the server limits the template, `OperatorScopes`).
 */
export default function UsagePage() {
  const { t, i18n } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const [search, setSearch] = useSearchParams()
  const sponsorId = search.get('sponsor')
  const [from, setFrom] = useState(() => monthOf())
  const [to, setTo] = useState(() => monthOf())
  const [meter, setMeter] = useState<string>(ALL)
  const [busy, setBusy] = useState<ExportFormat | null>(null)
  const meterLabel = useLabels(DICTIONARY.usageMeter)
  const meters = useQuery({ queryKey: ['qb-ext', 'dictionary-items', DICTIONARY.usageMeter, i18n.language],
    queryFn: () => readDictionary(DICTIONARY.usageMeter), staleTime: 5 * 60_000 })
  const filter: UsageFilter = { from, to, sponsorId, meter: meter === ALL ? null : meter }
  const valid = validRange(filter)
  const { query, table, sortable } = usePaged(['qb-ext', 'usage', filter],
    (offset, limit, sorts) => readUsage(filter, offset, limit, sorts), valid, SORTS)
  const columns = useMemo<ColumnDef<UsageRow, unknown>[]>(() => {
    const value = (row: UsageRow, figure: 'average' | 'peak' | 'latest') =>
      formatUsage(row[figure], isByteMeter(row.meter), i18n.language)
    return [
      { id: 'sponsor', header: t('usage.columns.sponsor'),
        cell: ({ row }) => (
          <Link to={PATH.sponsor(row.original.sponsorId)} className={linkClass} data-testid="usage-sponsor">
            {row.original.company ?? <UserName id={row.original.sponsorId} />}
          </Link>
        ) },
      { id: 'month', header: t('usage.columns.month'), cell: ({ row }) => row.original.month },
      { id: 'meter', header: t('usage.columns.meter'), cell: ({ row }) => meterLabel(row.original.meter) },
      { id: 'days', header: t('usage.columns.days'), meta: right, cell: ({ row }) => row.original.days },
      { id: 'average', header: t('usage.columns.average'), meta: right,
        cell: ({ row }) => value(row.original, 'average') },
      { id: 'peak', header: t('usage.columns.peak'), meta: right, cell: ({ row }) => value(row.original, 'peak') },
      { id: 'latest', header: t('usage.columns.latest'), meta: right,
        cell: ({ row }) => value(row.original, 'latest') },
    ]
  }, [t, i18n.language, meterLabel])
  const download = async (format: ExportFormat) => {
    setBusy(format)
    try {
      await exportUsage(filter, format)
    } catch (error) {
      tell(error, t('usage.exportFailed'))
    } finally {
      setBusy(null)
    }
  }
  return (
    <div className="flex flex-col gap-4" data-testid="qb-usage">
      <PageHeader title={t('usage.title')} description={t('usage.description')} className="pb-0" />
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1">
          <Label htmlFor={`${id}-from`}>{t('usage.from')}</Label>
          <Input id={`${id}-from`} type="month" className="w-44" value={from} data-testid="usage-from"
            onChange={(event) => setFrom(event.target.value)} />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor={`${id}-to`}>{t('usage.to')}</Label>
          <Input id={`${id}-to`} type="month" className="w-44" value={to} data-testid="usage-to"
            onChange={(event) => setTo(event.target.value)} />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor={`${id}-meter`}>{t('usage.meter')}</Label>
          <Select value={meter} onValueChange={setMeter}>
            <SelectTrigger id={`${id}-meter`} className="w-52" data-testid="usage-meter"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL}>{t('usage.allMeters')}</SelectItem>
              {(meters.data ?? []).map((item) => <SelectItem key={item.code} value={item.code}>{item.label}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-wrap gap-2">
          {EXPORTS.map((format) => (
            <Button key={format} variant="outline" size="sm" disabled={!valid || busy !== null}
              aria-busy={busy === format || undefined} data-testid={`usage-export-${format}`}
              onClick={() => void download(format)}>
              <Download aria-hidden />{t(`usage.export.${format}`)}
            </Button>
          ))}
        </div>
      </div>
      {sponsorId && (
        <p className="flex flex-wrap items-center gap-2 text-sm" data-testid="usage-one-sponsor">
          <span className="text-muted-foreground">{t('usage.allSponsors')}</span>
          <Button variant="link" size="sm" className="h-auto p-0" data-testid="usage-show-all" onClick={() => {
            const next = new URLSearchParams(search)
            next.delete('sponsor')
            setSearch(next, { replace: true })
          }}>{t('usage.showAll')}</Button>
        </p>
      )}
      {!valid ? <p className="text-destructive text-sm" role="alert" data-testid="usage-invalid">{t('usage.invalidRange')}</p>
        : query.isError ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} /> : (
          <DataTable label={t('usage.table')} columns={sortable(columns)} {...table}
            getRowId={(row) => `${row.sponsorId}:${row.month}:${row.meter}`}
            empty={<EmptyState art="search" title={t('usage.none')} />}
            rowProps={(row) => ({ 'data-testid': 'usage-row', 'data-sponsor': row.sponsorId, 'data-month': row.month,
              'data-meter': row.meter })} />
        )}
    </div>
  )
}
