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
  UserName,
  paths,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { EmptyState, LoadFailed, StatusBadge } from './components'
import { QUEUE, usePaged, when } from './hooks'
import { PATH, PERMISSION, REGION_PROCESS } from './ids'
import { readRefusalDays, readRegionExceptions, type RegionException } from './region-api'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const IN_FORCE = 'IN_FORCE'
const ALL = 'ALL'
/** The columns sorted on the server, by the template's `list.sorts` field. */
const SORTS = { added: 'createdAt', expiresOn: 'expiresOn' } as const
/** How many days of refused starts the page shows. */
const REFUSAL_DAYS = 30

/** The UTC date `days` before today. */
function utcDaysAgo(days: number): string {
  return new Date(Date.now() - days * 86_400_000).toISOString().slice(0, 10)
}

/** The starts the rule refused by day: counts only. */
function Refusals() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [from] = useState(() => utcDaysAgo(REFUSAL_DAYS - 1))
  const days = useQuery({ queryKey: [...QUEUE.region, 'refusals', from], queryFn: () => readRefusalDays(from) })
  const columns = useMemo<ColumnDef<{ day: string; refused: number }, unknown>[]>(() => [
    { id: 'day', header: t('region.day'), cell: ({ row }) => row.original.day },
    { id: 'refused', header: t('region.refused'), meta: { className: 'tabular-nums' },
      cell: ({ row }) => row.original.refused },
  ], [t])
  return (
    <section className="flex flex-col gap-2" aria-labelledby="region-refusals-title" data-testid="region-refusals">
      <h2 id="region-refusals-title" className="font-semibold">{t('region.refusals')}</h2>
      <p className="text-muted-foreground text-sm">{t('region.refusalsHint')}</p>
      {days.isError
        ? <LoadFailed error={days.error} onRetry={() => void days.refetch()} />
        : <DataTable label={t('region.refusalsTable')} columns={columns} data={days.data ?? []}
          loading={days.isPending} getRowId={(row) => row.day}
          empty={<EmptyState art="search" title={t('region.noRefusals')} />}
          rowProps={(row) => ({ 'data-testid': 'region-refusal-row', 'data-day': row.day })} />}
    </section>
  )
}

/**
 * Foreign videos and mainland China (F-S11, A3-15; docs/quizbuks/plans/impl/F-S11.md): headquarters' exceptions,
 * those in force by default, each with its reason, last day and state; adding one, changing its last day and revoking
 * it are the platform's process pages (with the exception filled in), where the reason is required and every change is
 * audited. Below, the starts the rule refused by day. The daily check of the address database links its task here.
 */
export default function RegionPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const write = can(PERMISSION.regionWrite)
  const [show, setShow] = useState<string>(IN_FORCE)
  // The day that decides what is in force: the page's opening (the server compares by the UTC date).
  const [at] = useState(() => new Date().toISOString())
  const inForce = show === IN_FORCE ? true : null
  const { query, table, sortable } = usePaged([...QUEUE.region, show, at],
    (offset, limit, sorts) => readRegionExceptions(offset, limit, sorts, inForce, at), true, SORTS)
  const columns = useMemo<ColumnDef<RegionException, unknown>[]>(() => {
    const link = (process: string, exceptionId: string) =>
      `${paths.process(process, REGION_PROCESS.version)}?${new URLSearchParams({ exceptionId })}`
    const nameOf = (row: RegionException) => row.publicationName ?? row.publicationId ?? row.sponsorId ?? ''
    return [
      {
        id: 'target',
        header: t('region.columns.target'),
        cell: ({ row }) => (
          <span className="flex flex-col gap-0.5">
            <span className="text-muted-foreground text-xs">{t(`region.kinds.${row.original.kind}`)}</span>
            {row.original.kind === 'PUBLICATION' && row.original.publicationId
              ? <Link to={PATH.publication(row.original.publicationId)} className={linkClass}
                data-testid="region-publication">{nameOf(row.original)}</Link>
              : row.original.sponsorId && <UserName id={row.original.sponsorId} />}
            {row.original.foreignVideo === false && (
              <span className="text-muted-foreground text-xs">{t('region.noForeignVideo')}</span>
            )}
          </span>
        ),
      },
      // Plain text: what an administrator wrote is never rendered as markup.
      { id: 'reason', header: t('region.columns.reason'),
        cell: ({ row }) => <span className="text-sm whitespace-pre-wrap">{row.original.reason}</span> },
      { id: 'expiresOn', header: t('region.columns.expiresOn'),
        cell: ({ row }) => row.original.expiresOn ?? t('region.noExpiry') },
      {
        id: 'state',
        header: t('region.columns.state'),
        cell: ({ row }) => {
          const state = row.original.revoked ? 'revoked' : row.original.inForce ? 'inForce' : 'expired'
          return (
            <span className="flex flex-col items-start gap-1">
              <StatusBadge code={state === 'inForce' ? 'ACTIVE' : 'ENDED'} label={t(`region.states.${state}`)}
                testId="region-state" />
              {row.original.revokeReason && (
                <span className="text-muted-foreground text-xs whitespace-pre-wrap">
                  {t('region.revokedBecause', { reason: row.original.revokeReason })}</span>
              )}
            </span>
          )
        },
      },
      {
        id: 'added',
        header: t('region.columns.added'),
        cell: ({ row }) => (
          <span className="flex flex-col text-xs">
            <span>{when(row.original.createdAt)}</span>
            {row.original.createdBy && <UserName id={row.original.createdBy} />}
          </span>
        ),
      },
      {
        id: 'actions',
        header: t('region.columns.actions'),
        cell: ({ row }) => write && !row.original.revoked && (
          <span className="flex flex-col items-start gap-1">
            <Link to={link(REGION_PROCESS.expirySet, row.original.exceptionId)} className={linkClass}
              aria-label={t('region.expiryOf', { name: nameOf(row.original) })} data-testid="region-expiry">
              {t('region.expiry')}</Link>
            <Link to={link(REGION_PROCESS.revoke, row.original.exceptionId)} className={linkClass}
              aria-label={t('region.revokeOf', { name: nameOf(row.original) })} data-testid="region-revoke">
              {t('region.revoke')}</Link>
          </span>
        ),
      },
    ]
  }, [t, write])
  return (
    <div className="flex flex-col gap-4" data-testid="qb-region">
      <PageHeader title={t('region.title')} description={t('region.description')} className="pb-0"
        actions={write && (
          <Button asChild>
            <Link to={paths.process(REGION_PROCESS.add, REGION_PROCESS.version)} data-testid="region-add">
              {t('region.add')}</Link>
          </Button>
        )} />
      <div className="flex items-center gap-2">
        <Label htmlFor="region-show">{t('region.filter')}</Label>
        <Select value={show} onValueChange={setShow}>
          <SelectTrigger id="region-show" className="w-56" data-testid="region-show"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value={IN_FORCE}>{t('region.inForce')}</SelectItem>
            <SelectItem value={ALL}>{t('region.all')}</SelectItem>
          </SelectContent>
        </Select>
      </div>
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : <DataTable label={t('region.table')} columns={sortable(columns)} {...table}
          getRowId={(row) => row.exceptionId} empty={<EmptyState art="search" title={t('region.none')} />}
          rowProps={(row) => ({ 'data-testid': 'region-row', 'data-id': row.exceptionId })} />}
      <Refusals />
    </div>
  )
}
