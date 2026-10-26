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
import { EmptyState } from '../EmptyState'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { Link, navigate } from '../qb/router'
import { PUBLICATION_STATUSES, PUBLISHING_LIMITS, searchPublications, type PublicationRow,
  type PublicationStatus } from './api'
import { NEW_PUBLICATION, publicationPath, PUBLICATIONS_QUERY } from './paths'
import { Period, StatusBadge, useDebounced, useRegionsText, useStatusLabel, useRewardMethodLabel } from './shared'
import { CardGrid, Pager, useListView, ViewSwitch } from '../ListView'
import { CoverPlaceholder } from '../qb/Illustrations'
import { Thumbnail } from '../content/ContentImage'

const PAGE_SIZE = 20
const ALL = 'all'

/**
 * A publication as a card (Q34b): its quiz's cover large on top with the state and the way it pays, the name and the
 * quiz, then what it reached (takers, winners, spent) and its period. The same test ids and data as a row of the table.
 */
function PublicationCard({ publication: p, methodLabel, regionsText }: { publication: PublicationRow;
  methodLabel: (method: PublicationRow['rewardMethod']) => string; regionsText: (regions: string[]) => string }) {
  const { t } = useTranslation(SPONSOR)
  const cap = p.budgetCap ?? p.maxSpend
  return (
    <li className="bg-card group relative flex flex-col overflow-hidden border shadow-xs transition-shadow hover:shadow-md"
      data-testid="publication-row" data-publication={p.publicationId} data-status={p.status}>
      <div aria-hidden className="relative block aspect-video overflow-hidden">
        {p.cover
          ? <Thumbnail fileId={p.cover} width={480} className="size-full transition-transform duration-300 group-hover:scale-105" />
          : <CoverPlaceholder seed={p.publicationId} className="size-full" />}
        <span className="absolute top-2 left-2 flex flex-wrap gap-1">
          <StatusBadge status={p.status} startAt={p.startAt} endAt={p.endAt} />
          {p.fundsShort && <Badge variant="warning" data-testid="publication-funds-short">{t('publishing.list.fundsShort')}</Badge>}
        </span>
        {p.rewardMethod !== 'PLATFORM' && (
          <Badge variant="outline" className="bg-background/90 absolute top-2 right-2" data-testid="publication-method-badge"
            data-method={p.rewardMethod}>
            {methodLabel(p.rewardMethod)}
          </Badge>
        )}
      </div>
      <div className="flex flex-1 flex-col gap-1.5 p-3">
        <Link to={publicationPath(p.publicationId)} className="line-clamp-2 font-semibold underline-offset-4 after:absolute after:inset-0 hover:underline"
          data-testid="publication-link">
          {p.name}
        </Link>
        <span className="text-muted-foreground truncate text-sm">
          {p.versionLabel ? `${p.quizTitle} (${p.versionLabel})` : t('publishing.list.noQuiz')}
        </span>
        {p.status === 'DRAFT' && (p.rejectReason || p.rejectCode) && (
          <Badge variant="destructive" className="w-fit" data-testid="publication-sent-back">{t('publishing.list.sentBack')}</Badge>
        )}
        <dl className="text-muted-foreground mt-auto grid grid-cols-3 gap-2 border-t pt-2 text-xs">
          <div><dt>{t('publishing.list.takers')}</dt><dd className="text-foreground font-medium">{p.starts.toLocaleString()}</dd></div>
          <div><dt>{t('publishing.list.winners')}</dt><dd className="text-foreground font-medium">{p.maxWinners === null
            ? p.winners.toLocaleString()
            : t('publishing.list.winnersOf', { winners: p.winners.toLocaleString(), max: p.maxWinners.toLocaleString() })}</dd></div>
          <div><dt>{t('publishing.list.spent')}</dt><dd className="text-foreground font-medium">{cap === null ? formatKudos(p.spent)
            : t('publishing.list.spentOf', { spent: formatKudos(p.spent), max: formatKudos(cap) })}</dd></div>
        </dl>
        <span className="text-muted-foreground flex flex-wrap justify-between gap-x-3 text-xs">
          <span><Period startAt={p.startAt} endAt={p.endAt} /></span>
          <span>{regionsText(p.regions)}</span>
        </span>
      </div>
    </li>
  )
}

/**
 * The sponsor's publications (M-34): the quiz version, state, regions, takers, winners of the most allowed and Kudos
 * paid out of the budget, searched by name, filtered by state and sorted by the server (`qb.sponsor.publications`).
 */
export function PublicationListPage() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const statusLabel = useStatusLabel()
  const methodLabel = useRewardMethodLabel()
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

  const [view, setView] = useListView('publications')
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
          {row.original.rewardMethod !== 'PLATFORM' && (
            <Badge variant="outline" className="w-fit" data-testid="publication-method-badge"
              data-method={row.original.rewardMethod}>
              {methodLabel(row.original.rewardMethod)}
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
  ], [t, regionsText, methodLabel])

  const rows = publications.data?.items ?? []
  const empty = q || status ? t('publishing.list.noMatch')
    : <EmptyState testId="publications-empty">{t('publishing.list.none')}</EmptyState>

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
        <ViewSwitch value={view} onChange={setView} testId="publication-view-switch" />
      </form>
      {publications.isError
        ? <PageState kind="error" description={tQb('failed')}
          action={<Button variant="outline" onClick={() => void publications.refetch()}>{tQb('retry')}</Button>} />
        : view === 'cards' ? (
          publications.isPending ? <PageState kind="loading" />
            : rows.length === 0 ? <div className="py-6 text-center">{empty}</div>
            : (
              <>
                <CardGrid label={t('publishing.list.table')} testId="publication-cards">
                  {rows.map((row) => <PublicationCard key={row.publicationId} publication={row} methodLabel={methodLabel}
                    regionsText={regionsText} />)}
                </CardGrid>
                <Pager pageIndex={pagination.pageIndex} pageSize={pagination.pageSize} total={publications.data?.total}
                  onPage={(pageIndex) => setPagination((p) => ({ ...p, pageIndex }))} />
              </>
            )
        ) : (
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
