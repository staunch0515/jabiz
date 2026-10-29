import { Search } from 'lucide-react'
import { useId, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Badge,
  Button,
  Checkbox,
  DataTable,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  PageHeader,
  UserName,
  type ColumnDef,
} from '@jabiz/admin'
import { LoadFailed } from './components'
import { useLabels, usePaged, when } from './hooks'
import { useMoreFilters } from './moreFilters'
import { DICTIONARY, PATH } from './ids'
import { quizFilterOf, readQuizzes, type QuizRow } from './overview-api'

/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS = { title: 'title', status: 'status', questions: 'questionCount', editedAt: 'editedAt' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }

/**
 * Every sponsor's quizzes (Q17), the last edited first, searched by title and narrowed to a sponsor (the address
 * carries both: a user's page links here). Removed quizzes are shown on request. Each opens on its draft, versions
 * and publications.
 */
export default function QuizzesPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const kindLabel = useLabels(DICTIONARY.quizKind)
  const statusLabel = useLabels(DICTIONARY.quizStatus)
  const [search, setSearch] = useSearchParams()
  const filter = quizFilterOf(search)
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
    { kind: 'dictionary', name: 'kind', label: t('quizzes.kind'), dictionary: DICTIONARY.quizKind },
    { kind: 'dictionary', name: 'status', label: t('quizzes.status'), dictionary: DICTIONARY.quizStatus },
    { kind: 'time', name: 'editedAt', label: t('quizzes.editedAt') },
  ], 'quizzes-filters')
  const { query, table, sortable } = usePaged(['qb-ext', 'quizzes', filter, more.filters],
    (offset, limit, sorts) => readQuizzes(filter, offset, limit, sorts, more.filters), true, SORTS)

  const columns = useMemo<ColumnDef<QuizRow, unknown>[]>(() => [
    {
      id: 'title',
      header: t('quizzes.quizTitle'),
      cell: ({ row }) => (
        <span className="flex flex-wrap items-center gap-2">
          <Link to={PATH.quiz(row.original.quizId)} className={linkClass} data-testid="quiz-link"
            aria-label={t('quizzes.open', { title: row.original.title })}>
            {row.original.title}
          </Link>
          {row.original.removed && <Badge variant="outline" data-testid="quiz-removed">{t('quizzes.removed')}</Badge>}
        </span>
      ),
    },
    {
      id: 'sponsor',
      header: t('quizzes.sponsor'),
      cell: ({ row }) => (
        <Link to={PATH.user(row.original.ownerId)} className={linkClass}><UserName id={row.original.ownerId} /></Link>
      ),
    },
    { id: 'kind', header: t('quizzes.kind'), cell: ({ row }) => kindLabel(row.original.kind) },
    {
      id: 'status',
      header: t('quizzes.status'),
      cell: ({ row }) => (
        <span className="flex flex-col">
          <span>{statusLabel(row.original.status)}</span>
          {row.original.changedSinceVersion && row.original.versionLabel && (
            <span className="text-muted-foreground text-xs">
              {t('quizzes.changed', { label: row.original.versionLabel })}
            </span>
          )}
        </span>
      ),
    },
    { id: 'version', header: t('quizzes.version'), cell: ({ row }) => row.original.versionLabel ?? '' },
    { id: 'questions', header: t('quizzes.questions'), meta: right,
      cell: ({ row }) => String(row.original.questionCount) },
    {
      id: 'publications',
      header: t('quizzes.publications'),
      meta: right,
      cell: ({ row }) => (row.original.publicationCount === 0 ? '0' : (
        <Link to={PATH.publicationsWhere({ quiz: row.original.quizId })} className={linkClass}
          aria-label={t('quizzes.publicationsOf', { title: row.original.title })}>
          {row.original.publicationCount}
        </Link>
      )),
    },
    { id: 'editedAt', header: t('quizzes.editedAt'), cell: ({ row }) => when(row.original.editedAt) },
  ], [t, kindLabel, statusLabel])

  return (
    <div className="flex flex-col gap-4" data-testid="qb-quizzes">
      <PageHeader title={t('quizzes.title')} description={t('quizzes.description')} className="pb-0" />
      <div className="flex flex-wrap items-center gap-4">
        <form role="search" className="flex max-w-md items-center gap-2" onSubmit={(event) => {
          event.preventDefault()
          set({ q: text.trim() || null })
        }}>
          <Input type="search" value={text} onChange={(event) => setText(event.target.value)}
            placeholder={t('quizzes.search')} aria-label={t('quizzes.search')} maxLength={100}
            data-testid="quizzes-search" />
          <Button type="submit" variant="outline">
            <Search aria-hidden />
            {t('quizzes.searchButton')}
          </Button>
        </form>
        <div className="flex items-center gap-2">
          <Checkbox id={`${id}-removed`} checked={filter.removed} data-testid="quizzes-removed"
            onCheckedChange={(checked) => set({ removed: checked === true ? '1' : null })} />
          <Label htmlFor={`${id}-removed`}>{t('quizzes.showRemoved')}</Label>
        </div>
        {filter.ownerId && (
          <span className="text-sm" data-testid="quizzes-sponsor">
            {t('quizzes.ofSponsor')} <UserName id={filter.ownerId} />
          </span>
        )}
        {(filter.ownerId || filter.q) && (
          <Button variant="ghost" size="sm" data-testid="quizzes-all" onClick={() => {
            setText('')
            set({ sponsor: null, q: null })
          }}>
            {t('quizzes.all')}
          </Button>
        )}
      </div>
      {more.bar}
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('quizzes.table')} columns={sortable(columns)} {...table} getRowId={(row) => row.quizId}
            empty={t('quizzes.none')}
            rowProps={(row) => ({ 'data-testid': 'quiz-row', 'data-id': row.quizId })} />
        )}
    </div>
  )
}
