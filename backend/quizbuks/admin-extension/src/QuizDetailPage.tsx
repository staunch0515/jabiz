import { useQuery } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { createContext, useContext, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  DataTable,
  DescriptionList,
  EXTENSION_NAMESPACE,
  PageHeader,
  PageState,
  UserName,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { readVersion } from './api'
import { LoadFailed, Section } from './components'
import { useLabels, usePaged, when } from './hooks'
import { DICTIONARY, PATH, PERMISSION } from './ids'
import {
  readDraft,
  readPublications,
  readQuiz,
  readQuizVersions,
  type PublicationListRow,
  type QuizRow,
  type QuizVersionRow,
} from './overview-api'
import type { BackTo } from './PublicationsPage'
import { VersionPreview } from './VersionPreview'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }

function Publications({ quiz }: { quiz: QuizRow }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const statusLabel = useLabels(DICTIONARY.publicationStatus)
  const filter = { q: null, status: null, sponsorId: null, quizId: quiz.quizId }
  const { query, table } = usePaged(['qb-ext', 'publications', filter],
    (offset, limit) => readPublications(filter, offset, limit))
  const columns = useMemo<ColumnDef<PublicationListRow, unknown>[]>(() => [
    {
      id: 'name',
      header: t('publications.name'),
      cell: ({ row }) => (
        <Link to={PATH.publication(row.original.publicationId)} className={linkClass}
          state={{ backTo: { path: PATH.quiz(quiz.quizId), label: 'publications.backToQuiz' } satisfies BackTo }}
          aria-label={t('publications.open', { name: row.original.name })} data-testid="quiz-publication-link">
          {row.original.name}
        </Link>
      ),
    },
    { id: 'version', header: t('quiz.versionLabel'), cell: ({ row }) => row.original.versionLabel ?? '' },
    { id: 'status', header: t('publications.status'),
      cell: ({ row }) => <Badge variant="outline">{statusLabel(row.original.status)}</Badge> },
    { id: 'starts', header: t('publications.participants'), meta: right,
      cell: ({ row }) => String(row.original.starts) },
    {
      id: 'attempts',
      header: t('publications.attempts'),
      cell: ({ row }) => (row.original.starts === 0 ? '' : (
        <Link to={PATH.attemptsOf({ publication: row.original.publicationId })} className={linkClass}
          aria-label={t('publications.attemptsOf', { name: row.original.name })}>
          {t('publications.attempts')}
        </Link>
      )),
    },
    { id: 'editedAt', header: t('publications.editedAt'), cell: ({ row }) => when(row.original.editedAt) },
  ], [t, statusLabel, quiz.quizId])
  return (
    <Section title={t('quiz.publications')} testId="quiz-publications" actions={
      <Button variant="outline" size="sm" asChild>
        <Link to={PATH.publicationsWhere({ quiz: quiz.quizId })}>{t('quiz.allPublications')}</Link>
      </Button>
    }>
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('quiz.publicationsTable')} columns={columns} {...table}
            getRowId={(row) => row.publicationId} empty={t('quiz.nonePublications')}
            rowProps={(row) => ({ 'data-testid': 'quiz-publication', 'data-id': row.publicationId })} />
        )}
    </Section>
  )
}

/** One version's frozen content, read when asked for (it holds the correct answers). */
function VersionContent({ version }: { version: QuizVersionRow }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const read = useQuery({ queryKey: ['qb-ext', 'version-content', version.versionId],
    queryFn: () => readVersion(version.versionId), staleTime: Infinity })
  let body
  if (read.isPending) body = <PageState kind="loading" />
  else if (read.isError) body = <LoadFailed error={read.error} onRetry={() => void read.refetch()} />
  else if (!read.data?.content) body = <p className="text-muted-foreground text-sm">{t('quiz.unreadable')}</p>
  else body = <VersionPreview content={read.data.content} />
  return (
    <Section title={t('quiz.versionContent', { label: version.label })} testId="quiz-version-content">
      {body}
    </Section>
  )
}

/**
 * Which version's content is shown, for the table's toggles: through a context, so the columns stay the same and
 * a toggle keeps its focus when it is pressed (a cell renders anew when its column does).
 */
const Shown = createContext<{ shown: string | null; toggle: (version: QuizVersionRow) => void }>({
  shown: null,
  toggle: () => {},
})

function ShowToggle({ version }: { version: QuizVersionRow }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { shown, toggle } = useContext(Shown)
  const open = shown === version.versionId
  return (
    <Button variant="outline" size="sm" aria-expanded={open} data-testid="quiz-version-show"
      aria-label={open ? t('quiz.hide') : t('quiz.versionContent', { label: version.label })}
      onClick={() => toggle(version)}>
      {open ? t('quiz.hide') : t('quiz.show')}
    </Button>
  )
}

function Versions({ quiz }: { quiz: QuizRow }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { query, table } = usePaged(['qb-ext', 'quiz-versions', quiz.quizId],
    (offset, limit) => readQuizVersions(quiz.quizId, offset, limit))
  const [shown, setShown] = useState<QuizVersionRow | null>(null)
  const columns = useMemo<ColumnDef<QuizVersionRow, unknown>[]>(() => [
    { id: 'label', header: t('quiz.versionLabel'), cell: ({ row }) => row.original.label },
    { id: 'title', header: t('quiz.versionTitle'), cell: ({ row }) => row.original.title },
    { id: 'questions', header: t('quiz.questions'), meta: right,
      cell: ({ row }) => String(row.original.questionCount) },
    { id: 'fullScore', header: t('quiz.fullScore'), meta: right,
      cell: ({ row }) => (row.original.fullScore === null ? '' : String(row.original.fullScore)) },
    { id: 'versionedAt', header: t('quiz.versionedAt'), cell: ({ row }) => when(row.original.versionedAt) },
    {
      id: 'show',
      header: () => <span className="sr-only">{t('quiz.show')}</span>,
      cell: ({ row }) => <ShowToggle version={row.original} />,
    },
  ], [t])
  const toggle = (version: QuizVersionRow) =>
    setShown((current) => (current?.versionId === version.versionId ? null : version))
  return (
    <Shown.Provider value={{ shown: shown?.versionId ?? null, toggle }}>
      <Section title={t('quiz.versions')} testId="quiz-versions">
        {query.isError
          ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
          : (
            <DataTable label={t('quiz.versionsTable')} columns={columns} {...table}
              getRowId={(row) => row.versionId} empty={t('quiz.noneVersions')}
              rowProps={(row) => ({ 'data-testid': 'quiz-version', 'data-id': row.versionId })} />
          )}
      </Section>
      {shown && <VersionContent key={shown.versionId} version={shown} />}
    </Shown.Provider>
  )
}

function Draft({ quiz }: { quiz: QuizRow }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const draft = useQuery({ queryKey: ['qb-ext', 'quiz-draft', quiz.quizId, quiz.editedAt],
    queryFn: () => readDraft(quiz) })
  let body
  if (draft.isPending) body = <PageState kind="loading" />
  else if (draft.isError) body = <LoadFailed error={draft.error} onRetry={() => void draft.refetch()} />
  else body = <VersionPreview content={draft.data} />
  return (
    <Section title={t('quiz.draft')} testId="quiz-draft">
      <p className="text-muted-foreground text-sm">{t('quiz.draftHint')}</p>
      {body}
    </Section>
  )
}

/**
 * One quiz (Q17), whatever became of it: its facts, its publications in every state, its versions (each one's frozen
 * content shown on request: it holds the correct answers) and its draft as the sponsor is editing it now. Read only;
 * a removed quiz has no draft left, its versions stay.
 */
export default function QuizDetailPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { quizId = '' } = useParams()
  const { can } = useAuth()
  const kindLabel = useLabels(DICTIONARY.quizKind)
  const statusLabel = useLabels(DICTIONARY.quizStatus)
  const quiz = useQuery({ queryKey: ['qb-ext', 'quiz', quizId], queryFn: () => readQuiz(quizId) })

  const back = (
    <Button variant="ghost" size="sm" asChild>
      <Link to={PATH.quizzes}><ArrowLeft aria-hidden />{t('quiz.back')}</Link>
    </Button>
  )
  if (quiz.isPending) return <PageState kind="loading" />
  if (quiz.isError) return <LoadFailed error={quiz.error} onRetry={() => void quiz.refetch()} />
  if (!quiz.data) {
    return <div className="flex flex-col gap-4">{back}<PageState kind="notFound" description={t('quiz.notFound')} /></div>
  }
  const q = quiz.data
  return (
    <div className="flex flex-col gap-4" data-testid="qb-quiz" data-removed={q.removed}>
      <div>{back}</div>
      <PageHeader title={q.title} className="pb-0" description={
        <span className="flex flex-wrap items-center gap-2">
          <Badge variant="outline">{kindLabel(q.kind)}</Badge>
          <Badge variant="outline">{statusLabel(q.status)}</Badge>
          {q.removed && <Badge variant="secondary" data-testid="quiz-removed">{t('quizzes.removed')}</Badge>}
        </span>
      } />
      {q.removed && <Alert><AlertDescription>{t('quiz.removed')}</AlertDescription></Alert>}
      <Section title={t('quiz.facts')} testId="quiz-facts">
        <DescriptionList items={[
          { key: 'sponsor', label: t('quiz.sponsor'), value: (
            <Link to={PATH.user(q.ownerId)} className={linkClass} data-testid="quiz-sponsor">
              <UserName id={q.ownerId} />
            </Link>
          ) },
          { key: 'version', label: t('quiz.version'), value: q.versionLabel ?? t('quiz.noVersion') },
          ...(q.changedSinceVersion && q.versionLabel
            ? [{ key: 'changed', label: t('quiz.status'), value: t('quiz.changed') }] : []),
          { key: 'questions', label: t('quiz.questions'), value: String(q.questionCount) },
          { key: 'materials', label: t('quiz.materials'), value: String(q.materialCount) },
          { key: 'timeLimit', label: t('quiz.timeLimit'),
            value: q.timeLimitSec === null ? t('common.notSet') : t('common.seconds', { seconds: q.timeLimitSec }) },
          { key: 'editedAt', label: t('quiz.editedAt'), value: when(q.editedAt) },
          ...(q.clonedFrom ? [{ key: 'clonedFrom', label: t('quiz.clonedFrom'), value: (
            <Link to={PATH.quiz(q.clonedFrom)} className={linkClass}>{t('common.open')}</Link>
          ) }] : []),
        ]} />
      </Section>
      {can(PERMISSION.publicationRead) && <Publications quiz={q} />}
      <Versions quiz={q} />
      {!q.removed && <Draft quiz={q} />}
    </div>
  )
}
