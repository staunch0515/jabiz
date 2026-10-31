import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  DataTable,
  EXTENSION_NAMESPACE,
  Label,
  PageHeader,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  type ColumnDef,
} from '@jabiz/admin'
import { EmptyState, LoadFailed, StatusBadge } from './components'
import { usePaged, useLabels, when, QUEUE } from './hooks'
import { DICTIONARY, PATH, SPONSOR_PAGE_STATUSES } from './ids'
import { readPendingSponsorPages, readSponsorPages, type SponsorPageRow } from './page-api'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const ALL = 'ALL'
/** The columns sorted on the server, by the template's `list.sorts` field. */
const SORTS = { company: 'company', handle: 'handle', editedAt: 'editedAt', submittedAt: 'submittedAt' } as const

function useColumns(waiting: boolean) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const statusLabel = useLabels(DICTIONARY.sponsorPageStatus)
  const languageLabel = useLabels(DICTIONARY.contentLanguage)
  return useMemo<ColumnDef<SponsorPageRow, unknown>[]>(() => [
    {
      id: 'company',
      header: t('sponsorPages.columns.company'),
      cell: ({ row }) => {
        const name = row.original.company ?? row.original.handle
        return (
          <Link to={PATH.sponsorPage(row.original.userId)} className={linkClass}
            aria-label={t('sponsorPages.open', { name })} data-testid="sponsor-page-link">{name}</Link>
        )
      },
    },
    { id: 'handle', header: t('sponsorPages.columns.handle'), cell: ({ row }) => row.original.handle },
    { id: 'headline', header: t('sponsorPages.columns.headline'), cell: ({ row }) => row.original.headline },
    { id: 'language', header: t('sponsorPages.columns.language'),
      cell: ({ row }) => languageLabel(row.original.language) },
    ...(waiting ? [] : [{ id: 'status', header: t('sponsorPages.columns.status'),
      cell: ({ row }: { row: { original: SponsorPageRow } }) => (
        <StatusBadge code={row.original.status} label={statusLabel(row.original.status)} />
      ) }]),
    waiting
      ? { id: 'submittedAt', header: t('sponsorPages.columns.submittedAt'), cell: ({ row }) => when(row.original.submittedAt) }
      : { id: 'editedAt', header: t('sponsorPages.columns.editedAt'), cell: ({ row }) => when(row.original.editedAt) },
  ], [t, statusLabel, languageLabel, waiting])
}

/** The review queue's tab of sponsors' pages (Q60a-3): those waiting, the longest waiting first. */
export function SponsorPageQueue() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { query, table, sortable } = usePaged(QUEUE.sponsorPages, readPendingSponsorPages, true, SORTS)
  const columns = useColumns(true)
  if (query.isError) return <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
  return (
    <DataTable label={t('sponsorPages.reviewsTable')} columns={sortable(columns)} {...table}
      getRowId={(row) => row.userId} empty={<EmptyState art="done" title={t('sponsorPages.nonePending')} />}
      rowProps={(row) => ({ 'data-testid': 'queue-sponsor-page', 'data-id': row.userId })} />
  )
}

/**
 * Every sponsor's page (Q60a-3; docs/quizbuks/plans/Q60-sponsor-page.md), last edited first, by status: each opens on
 * its content, its review and its takedown. An operator's session lists its own sponsors' only (the server limits
 * the template).
 */
export default function SponsorPagesPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const statusLabel = useLabels(DICTIONARY.sponsorPageStatus)
  const [status, setStatus] = useState<string>(ALL)
  const chosen = status === ALL ? null : status
  const { query, table, sortable } = usePaged(['qb-ext', 'sponsor-pages', status],
    (offset, limit, sorts) => readSponsorPages(offset, limit, sorts, chosen), true, SORTS)
  const columns = useColumns(false)
  return (
    <div className="flex flex-col gap-4" data-testid="qb-sponsor-pages">
      <PageHeader title={t('sponsorPages.list.title')} description={t('sponsorPages.list.description')} className="pb-0" />
      <div className="flex items-center gap-2">
        <Label htmlFor="sponsor-pages-status">{t('sponsorPages.list.status')}</Label>
        <Select value={status} onValueChange={setStatus}>
          <SelectTrigger id="sponsor-pages-status" className="w-48" data-testid="sponsor-pages-status">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>{t('sponsorPages.list.allStatuses')}</SelectItem>
            {SPONSOR_PAGE_STATUSES.map((code) => <SelectItem key={code} value={code}>{statusLabel(code)}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>
      {query.isError ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} /> : (
        <DataTable label={t('sponsorPages.list.table')} columns={sortable(columns)} {...table}
          getRowId={(row) => row.userId} empty={<EmptyState art="search" title={t('sponsorPages.none')} />}
          rowProps={(row) => ({ 'data-testid': 'sponsor-page-row', 'data-id': row.userId })} />
      )}
    </div>
  )
}
