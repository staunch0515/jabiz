import { formatDateTime } from '@jabiz/client'
import {
  Badge,
  Button,
  DataTable,
  Input,
  Label,
  PageHeader,
  PageState,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  type ColumnDef,
  type PaginationState,
  type SortingState,
} from '@jabiz/ui'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { useId, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { Link, navigate } from '../qb/router'
import { PUBLICATION_STATUSES, PUBLISHING_LIMITS, searchPublications, type PublicationRow,
  type PublicationStatus } from './api'
import { NEW_PUBLICATION, publicationPath, PUBLICATIONS_QUERY } from './paths'
import { Period, StatusBadge, useDebounced, useRegionsText, useStatusLabel } from './shared'

const PAGE_SIZE = 20
const ALL = 'all'

/**
 * The sponsor's publications (M-34): the quiz version, state, regions, takers, winners of the most allowed and Kudos
 * paid out of the budget, searched by name, filtered by state and sorted by the server (`qb.sponsor.publications`).
 */
export function PublicationListPage() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const statusLabel = useStatusLabel()
  const regionsText = useRegionsText()
  const [search, setSearch] = useState('')
  const q = useDebounced(search, 300)
  const [status, setStatus] = useState<PublicationStatus | null>(null)
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const [sorting, setSorting] = useState<SortingState>([{ id: 'editedAt', desc: true }])
  const firstPage = () => setPagination((p) => (p.pageIndex === 0 ? p : { ...p, pageIndex: 0 }))

  const publications = useQuery({
    queryKey: [...PUBLICATIONS_QUERY, 'list', q, status, pagination, sorting],
    queryFn: () => searchPublications({
      q,
      status,
      offset: pagination.pageIndex * pagination.pageSize,
      limit: pagination.pageSize,
      sorts: sorting.map((s) => ({ field: s.id, asc: !s.desc })),
    }),
    placeholderData: keepPreviousData,
  })

  const columns = useMemo<ColumnDef<PublicationRow, unknown>[]>(() => [
    {
      id: 'name',
      header: t('publishing.list.name'),
      cell: ({ row }) => (
        <span className="flex flex-col gap-1">
          <Link to={publicationPath(row.original.publicationId)} className="font-medium underline-offset-4 hover:underline"
            data-testid="publication-link">
            {row.original.name}
          </Link>
          {row.original.status === 'DRAFT' && (row.original.rejectReason || row.original.rejectCode) && (
            <Badge variant="destructive" className="w-fit" data-testid="publication-sent-back">
              {t('publishing.list.sentBack')}
            </Badge>
          )}
        </span>
      ),
    },
    {
      id: 'quiz',
      header: t('publishing.list.quiz'),
      enableSorting: false,
      cell: ({ row }) => row.original.versionLabel
        ? <span>{row.original.quizTitle} <span className="text-muted-foreground">({row.original.versionLabel})</span></span>
        : <span className="text-muted-foreground">{t('publishing.list.noQuiz')}</span>,
    },
    {
      id: 'status',
      header: t('publishing.list.status'),
      enableSorting: false,
      cell: ({ row }) => (
        <span className="flex flex-col gap-1">
          <StatusBadge status={row.original.status} startAt={row.original.startAt} endAt={row.original.endAt} />
          {row.original.fundsShort && (
            <Badge variant="warning" className="w-fit" data-testid="publication-funds-short">
              {t('publishing.list.fundsShort')}
            </Badge>
          )}
        </span>
      ),
    },
    {
      id: 'regions',
      header: t('publishing.list.regions'),
      enableSorting: false,
      cell: ({ row }) => regionsText(row.original.regions),
    },
    {
      id: 'starts',
      header: t('publishing.list.takers'),
      enableSorting: false,
      meta: { className: 'text-right' },
      cell: ({ row }) => row.original.starts.toLocaleString(),
    },
    {
      id: 'winners',
      header: t('publishing.list.winners'),
      enableSorting: false,
      meta: { className: 'text-right' },
      cell: ({ row }) => row.original.maxWinners === null
        ? row.original.winners.toLocaleString()
        : t('publishing.list.winnersOf', { winners: row.original.winners.toLocaleString(),
          max: row.original.maxWinners.toLocaleString() }),
    },
    {
      id: 'spent',
      header: t('publishing.list.spent'),
      enableSorting: false,
      meta: { className: 'text-right' },
      cell: ({ row }) => {
        const cap = row.original.budgetCap ?? row.original.maxSpend
        return cap === null ? formatKudos(row.original.spent)
          : t('publishing.list.spentOf', { spent: formatKudos(row.original.spent), max: formatKudos(cap) })
      },
    },
    {
      id: 'startAt',
      header: t('publishing.list.period'),
      cell: ({ row }) => <Period startAt={row.original.startAt} endAt={row.original.endAt} />,
    },
    {
      id: 'editedAt',
      header: t('publishing.list.editedAt'),
      cell: ({ row }) => formatDateTime(row.original.editedAt),
    },
  ], [t, regionsText])

  const rows = publications.data?.items ?? []
  const empty = q || status ? t('publishing.list.noMatch') : t('publishing.list.none')

  return (
    <section className="flex flex-col gap-4" data-testid="publication-list">
      <PageHeader title={t('publishing.list.title')} description={t('publishing.list.intro')}
        actions={
          <Button onClick={() => navigate(publicationPath(NEW_PUBLICATION))} data-testid="new-publication">
            <Plus aria-hidden />
            {t('publishing.list.new')}
          </Button>
        } />
      <form role="search" className="flex flex-wrap items-end gap-4" onSubmit={(event) => event.preventDefault()}>
        <div className="flex w-full max-w-sm flex-col gap-2">
          <Label htmlFor={`${id}-search`}>{t('publishing.list.search')}</Label>
          <Input id={`${id}-search`} type="search" value={search} maxLength={PUBLISHING_LIMITS.name} autoComplete="off"
            data-testid="publication-search" onChange={(event) => {
              setSearch(event.target.value)
              firstPage()
            }} />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-state`}>{t('publishing.list.state')}</Label>
          <Select value={status ?? ALL} onValueChange={(value) => {
            setStatus(value === ALL ? null : (value as PublicationStatus))
            firstPage()
          }}>
            <SelectTrigger id={`${id}-state`} className="w-48" data-testid="publication-state-filter">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL}>{t('publishing.list.allStates')}</SelectItem>
              {PUBLICATION_STATUSES.map((code) => <SelectItem key={code} value={code}>{statusLabel(code)}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
      </form>
      {publications.isError
        ? <PageState kind="error" description={tQb('failed')}
          action={<Button variant="outline" onClick={() => void publications.refetch()}>{tQb('retry')}</Button>} />
        : (
          <DataTable label={t('publishing.list.table')} columns={columns} data={rows}
            getRowId={(row) => row.publicationId} loading={publications.isPending}
            stale={publications.isPlaceholderData} empty={empty}
            pagination={pagination} onPaginationChange={setPagination} rowCount={publications.data?.total}
            sorting={sorting} onSortingChange={(next) => {
              setSorting(next)
              firstPage()
            }}
            rowProps={(row) => ({ 'data-testid': 'publication-row', 'data-publication': row.publicationId,
              'data-status': row.status })} />
        )}
    </section>
  )
}
