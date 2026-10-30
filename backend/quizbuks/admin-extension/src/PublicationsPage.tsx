import { Search } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Badge,
  Button,
  DataTable,
  EXTENSION_NAMESPACE,
  Input,
  PageHeader,
  UserName,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { LoadFailed, PublisherLink } from './components'
import { StatusFilter } from './finance-components'
import { useLabels, usePaged, when } from './hooks'
import { useMoreFilters } from './moreFilters'
import { DICTIONARY, PATH, PERMISSION } from './ids'
import { publicationFilterOf, readPublications, type PublicationListRow } from './overview-api'

/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS = { name: 'name', status: 'status', period: 'startAt', editedAt: 'editedAt' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }

/** What a publication's page goes back to when it was opened from this list. */
export interface BackTo {
  path: string
  label: 'publications.backToList' | 'publications.backToQuiz'
}

/**
 * Every publication in every state (Q17), the last edited first: unlike the review queue, a publication stays here
 * once it was decided. Searched by name and narrowed by state, sponsor or quiz (the address carries the filter: a
 * quiz's and a user's pages link here). Each opens on the publication's page, and its attempts on the attempt list.
 */
export default function PublicationsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const quizzes = can(PERMISSION.quizRead)
  const statusLabel = useLabels(DICTIONARY.publicationStatus)
  const [search, setSearch] = useSearchParams()
  const filter = publicationFilterOf(search)
  const [text, setText] = useState(filter.q ?? '')
  // The box follows the address (back and forward, the menu's link without a search).
  const [shownQ, setShownQ] = useState(filter.q)
  if (shownQ !== filter.q) {
    setShownQ(filter.q)
    setText(filter.q ?? '')
  }
  const set = (change: Record<string, string | null>) => {
    const next = new URLSearchParams(search)
    for (const [key, value] of Object.entries(change)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setSearch(next, { replace: true })
  }
  const more = useMoreFilters([
    { kind: 'time', name: 'startAt', label: t('publications.startAt') },
    { kind: 'time', name: 'submittedAt', label: t('publications.submittedAt') },
  ], 'publications-filters')
  const { query, table, sortable } = usePaged(['qb-ext', 'publications', filter, more.filters],
    (offset, limit, sorts) => readPublications(filter, offset, limit, sorts, more.filters), true, SORTS)
  const here = `${PATH.publications}${search.size > 0 ? `?${search}` : ''}`

  const columns = useMemo<ColumnDef<PublicationListRow, unknown>[]>(() => [
    {
      id: 'name',
      header: t('publications.name'),
      cell: ({ row }) => (
        <Link to={PATH.publication(row.original.publicationId)} className={linkClass} data-testid="publication-link"
          state={{ backTo: { path: here, label: 'publications.backToList' } satisfies BackTo }}
          aria-label={t('publications.open', { name: row.original.name })}>
          {row.original.name}
        </Link>
      ),
    },
    {
      id: 'quiz',
      header: t('publications.quiz'),
      cell: ({ row }) => {
        const label = [row.original.quizTitle, row.original.versionLabel].filter(Boolean).join(' · ')
        return quizzes && row.original.quizId
          ? <Link to={PATH.quiz(row.original.quizId)} className={linkClass}>{label}</Link>
          : label
      },
    },
    {
      id: 'sponsor',
      header: t('publications.sponsor'),
      cell: ({ row }) => (
        <PublisherLink kind={row.original.publisherKind} id={row.original.publisherId} />
      ),
    },
    {
      id: 'status',
      header: t('publications.status'),
      cell: ({ row }) => (
        <span className="flex flex-wrap items-center gap-1">
          <Badge variant="outline">{statusLabel(row.original.status)}</Badge>
          {row.original.status === 'DRAFT' && row.original.rejectReason && (
            <Badge variant="secondary" data-testid="publication-sent-back">{t('publications.sentBack')}</Badge>
          )}
        </span>
      ),
    },
    {
      id: 'period',
      header: t('publications.period'),
      cell: ({ row }) => (row.original.startAt || row.original.endAt
        ? t('publications.periodValue', { start: when(row.original.startAt), end: when(row.original.endAt) })
        : ''),
    },
    { id: 'starts', header: t('publications.participants'), meta: right,
      cell: ({ row }) => String(row.original.starts) },
    { id: 'completions', header: t('publications.completed'), meta: right,
      cell: ({ row }) => String(row.original.completions) },
    { id: 'winners', header: t('publications.winners'), meta: right,
      cell: ({ row }) => String(row.original.winners) },
    {
      id: 'attempts',
      header: t('publications.attempts'),
      cell: ({ row }) => (row.original.starts === 0 ? '' : (
        <Link to={PATH.attemptsOf({ publication: row.original.publicationId })} className={linkClass}
          data-testid="publication-attempts" aria-label={t('publications.attemptsOf', { name: row.original.name })}>
          {t('publications.attempts')}
        </Link>
      )),
    },
    { id: 'editedAt', header: t('publications.editedAt'), cell: ({ row }) => when(row.original.editedAt) },
  ], [t, statusLabel, quizzes, here])

  return (
    <div className="flex flex-col gap-4" data-testid="qb-publications">
      <PageHeader title={t('publications.title')} description={t('publications.description')} className="pb-0" />
      <div className="flex flex-wrap items-end gap-4">
        <form role="search" className="flex max-w-md items-center gap-2" onSubmit={(event) => {
          event.preventDefault()
          set({ q: text.trim() || null })
        }}>
          <Input type="search" value={text} onChange={(event) => setText(event.target.value)}
            placeholder={t('publications.search')} aria-label={t('publications.search')} maxLength={100}
            data-testid="publications-search" />
          <Button type="submit" variant="outline">
            <Search aria-hidden />
            {t('publications.searchButton')}
          </Button>
        </form>
        <StatusFilter dictionary={DICTIONARY.publicationStatus} value={filter.status} testId="publications-status"
          onChange={(value) => set({ status: value })} />
        {filter.sponsorId && (
          <span className="text-sm" data-testid="publications-sponsor">
            {t('publications.ofSponsor')} <UserName id={filter.sponsorId} />
          </span>
        )}
        {filter.quizId && quizzes && (
          <span className="text-sm" data-testid="publications-quiz">
            {t('publications.ofQuiz')}{' '}
            <Link to={PATH.quiz(filter.quizId)} className={linkClass}>{t('publications.openQuiz')}</Link>
          </span>
        )}
        {(filter.q || filter.status || filter.sponsorId || filter.quizId) && (
          <Button variant="ghost" size="sm" data-testid="publications-all" onClick={() => {
            setText('')
            set({ q: null, status: null, sponsor: null, quiz: null })
          }}>
            {t('publications.all')}
          </Button>
        )}
      </div>
      {more.bar}
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('publications.table')} columns={sortable(columns)} {...table}
            getRowId={(row) => row.publicationId} empty={t('publications.none')}
            rowProps={(row) => ({ 'data-testid': 'publication-row', 'data-id': row.publicationId,
              'data-status': row.status })} />
        )}
    </div>
  )
}
