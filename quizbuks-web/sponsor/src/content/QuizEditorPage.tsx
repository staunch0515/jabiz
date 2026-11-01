import { QuizProductsPanel } from '../products/QuizProductsPanel'
import { formatDateTime } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  Badge,
  Button,
  ConfirmDialog,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  notify,
  PageHeader,
  PageState,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CircleCheck, Copy, RefreshCw, Save, Trash2 } from 'lucide-react'
import { useCallback, useEffect, useId, useMemo, useRef, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { AiCoverPanel } from '../ai/AiCoverPanel'
import { AiQuestionsPanel } from '../ai/AiQuestionsPanel'
import { SPONSOR } from '../messages'
import { QB } from '../qb/messages'
import { fieldProblem } from '../qb/problems'
import { Link, navigate } from '../qb/router'
import { cloneQuiz, deleteQuiz, isStale, publishVersion, readDraft, type LanguageStatus, type Quiz } from './api'
import { EditingProvider, failedRemoval, formProblem, ProblemList, type Editing } from './editing'
import { HeadingForm } from './HeadingForm'
import { LIMITS } from './limits'
import { MaterialsPanel } from './MaterialsPanel'
import { draftQuery, quizPath, QUIZZES_PATH, QUIZZES_QUERY, versionsQuery } from './paths'
import { QuestionsPanel } from './QuestionsPanel'
import { TranslationsPanel } from './TranslationsPanel'
import { useQuizStatusLabel } from './QuizListPage'
import { VersionsPanel } from './VersionsPanel'
import { PreviewButton } from '../preview/PreviewButton'
import { previewOfQuiz } from '../preview/paths'

/** A copy of the draft as a new quiz (M-28), under a title of the sponsor's choice. */
function CopyDialog({ quiz, open, onOpenChange }: { quiz: Quiz; open: boolean; onOpenChange: (open: boolean) => void }) {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const id = useId()
  const [title, setTitle] = useState(() => t('editor.copyOf', { title: quiz.title }).slice(0, LIMITS.title))
  const copy = useMutation({
    mutationFn: () => cloneQuiz(quiz.quizId, null, title.trim()),
    onSuccess: async (output) => {
      onOpenChange(false)
      notify.success(t('editor.copied'))
      await queryClient.invalidateQueries({ queryKey: QUIZZES_QUERY })
      navigate(quizPath(output.quizId))
    },
  })
  const problem = fieldProblem(copy.error, 'title')
  return (
    <Dialog open={open} onOpenChange={(next) => {
      if (!copy.isPending) onOpenChange(next)
    }}>
      <DialogContent data-testid="copy-dialog">
        <form noValidate className="flex flex-col gap-4" onSubmit={(event: FormEvent) => {
          event.preventDefault()
          copy.mutate()
        }}>
          <DialogHeader>
            <DialogTitle>{t('editor.copyTitle')}</DialogTitle>
            <DialogDescription>{t('editor.copyHint')}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-title`}>{t('editor.quizTitle')}</Label>
            <Input id={`${id}-title`} value={title} maxLength={LIMITS.title} autoComplete="off"
              aria-invalid={problem ? true : undefined} aria-describedby={problem ? `${id}-error` : undefined}
              data-testid="copy-title" onChange={(event) => setTitle(event.target.value)} />
            {problem && <p id={`${id}-error`} className="text-destructive text-sm">{problem}</p>}
          </div>
          <ProblemList problems={formProblem(copy.error, ['title'])} />
          <DialogFooter>
            <Button type="button" variant="outline" disabled={copy.isPending} onClick={() => onOpenChange(false)}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={copy.isPending} aria-busy={copy.isPending || undefined}
              data-testid="copy-confirm">
              {t('editor.copy')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

type Tab = 'quiz' | 'questions' | 'materials' | 'languages' | 'versions' | 'products'

/**
 * One quiz's editor (M-21 – M-24, M-27, M-28): its heading, questions and materials, saved part by part, and its
 * versions. Every edit sends the revision the page last saw; when the quiz changed meanwhile (another window, another
 * person) the server refuses it (`QB_QUIZ_CHANGED`) and the page asks to reload before editing on. "Save version"
 * checks the draft is complete and lists every problem at once.
 */
export function QuizEditorPage({ quizId }: { quizId: string }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const statusLabel = useQuizStatusLabel()
  const draft = useQuery({ queryKey: draftQuery(quizId), queryFn: () => readDraft(quizId) })
  const [tab, setTab] = useState<Tab>('quiz')
  const [stale, setStale] = useState(false)
  // Forms start from the quiz as read; a reload starts them again.
  const [generation, setGeneration] = useState(0)
  const [copying, setCopying] = useState(false)
  // The last "save version": the label saved, or the problems that stopped it.
  const [versionOutcome, setVersionOutcome] = useState<{ label?: string; problems?: string[];
    incomplete?: LanguageStatus[] } | null>(null)
  const staleRef = useRef<HTMLDivElement>(null)

  // The revision the page knows: the one first read, then each of its own edits'. A newer one read afterwards is
  // someone else's change, which the forms do not show: the page is out of date.
  const known = useRef<number | null>(null)
  useEffect(() => {
    const revision = draft.data?.quiz.revision
    if (revision === undefined) return
    if (known.current === null) known.current = revision
    else if (revision > known.current) setStale(true)
  }, [draft.data])

  // A refusal as out of date moves the focus to the notice, once it is shown: after React commits it (an effect), not
  // in the next frame, which may come first.
  const focusStale = useRef(false)
  const markStale = useCallback(() => {
    if (staleRef.current) staleRef.current.focus()
    else focusStale.current = true
    setStale(true)
  }, [])
  useEffect(() => {
    if (!stale || !focusStale.current) return
    focusStale.current = false
    staleRef.current?.focus()
  }, [stale])

  const editing = useMemo<Editing>(() => ({
    quizId,
    revision: () => known.current ?? draft.data?.quiz.revision ?? 0,
    edited: async (output) => {
      known.current = output.revision
      // What "save version" said is about the content before this edit.
      setVersionOutcome(null)
      await queryClient.invalidateQueries({ queryKey: QUIZZES_QUERY })
    },
    failed: (error) => {
      if (isStale(error)) markStale()
    },
    stale,
  }), [quizId, queryClient, draft.data, stale, markStale])

  const reload = async () => {
    known.current = null
    await queryClient.invalidateQueries({ queryKey: QUIZZES_QUERY })
    setStale(false)
    setGeneration((g) => g + 1)
  }

  const version = useMutation({
    mutationFn: () => publishVersion(quizId, editing.revision()),
    onMutate: () => setVersionOutcome(null),
    onSuccess: async (output) => {
      // Q73a: the languages left out of the version, said once with it.
      setVersionOutcome({ label: output.label, incomplete: output.incomplete ?? [] })
      notify.success(t('editor.versionSaved', { label: output.label }))
      await queryClient.invalidateQueries({ queryKey: QUIZZES_QUERY })
      await queryClient.invalidateQueries({ queryKey: versionsQuery(quizId) })
    },
    onError: (error) => {
      editing.failed(error)
      setVersionOutcome({ problems: formProblem(error, []) })
    },
  })

  const remove = () => deleteQuiz(quizId, editing.revision()).then(
    async () => {
      notify.success(t('editor.deleted'))
      navigate(QUIZZES_PATH)
      await queryClient.invalidateQueries({ queryKey: QUIZZES_QUERY })
    },
    (error: unknown) => failedRemoval(editing, error),
  )

  if (draft.isPending) return <PageState kind="loading" />
  if (draft.isError) {
    return <PageState kind="error" titleAs="h1" description={tQb('failed')}
      action={<Button variant="outline" onClick={() => void draft.refetch()}>{tQb('retry')}</Button>} />
  }
  if (!draft.data) {
    return <PageState kind="notFound" titleAs="h1" description={t('editor.notFound')}
      action={<Button variant="outline" onClick={() => navigate(QUIZZES_PATH)}>{t('editor.backToList')}</Button>} />
  }
  const { quiz, questions, materials } = draft.data
  const versionProblems = versionOutcome?.problems ?? []

  return (
    <EditingProvider value={editing}>
      <section className="flex flex-col gap-4" data-testid="quiz-editor" data-revision={quiz.revision}>
        <PageHeader
          breadcrumb={
            <nav aria-label={t('editor.breadcrumb')} className="text-sm">
              <Link to={QUIZZES_PATH} className="text-muted-foreground underline-offset-4 hover:underline">
                {t('quizzes.title')}
              </Link>
            </nav>
          }
          title={
            <span className="flex flex-wrap items-center gap-2">
              <span data-testid="editor-title">{quiz.title}</span>
              {quiz.kind === 'SURVEY' && <Badge variant="secondary" data-testid="editor-survey">{t('quizzes.kinds.SURVEY')}</Badge>}
            </span>
          }
          description={
            <span className="flex flex-wrap items-center gap-2" data-testid="editor-state">
              <Badge variant={quiz.status === 'VERSIONED' ? 'secondary' : 'outline'}>{statusLabel(quiz.status)}</Badge>
              {quiz.versionLabel && <Badge variant="outline" data-testid="editor-version">{quiz.versionLabel}</Badge>}
              {quiz.versionLabel && quiz.changedSinceVersion && (
                <span className="text-muted-foreground text-sm">{t('quizzes.changedSinceVersion')}</span>
              )}
              <span className="text-muted-foreground text-sm">
                {t('editor.editedAt', { time: formatDateTime(quiz.editedAt) })}
              </span>
            </span>
          }
          actions={
            <div className="flex flex-wrap gap-2">
              <Button disabled={version.isPending || stale} aria-busy={version.isPending || undefined}
                data-testid="save-version" onClick={() => version.mutate()}>
                <Save aria-hidden />
                {t('editor.saveVersion')}
              </Button>
              <PreviewButton to={previewOfQuiz(quiz.quizId)} />
              <Button variant="outline" disabled={stale} data-testid="copy-quiz" onClick={() => setCopying(true)}>
                <Copy aria-hidden />
                {t('editor.copy')}
              </Button>
              <ConfirmDialog destructive title={t('editor.deleteTitle')} description={t('editor.deleteText')}
                confirmLabel={t('editor.delete')} cancelLabel={t('common.cancel')} onConfirm={remove}
                trigger={
                  <Button variant="outline" disabled={stale} data-testid="delete-quiz">
                    <Trash2 aria-hidden />
                    {t('editor.delete')}
                  </Button>
                } />
            </div>
          } />
        {stale && (
          <Alert variant="warning" ref={staleRef} tabIndex={-1} data-testid="stale">
            <AlertTitle>{t('editor.staleTitle')}</AlertTitle>
            <AlertDescription className="flex flex-col items-start gap-3">
              <span>{t('editor.staleText')}</span>
              <Button size="sm" data-testid="reload" onClick={() => void reload()}>
                <RefreshCw aria-hidden />
                {t('editor.reload')}
              </Button>
            </AlertDescription>
          </Alert>
        )}
        {versionProblems.length > 0 && (
          <div className="flex flex-col gap-2">
            <p className="text-sm font-medium">{t('editor.versionRefused')}</p>
            <ProblemList problems={versionProblems} testId="version-problems" />
          </div>
        )}
        {versionOutcome?.label && (
          <p role="status" className="text-success flex items-center gap-2 text-sm" data-testid="version-saved">
            <CircleCheck aria-hidden className="size-4" />
            {t('editor.versionSaved', { label: versionOutcome.label })}
          </p>
        )}
        {(versionOutcome?.incomplete?.length ?? 0) > 0 && (
          <Alert variant="warning" data-testid="version-incomplete">
            <AlertTitle>{t('translations.leftOutTitle')}</AlertTitle>
            <AlertDescription>
              <ul className="list-disc pl-4">
                {versionOutcome!.incomplete!.map((status) => (
                  <li key={status.language}>{t('translations.leftOut', {
                    language: tQb(`languages.${status.language}`), missing: status.missing.length,
                    stale: status.stale.length, materials: status.materials.length })}</li>
                ))}
              </ul>
            </AlertDescription>
          </Alert>
        )}
        <Tabs value={tab} onValueChange={(value) => setTab(value as Tab)}>
          <TabsList className="flex-wrap">
            <TabsTrigger value="quiz" data-testid="tab-quiz">{t('editor.tabs.quiz')}</TabsTrigger>
            <TabsTrigger value="questions" data-testid="tab-questions">
              {t('editor.tabs.questionsCount', { n: questions.length })}
            </TabsTrigger>
            <TabsTrigger value="materials" data-testid="tab-materials">
              {t('editor.tabs.materialsCount', { n: materials.length })}
            </TabsTrigger>
            {/* Q73a: the quiz's other languages. */}
            <TabsTrigger value="languages" data-testid="tab-languages">
              {quiz.translationLanguages.length > 0
                ? t('editor.tabs.languagesCount', { n: quiz.translationLanguages.length + 1 })
                : t('editor.tabs.languages')}
            </TabsTrigger>
            <TabsTrigger value="versions" data-testid="tab-versions">{t('editor.tabs.versions')}</TabsTrigger>
            {/* Q60b: the products the quiz shows, outside its versions. */}
            <TabsTrigger value="products" data-testid="tab-products">{t('products.quiz.tab')}</TabsTrigger>
          </TabsList>
          <TabsContent value="quiz" className="flex flex-col gap-6 pt-4">
            <HeadingForm key={generation} quiz={quiz} />
            <AiCoverPanel quiz={quiz} />
          </TabsContent>
          <TabsContent value="questions" className="flex flex-col gap-4 pt-4">
            {/* AI does not write a survey's questions (Q12, D-Q12-9). */}
            {quiz.kind !== 'SURVEY' && <AiQuestionsPanel quiz={quiz} materials={materials} />}
            <QuestionsPanel questions={questions} survey={quiz.kind === 'SURVEY'} />
          </TabsContent>
          <TabsContent value="materials" className="pt-4">
            <MaterialsPanel materials={materials} />
          </TabsContent>
          <TabsContent value="languages" className="pt-4">
            <TranslationsPanel draft={draft.data} generation={generation} />
          </TabsContent>
          <TabsContent value="versions" className="pt-4">
            <VersionsPanel quizId={quizId} stale={stale} />
          </TabsContent>
          <TabsContent value="products" className="pt-4">
            <QuizProductsPanel quizId={quizId} questions={questions} />
          </TabsContent>
        </Tabs>
        {copying && <CopyDialog quiz={quiz} open onOpenChange={setCopying} />}
      </section>
    </EditingProvider>
  )
}
