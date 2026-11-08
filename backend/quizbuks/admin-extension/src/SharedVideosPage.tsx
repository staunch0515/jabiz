import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
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
  type ColumnDef,
} from '@jabiz/admin'
import { EmptyState, LoadFailed, StatusBadge } from './components'
import { QUEUE, usePaged, when } from './hooks'
import { PATH } from './ids'
import { operatorColumn, useOperatorFilter } from './operator-filter'
import {
  duration,
  readSharedVideos,
  SHARED_VIDEO_STATUSES,
  titleIn,
  type AdminSharedVideo,
  type SharedVideoStatus,
} from './shared-video-api'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const ALL = 'ALL'
/** The columns sorted on the server, by the template's `list.sorts` field. */
const SORTS = { shared: 'sharedAt' } as const

/**
 * The videos Sponsors share (F-S12; docs/quizbuks/plans/impl/F-S12.md): those in review first to hand, each opened for
 * its review (the platform's approval panel) or its takedown. One page for both management systems: an operator's
 * session reads its own Sponsors' (the server's scope) and reviews and takes them down; the platform's reads every
 * operator's, filtered by operator, and takes down in an emergency.
 */
export default function SharedVideosPage() {
  const { t, i18n } = useTranslation(EXTENSION_NAMESPACE)
  const [show, setShow] = useState<string>('PENDING')
  const [search, setSearch] = useState('')
  const status = show === ALL ? null : show as SharedVideoStatus
  const operators = useOperatorFilter('shared-videos-operator-filter')
  const { query, table, sortable } = usePaged([...QUEUE.sharedVideos, show, search, operators.filters],
    (offset, limit, sorts) => readSharedVideos(offset, limit, sorts, status, search, operators.filters), true, SORTS)
  const columns = useMemo<ColumnDef<AdminSharedVideo, unknown>[]>(() => [
    {
      id: 'title',
      header: t('sharedVideos.columns.title'),
      cell: ({ row }) => (
        <span className="flex flex-col gap-0.5">
          <Link to={PATH.sharedVideo(row.original.sharedVideoId)} className={linkClass}
            data-testid="shared-video-link">{titleIn(row.original.titles, i18n.language)}</Link>
          <span className="text-muted-foreground text-xs">
            {t(`sharedVideos.orientation.${row.original.vertical ? 'vertical' : 'horizontal'}`)}
            {' · '}{duration(row.original.durationSec)}</span>
        </span>
      ),
    },
    { id: 'provider', header: t('sharedVideos.columns.provider'),
      cell: ({ row }) => row.original.company ?? row.original.providerId },
    ...(operators.across ? [operatorColumn<AdminSharedVideo>(t('pickers.operatorColumn'))] : []),
    { id: 'status', header: t('sharedVideos.columns.status'),
      cell: ({ row }) => <StatusBadge code={row.original.status} label={t(`sharedVideos.status.${row.original.status}`)}
        testId="shared-video-status" /> },
    { id: 'uses', header: t('sharedVideos.columns.uses'), meta: { className: 'tabular-nums' },
      cell: ({ row }) => row.original.usesRunning },
    { id: 'shared', header: t('sharedVideos.columns.shared'), cell: ({ row }) => when(row.original.sharedAt) },
  ], [t, i18n.language, operators.across])
  return (
    <div className="flex flex-col gap-4" data-testid="qb-shared-videos">
      <PageHeader title={t('sharedVideos.title')} description={t('sharedVideos.description')} className="pb-0" />
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1">
          <Label htmlFor="shared-videos-show">{t('sharedVideos.filter')}</Label>
          <Select value={show} onValueChange={setShow}>
            <SelectTrigger id="shared-videos-show" className="w-48" data-testid="shared-videos-show"><SelectValue /></SelectTrigger>
            <SelectContent>
              {SHARED_VIDEO_STATUSES.map((code) => (
                <SelectItem key={code} value={code}>{t(`sharedVideos.status.${code}`)}</SelectItem>
              ))}
              <SelectItem value={ALL}>{t('sharedVideos.all')}</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="shared-videos-search">{t('sharedVideos.search')}</Label>
          <Input id="shared-videos-search" type="search" value={search} className="w-64" autoComplete="off"
            placeholder={t('sharedVideos.searchPlaceholder')} data-testid="shared-videos-search"
            onChange={(event) => setSearch(event.target.value)} />
        </div>
      </div>
      {operators.bar}
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : <DataTable label={t('sharedVideos.table')} columns={sortable(columns)} {...table}
          getRowId={(row) => row.sharedVideoId} empty={<EmptyState art="search" title={t('sharedVideos.none')} />}
          rowProps={(row) => ({ 'data-testid': 'shared-video-row', 'data-id': row.sharedVideoId })} />}
    </div>
  )
}
