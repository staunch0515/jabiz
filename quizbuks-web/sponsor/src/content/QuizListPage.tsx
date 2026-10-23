import { formatDateTime } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  DataTable,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  PageHeader,
  PageState,
  RadioGroup,
  RadioGroupItem,
  type ColumnDef,
  type PaginationState,
  type SortingState,
} from '@jabiz/ui'
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { useEffect, useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { QB } from '../qb/messages'
import { fieldProblem, generalProblem } from '../qb/problems'
import { Link, navigate } from '../qb/router'
import { readQuizStatuses, saveQuiz, searchQuizzes, type QuizKind, type QuizRow } from './api'
import { Thumbnail } from './ContentImage'
import { LIMITS } from './limits'
import { quizPath, QUIZZES_QUERY } from './paths'
import { ExportQuizButton, ImportQuizButton } from './QuizTransfer'

/** The labels of the quiz states, from the server's dictionary (the code until it answered). */
export function useQuizStatusLabel() {
  const statuses = useQuery({ queryKey: ['qb', 'dict', 'quiz-status'], queryFn: readQuizStatuses, staleTime: Infinity })
  return (code: string) => statuses.data?.find((item) => item.code === code)?.label ?? code
}

/** Waits until typing paused before searching. */
function useDebounced<T>(value: T, ms: number): T {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), ms)
    return () => clearTimeout(timer)
  }, [value, ms])
  return settled
}

/**
 * Starts a quiz with only its title (a draft may be incomplete, M-22) and its kind, a scored quiz or a survey (Q12:
 * chosen now, never changed), and opens it.
 */
function NewQuizDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const [title, setTitle] = useState('')
  const [kind, setKind] = useState<QuizKind>('QUIZ')
  const create = useMutation({
    mutationFn: () => saveQuiz({ title: title.trim(), intro: null, cover: null, timeLimitSec: null, kind }),
    onSuccess: (output) => {
      onOpenChange(false)
      navigate(quizPath(output.quizId))
    },
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    create.mutate()
  }
  const problem = fieldProblem(create.error, 'title')
  const general = generalProblem(create.error, ['title'])
  return (
    <Dialog open={open} onOpenChange={(next) => {
      if (!next) {
        setTitle('')
        setKind('QUIZ')
        create.reset()
      }
      onOpenChange(next)
    }}>
      <DialogContent data-testid="new-quiz-dialog">
        <form noValidate onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t('quizzes.new')}</DialogTitle>
            <DialogDescription>{t('quizzes.newHint')}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-title`}>{t('editor.quizTitle')}</Label>
            <Input id={`${id}-title`} value={title} required maxLength={LIMITS.title} autoComplete="off"
              aria-invalid={problem ? true : undefined} aria-describedby={problem ? `${id}-title-error` : undefined}
              data-testid="new-quiz-title" onChange={(event) => setTitle(event.target.value)} />
            {problem && <p id={`${id}-title-error`} className="text-destructive text-sm">{problem}</p>}
          </div>
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-2 text-sm font-medium">{t('quizzes.kind')}</legend>
            <RadioGroup value={kind} onValueChange={(next) => setKind(next as QuizKind)} data-testid="new-quiz-kind">
              {(['QUIZ', 'SURVEY'] as const).map((value) => (
                <div key={value} className="flex items-start gap-2">
                  <RadioGroupItem id={`${id}-${value}`} value={value} aria-describedby={`${id}-${value}-hint`}
                    data-testid={`new-quiz-kind-${value}`} />
                  <span className="flex flex-col gap-0.5">
                    <Label htmlFor={`${id}-${value}`}>{t(`quizzes.kinds.${value}`)}</Label>
                    <span id={`${id}-${value}-hint`} className="text-muted-foreground text-xs">
                      {t(`quizzes.kindHints.${value}`)}
                    </span>
                  </span>
                </div>
              ))}
            </RadioGroup>
          </fieldset>
          {general && (
            <Alert variant="destructive"><AlertDescription>{general}</AlertDescription></Alert>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>{t('common.cancel')}</Button>
            <Button type="submit" disabled={create.isPending} aria-busy={create.isPending || undefined}
              data-testid="new-quiz-create">
              {t('quizzes.create')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

const PAGE_SIZE = 20

/**
 * The sponsor's quizzes (M-20): cover, title, version, number of questions, state and last change, searched by title
 * and sorted by the server (`qb.sponsor.quizzes`, the sponsor's own and not removed). "New quiz" starts a draft.
 */
export function QuizListPage() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const statusLabel = useQuizStatusLabel()
  const [search, setSearch] = useState('')
  const q = useDebounced(search, 300)
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const [sorting, setSorting] = useState<SortingState>([{ id: 'editedAt', desc: true }])
  const [creating, setCreating] = useState(false)

  const quizzes = useQuery({
    queryKey: [...QUIZZES_QUERY, 'list', q, pagination, sorting],
    queryFn: () => searchQuizzes({
      q,
      offset: pagination.pageIndex * pagination.pageSize,
      limit: pagination.pageSize,
      sorts: sorting.map((s) => ({ field: s.id, asc: !s.desc })),
    }),
    placeholderData: keepPreviousData,
  })

  const columns = useMemo<ColumnDef<QuizRow, unknown>[]>(() => [
    {
      id: 'cover',
      header: () => <span className="sr-only">{t('quizzes.cover')}</span>,
      enableSorting: false,
      meta: { className: 'w-20' },
      cell: ({ row }) => row.original.cover
        ? <Thumbnail fileId={row.original.cover} width={64} className="h-10 w-16 rounded-sm" />
        : <span aria-hidden className="bg-muted inline-block h-10 w-16 rounded-sm" />,
    },
    {
      id: 'title',
      header: t('quizzes.titleColumn'),
      cell: ({ row }) => (
        <span className="flex flex-col gap-1">
          <Link to={quizPath(row.original.quizId)} className="font-medium underline-offset-4 hover:underline"
            data-testid="quiz-link">
            {row.original.title}
          </Link>
          <span className="flex flex-wrap gap-1">
            {row.original.kind === 'SURVEY' && (
              <Badge variant="secondary" className="w-fit" data-testid="quiz-survey">{t('quizzes.kinds.SURVEY')}</Badge>
            )}
            {row.original.aiGenerated && <Badge variant="outline" className="w-fit">{t('quizzes.ai')}</Badge>}
          </span>
        </span>
      ),
    },
    {
      id: 'version',
      header: t('quizzes.version'),
      enableSorting: false,
      cell: ({ row }) => (
        <span className="flex flex-wrap items-center gap-1">
          <span>{row.original.versionLabel ?? t('quizzes.noVersion')}</span>
          {row.original.versionLabel && row.original.changedSinceVersion && (
            <Badge variant="outline">{t('quizzes.changedSinceVersion')}</Badge>
          )}
        </span>
      ),
    },
    {
      id: 'status',
      header: t('quizzes.status'),
      enableSorting: false,
      cell: ({ row }) => (
        <span className="flex flex-wrap gap-1">
          <Badge variant={row.original.status === 'VERSIONED' ? 'secondary' : 'outline'}>
            {statusLabel(row.original.status)}
          </Badge>
          {row.original.published && <Badge variant="success">{t('quizzes.published')}</Badge>}
        </span>
      ),
    },
    {
      id: 'questionCount',
      header: t('quizzes.questions'),
      meta: { className: 'text-right' },
      cell: ({ row }) => row.original.questionCount,
    },
    {
      id: 'materialCount',
      header: t('quizzes.materials'),
      enableSorting: false,
      meta: { className: 'text-right' },
      cell: ({ row }) => row.original.materialCount,
    },
    {
      id: 'editedAt',
      header: t('quizzes.editedAt'),
      cell: ({ row }) => formatDateTime(row.original.editedAt),
    },
    {
      id: 'actions',
      header: () => <span className="sr-only">{t('quizzes.transfer.export')}</span>,
      enableSorting: false,
      meta: { className: 'text-right' },
      cell: ({ row }) => <ExportQuizButton quizId={row.original.quizId} title={row.original.title} />,
    },
  ], [t, statusLabel])

  const rows = quizzes.data?.items ?? []
  const empty = q ? t('quizzes.noMatch') : t('quizzes.none')

  return (
    <section className="flex flex-col gap-4" data-testid="quiz-list">
      <PageHeader title={t('quizzes.title')} description={t('quizzes.intro')}
        actions={
          <div className="flex flex-wrap gap-2">
            <ImportQuizButton />
            <Button onClick={() => setCreating(true)} data-testid="new-quiz">
              <Plus aria-hidden />
              {t('quizzes.new')}
            </Button>
          </div>
        } />
      <form role="search" className="flex max-w-sm flex-col gap-2" onSubmit={(event) => event.preventDefault()}>
        <Label htmlFor={`${id}-search`}>{t('quizzes.search')}</Label>
        <Input id={`${id}-search`} type="search" value={search} maxLength={LIMITS.title} autoComplete="off"
          data-testid="quiz-search" onChange={(event) => {
            setSearch(event.target.value)
            // A new search starts on the first page.
            setPagination((p) => (p.pageIndex === 0 ? p : { ...p, pageIndex: 0 }))
          }} />
      </form>
      {quizzes.isError
        ? <PageState kind="error" description={tQb('failed')}
          action={<Button variant="outline" onClick={() => void quizzes.refetch()}>{tQb('retry')}</Button>} />
        : (
          <DataTable label={t('quizzes.table')} columns={columns} data={rows}
            getRowId={(row) => row.quizId} loading={quizzes.isPending}
            stale={quizzes.isPlaceholderData} empty={empty}
            pagination={pagination} onPaginationChange={setPagination} rowCount={quizzes.data?.total}
            sorting={sorting} onSortingChange={(next) => {
              setSorting(next)
              setPagination((p) => ({ ...p, pageIndex: 0 }))
            }}
            rowProps={(row) => ({ 'data-testid': 'quiz-row', 'data-quiz': row.quizId })} />
        )}
      <NewQuizDialog open={creating} onOpenChange={setCreating} />
    </section>
  )
}
