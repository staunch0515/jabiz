import { ApiError, formatDateTime } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Checkbox,
  ConfirmDialog,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  MarkdownView,
  notify,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Spinner,
  Textarea,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Sparkles } from 'lucide-react'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import type { Material, Quiz } from '../content/api'
import { formProblem, ProblemList, useEditing } from '../content/editing'
import { LIMITS } from '../content/limits'
import { SPONSOR } from '../messages'
import { pickText, readLegal, readMyConsents } from '../qb/legal'
import { QB } from '../qb/messages'
import {
  acceptAiQuestions,
  cancelAiJob,
  discardAiJob,
  fitsInput,
  materialBytes,
  readAiUsage,
  readLatestAiJob,
  requestAiQuestions,
  sentAsFile,
  stillPolling,
  type AiCandidate,
  type AiJob,
  type AiLanguage,
  type AiOption,
  type Difficulty,
} from './api'
import { AI_CODE, AI_KINDS, AI_LANGUAGES, AI_LIMITS, DIFFICULTIES } from './ids'
import './messages'
import { playSound } from '../qb/sound'

export const AI_QUERY = ['qb', 'ai'] as const
export const aiJobQuery = (quizId: string) => [...AI_QUERY, 'job', quizId] as const
export const AI_USAGE_QUERY = [...AI_QUERY, 'usage'] as const
export const AI_NOTICE_QUERY = [...AI_QUERY, 'notice'] as const

const kb = (bytes: number) => (Math.ceil(bytes / 102.4) / 10).toLocaleString()

/** The language of the console as one the AI writes in. */
export function consoleLanguage(language: string): AiLanguage {
  const base = language.split('-')[0]
  return (AI_LANGUAGES as readonly string[]).includes(base) ? (base as AiLanguage) : 'en'
}

/**
 * The AI notice in effect and whether the sponsor must accept it before asking (A-11): read only when the dialog is
 * open. Shown as Markdown in the console's language (Japanese governs).
 */
export function useAiNotice(open: boolean) {
  return useQuery({
    queryKey: AI_NOTICE_QUERY,
    enabled: open,
    queryFn: async () => {
      const [standing] = await readMyConsents(['AI_NOTICE'])
      const text = standing?.mustAccept ? await readLegal('AI_NOTICE') : null
      return { mustAccept: standing?.mustAccept ?? false, versionNo: standing?.versionNo ?? null, text }
    },
  })
}

/**
 * The AI notice as the dialogs show it on first use (A-11): its text in the console's language and the sponsor's
 * agreement. The request sends the version only when it was agreed to.
 */
export function AiNoticeSection({ notice, agreed, onAgreed }: {
  notice: ReturnType<typeof useAiNotice>
  agreed: boolean
  onAgreed: (agreed: boolean) => void
}) {
  const { t, i18n } = useTranslation(SPONSOR)
  const id = useId()
  const noticeText = notice.data?.text
  const body = pickText(noticeText?.body, i18n.language)
  const title = pickText(noticeText?.title, i18n.language)
  return (
    <>
      {notice.isPending && <p className="text-muted-foreground text-sm">{t('ai.notice.loading')}</p>}
      {notice.isError && <p role="alert" className="text-destructive text-sm">{t('ai.notice.failed')}</p>}
      {notice.data?.mustAccept && (
        <section className="flex flex-col gap-3 rounded-md border p-4" aria-labelledby={`${id}-notice`}
          data-testid="ai-notice">
          <h3 id={`${id}-notice`} className="font-medium">{t('ai.notice.title')}</h3>
          {title.text && <p className="text-sm font-medium" lang={title.lang}>{title.text}
            {notice.data.versionNo && <span className="text-muted-foreground font-normal">
              {' '}({t('ai.notice.version', { n: notice.data.versionNo })})</span>}</p>}
          {body.text && (
            <div className="max-h-60 overflow-y-auto rounded-sm border p-3 text-sm" tabIndex={0}
              aria-label={t('ai.notice.title')} lang={body.lang}>
              <MarkdownView text={body.text} lang={body.lang} />
            </div>
          )}
          <div className="flex items-start gap-2">
            <Checkbox id={`${id}-agree`} checked={agreed} data-testid="ai-notice-agree"
              onCheckedChange={(checked) => onAgreed(checked === true)} />
            <Label htmlFor={`${id}-agree`} className="leading-snug">{t('ai.notice.agree')}</Label>
          </div>
        </section>
      )}
    </>
  )
}

/** "Generate with AI": what to send and what to ask for, the notice on first use, the request. */
function RequestDialog({ quiz, materials, maxCount, open, onOpenChange }: {
  quiz: Quiz
  materials: Material[]
  /** The most questions a request may ask for now (the usage card's; the server decides). */
  maxCount: number
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { t, i18n } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const editing = useEditing()
  const id = useId()
  const [chosen, setChosen] = useState<string[]>(() => materials.map((m) => m.materialId))
  const [count, setCount] = useState(() => String(Math.min(5, maxCount)))
  const [difficulty, setDifficulty] = useState<Difficulty>('MEDIUM')
  const [language, setLanguage] = useState<AiLanguage>(() => consoleLanguage(i18n.language))
  const [avoidExisting, setAvoidExisting] = useState(true)
  const [agreed, setAgreed] = useState(false)
  const [local, setLocal] = useState<string | null>(null)
  const notice = useAiNotice(open)

  const sizes = useMemo(() => new Map(materials.map((m) => [m.materialId, materialBytes(m)])), [materials])
  const encoder = useMemo(() => new TextEncoder(), [])
  const total = encoder.encode(`${quiz.title}${quiz.intro ?? ''}`).length
    + chosen.reduce((sum, materialId) => sum + (sizes.get(materialId) ?? 0), 0)
  const tooLarge = !fitsInput(total)

  const request = useMutation({
    mutationFn: () => requestAiQuestions({
      quizId: editing.quizId,
      materialIds: materials.filter((m) => chosen.includes(m.materialId)).map((m) => m.materialId),
      count: Number(count),
      difficulty,
      language,
      avoidExisting,
      aiNoticeVersionNo: notice.data?.mustAccept ? notice.data.versionNo : null,
    }),
    onSuccess: async () => {
      onOpenChange(false)
      await queryClient.invalidateQueries({ queryKey: AI_QUERY })
    },
    onError: async (error) => {
      // A newer notice took effect meanwhile: read it again, so that it is shown.
      if (error instanceof ApiError && error.violations.some((v) => v.ruleCode === AI_CODE.noticeRequired)) {
        setAgreed(false)
        await queryClient.invalidateQueries({ queryKey: AI_NOTICE_QUERY })
      }
    },
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const n = Number(count)
    if (!Number.isInteger(n) || n < 1 || n > maxCount) {
      setLocal(t('ai.dialog.countRange', { max: maxCount }))
      return
    }
    if (notice.data?.mustAccept && !agreed) {
      setLocal(t('ai.notice.required'))
      return
    }
    setLocal(null)
    request.mutate()
  }

  return (
    <Dialog open={open} onOpenChange={(next) => {
      if (!request.isPending) onOpenChange(next)
    }}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl" data-testid="ai-dialog">
        <form noValidate onSubmit={submit} className="flex flex-col gap-5">
          <DialogHeader>
            <DialogTitle>{t('ai.dialog.title')}</DialogTitle>
            <DialogDescription>{t('ai.dialog.description')}</DialogDescription>
          </DialogHeader>
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-2 text-sm font-medium">{t('ai.dialog.materials')}</legend>
            {materials.length === 0 && <p className="text-muted-foreground text-sm">{t('ai.dialog.noMaterials')}</p>}
            <ul className="flex flex-col gap-2">
              {materials.map((material, index) => (
                <li key={material.materialId} className="flex items-start gap-2" data-testid="ai-material">
                  <Checkbox id={`${id}-m-${material.materialId}`} checked={chosen.includes(material.materialId)}
                    data-testid="ai-material-choose" onCheckedChange={(checked) => setChosen((list) =>
                      checked === true ? [...list, material.materialId] : list.filter((m) => m !== material.materialId))} />
                  <Label htmlFor={`${id}-m-${material.materialId}`} className="flex flex-col items-start gap-0.5">
                    <span>{t('ai.review.material', { n: index + 1 })}: {material.title ?? '—'}</span>
                    <span className="text-muted-foreground text-xs font-normal">
                      {t('ai.dialog.materialSize', { size: kb(sizes.get(material.materialId) ?? 0) })}
                      {sentAsFile(material)
                        ? ` · ${t('ai.dialog.asFile', { mb: AI_LIMITS.maxPdfMb, pages: AI_LIMITS.maxPdfPages })}`
                        : material.kind !== 'ARTICLE' && ` · ${t('ai.dialog.titleOnly')}`}
                    </span>
                  </Label>
                </li>
              ))}
            </ul>
            <p className={tooLarge ? 'text-destructive text-sm' : 'text-muted-foreground text-sm'}
              data-testid="ai-size" data-too-large={tooLarge || undefined}>
              {t('ai.dialog.size', { size: kb(total), max: kb(AI_LIMITS.maxInputBytes) })}
              {tooLarge && <> {t('ai.dialog.tooLarge')}</>}
            </p>
          </fieldset>
          <div className="grid gap-4 sm:grid-cols-3">
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-count`}>{t('ai.dialog.count')}</Label>
              <Input id={`${id}-count`} type="number" inputMode="numeric" min={1} max={maxCount} step={1}
                value={count} data-testid="ai-count" onChange={(event) => setCount(event.target.value)} />
            </div>
            <div className="flex flex-col gap-2">
              <Label id={`${id}-difficulty`}>{t('ai.dialog.difficulty')}</Label>
              <Select value={difficulty} onValueChange={(value) => setDifficulty(value as Difficulty)}>
                <SelectTrigger aria-labelledby={`${id}-difficulty`} data-testid="ai-difficulty">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {DIFFICULTIES.map((d) => <SelectItem key={d} value={d}>{t(`ai.dialog.difficulties.${d}`)}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-2">
              <Label id={`${id}-language`}>{t('ai.dialog.language')}</Label>
              <Select value={language} onValueChange={(value) => setLanguage(value as AiLanguage)}>
                <SelectTrigger aria-labelledby={`${id}-language`} data-testid="ai-language">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {AI_LANGUAGES.map((l) => <SelectItem key={l} value={l}>{t(`ai.dialog.languages.${l}`)}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
          </div>
          <div className="flex items-start gap-2">
            <Checkbox id={`${id}-avoid`} checked={avoidExisting} data-testid="ai-avoid"
              onCheckedChange={(checked) => setAvoidExisting(checked === true)} />
            <Label htmlFor={`${id}-avoid`} className="leading-snug">{t('ai.dialog.avoidExisting')}</Label>
          </div>
          <p className="text-muted-foreground text-sm" data-testid="ai-sent">{t('ai.dialog.sent')}</p>
          <AiNoticeSection notice={notice} agreed={agreed} onAgreed={setAgreed} />
          {local && <p role="alert" className="text-destructive text-sm" data-testid="ai-local-problem">{local}</p>}
          <ProblemList problems={formProblem(request.error, [])} testId="ai-request-error" />
          <DialogFooter>
            <Button type="button" variant="outline" disabled={request.isPending} onClick={() => onOpenChange(false)}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={request.isPending || tooLarge || notice.isPending}
              aria-busy={request.isPending || undefined} data-testid="ai-submit">
              <Sparkles aria-hidden />
              {t('ai.dialog.submit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

interface CandidateDraft {
  no: number
  stem: string
  points: string
  options: AiOption[]
  materials: number[]
  reviewed: boolean
}

const draftOf = (candidate: AiCandidate): CandidateDraft => ({
  no: candidate.no,
  stem: candidate.stem,
  points: String(candidate.points),
  options: candidate.options.map((o) => ({ ...o })),
  materials: candidate.materials,
  reviewed: false,
})

/**
 * The candidates of a ready job, each an editable card (plain text only: nothing the AI wrote is rendered as
 * Markdown or HTML) with "I have reviewed this question"; only the reviewed ones are added, as the sponsor left them.
 */
function Review({ job }: { job: AiJob }) {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const editing = useEditing()
  const id = useId()
  const [drafts, setDrafts] = useState<CandidateDraft[]>(() => job.candidates.map(draftOf))
  const reviewed = drafts.filter((d) => d.reviewed)
  const change = (no: number, patch: Partial<CandidateDraft>) =>
    setDrafts((list) => list.map((d) => (d.no === no ? { ...d, ...patch } : d)))
  const changeOption = (no: number, index: number, patch: Partial<AiOption>) =>
    setDrafts((list) => list.map((d) => (d.no === no
      ? { ...d, options: d.options.map((o, i) => (i === index ? { ...o, ...patch } : o)) } : d)))

  const accept = useMutation({
    mutationFn: () => acceptAiQuestions(job.aiJobId, editing.quizId, reviewed.map((d) => ({
      candidateNo: d.no,
      stem: d.stem,
      points: Number(d.points),
      options: d.options,
      reviewed: true as const,
    })), editing.revision()),
    onSuccess: async (output) => {
      notify.success(t('ai.review.added', { n: output.questionIds.length }))
      playSound('success')
      await editing.edited(output)
      await queryClient.invalidateQueries({ queryKey: AI_QUERY })
    },
    onError: editing.failed,
  })
  const discard = () => discardAiJob(job.aiJobId).then(async () => {
    notify.success(t('ai.review.discarded'))
    await queryClient.invalidateQueries({ queryKey: AI_QUERY })
  }, (error: unknown) => {
    notify.error(error instanceof ApiError ? error.message : String(error))
  })

  return (
    <section className="flex flex-col gap-4" aria-labelledby={`${id}-title`} data-testid="ai-review">
      <div className="flex flex-col gap-1">
        <h3 id={`${id}-title`} className="font-medium">{t('ai.review.title')}</h3>
        <p className="text-muted-foreground text-sm">{t('ai.review.intro')}</p>
        {job.expiresAt && <p className="text-muted-foreground text-xs" data-testid="ai-expires">
          {t('ai.review.expires', { time: formatDateTime(job.expiresAt) })}</p>}
        {job.invalidCount > 0 && <p className="text-muted-foreground text-xs" data-testid="ai-invalid">
          {t('ai.review.invalid', { n: job.invalidCount })}</p>}
      </div>
      <ol className="flex flex-col gap-3">
        {drafts.map((draft) => {
          const at = `${id}-${draft.no}`
          return (
            <li key={draft.no}>
              <Card data-testid="ai-candidate" data-reviewed={draft.reviewed || undefined}>
                <CardContent className="flex flex-col gap-3">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <h4 className="text-sm font-medium">{t('ai.review.question', { n: draft.no })}</h4>
                    <Badge variant="outline">
                      {draft.materials.length > 0
                        ? t('ai.review.basedOn', { list: draft.materials.map((n) => t('ai.review.material', { n }))
                          .join(', ') })
                        : t('ai.review.basedOnNone')}
                    </Badge>
                  </div>
                  <div className="flex flex-col gap-2">
                    <Label htmlFor={`${at}-stem`}>{t('ai.review.stem')}</Label>
                    <Textarea id={`${at}-stem`} rows={2} value={draft.stem} maxLength={LIMITS.stem}
                      data-testid="ai-candidate-stem" onChange={(event) => change(draft.no, { stem: event.target.value })} />
                  </div>
                  <fieldset className="flex flex-col gap-2">
                    <legend className="sr-only">{t('ai.review.question', { n: draft.no })}</legend>
                    {draft.options.map((option, index) => (
                      <div key={index} className="flex flex-wrap items-center gap-2" data-testid="ai-candidate-option">
                        <Label htmlFor={`${at}-o${index}`} className="w-20 shrink-0">
                          {t('ai.review.option', { n: index + 1 })}
                        </Label>
                        <Input id={`${at}-o${index}`} className="min-w-0 flex-1" value={option.text}
                          maxLength={LIMITS.option} autoComplete="off" data-testid="ai-candidate-option-text"
                          onChange={(event) => changeOption(draft.no, index, { text: event.target.value })} />
                        <div className="flex items-center gap-1">
                          <Checkbox id={`${at}-c${index}`} checked={option.correct}
                            data-testid="ai-candidate-option-correct"
                            onCheckedChange={(checked) => changeOption(draft.no, index, { correct: checked === true })} />
                          <Label htmlFor={`${at}-c${index}`}>{t('ai.review.correct')}</Label>
                        </div>
                      </div>
                    ))}
                  </fieldset>
                  <div className="flex flex-wrap items-end justify-between gap-3">
                    <div className="flex flex-col gap-2">
                      <Label htmlFor={`${at}-points`}>{t('ai.review.points')}</Label>
                      <Input id={`${at}-points`} type="number" inputMode="numeric" className="w-28" min={LIMITS.minPoints}
                        max={LIMITS.maxPoints} value={draft.points} data-testid="ai-candidate-points"
                        onChange={(event) => change(draft.no, { points: event.target.value })} />
                    </div>
                    <div className="flex items-center gap-2">
                      <Checkbox id={`${at}-reviewed`} checked={draft.reviewed} data-testid="ai-candidate-reviewed"
                        onCheckedChange={(checked) => change(draft.no, { reviewed: checked === true })} />
                      <Label htmlFor={`${at}-reviewed`}>{t('ai.review.reviewed')}</Label>
                    </div>
                  </div>
                </CardContent>
              </Card>
            </li>
          )
        })}
      </ol>
      <ProblemList problems={formProblem(accept.error, [])} testId="ai-accept-error" />
      <div className="flex flex-wrap gap-2">
        <Button disabled={reviewed.length === 0 || accept.isPending || editing.stale}
          aria-busy={accept.isPending || undefined} data-testid="ai-accept" onClick={() => accept.mutate()}>
          {reviewed.length === 0 ? t('ai.review.addNone') : t('ai.review.add', { n: reviewed.length })}
        </Button>
        <ConfirmDialog destructive title={t('ai.review.discardTitle')} description={t('ai.review.discardText')}
          confirmLabel={t('ai.review.discard')} cancelLabel={t('common.cancel')} onConfirm={discard}
          trigger={<Button variant="outline" disabled={accept.isPending} data-testid="ai-discard">
            {t('ai.review.discard')}</Button>} />
      </div>
    </section>
  )
}

/** The text of a failed job, in the console's language. */
export function failureText(t: (key: string) => string, code: string | null): string {
  const known = ['QB_AI_REFUSED', 'QB_AI_TRUNCATED', 'QB_AI_NO_CANDIDATES', 'QB_AI_INPUT_TOO_LARGE',
    'QB_AI_PDF_TOO_LONG', 'QB_AI_PDF_UNREADABLE', 'QB_AI_COVER_FONT']
  return code && known.includes(code) ? t(`ai.failure.${code}`) : t('ai.failure.generic')
}

/**
 * The editor's AI panel (Q8a, M-26), above the questions: the request (with the month's allowance), the job while it
 * runs (looked at every five seconds for at most five minutes, and not after the page is left), its candidates to
 * review, or why it failed.
 */
export function AiQuestionsPanel({ quiz, materials }: { quiz: Quiz; materials: Material[] }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const editing = useEditing()
  const [asking, setAsking] = useState(false)
  const job = useQuery({
    queryKey: aiJobQuery(quiz.quizId),
    queryFn: () => readLatestAiJob(quiz.quizId, AI_KINDS.questions),
    refetchInterval: (query) => (stillPolling(query.state.data ?? null) ? AI_LIMITS.pollMs : false),
  })
  const latest = job.data ?? null
  // Looked at with the job (16v-2): whether AI is paused can change while the job waits.
  const usage = useQuery({ queryKey: AI_USAGE_QUERY, queryFn: () => readAiUsage(),
    refetchInterval: stillPolling(latest) ? AI_LIMITS.pollMs : false })
  const cancel = useMutation({
    mutationFn: () => cancelAiJob(latest!.aiJobId),
    onSettled: () => queryClient.invalidateQueries({ queryKey: AI_QUERY }),
  })
  const enabled = usage.data?.enabled ?? false
  const running = latest?.status === 'QUEUED'
  const remaining = usage.data?.remainingTokens

  return (
    <Card data-testid="ai-panel" data-status={latest?.status ?? 'NONE'}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <Sparkles aria-hidden className="size-4" />
          {t('ai.title')}
        </CardTitle>
        <CardDescription>{t('ai.intro')}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {usage.isError && <p className="text-destructive text-sm">{tQb('failed')}</p>}
        {usage.isSuccess && !enabled && <p className="text-muted-foreground text-sm" data-testid="ai-off">{t('ai.off')}</p>}
        {usage.isSuccess && enabled && (
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
            <Button disabled={running || editing.stale || remaining === 0} data-testid="ai-generate"
              onClick={() => setAsking(true)}>
              <Sparkles aria-hidden />
              {t('ai.generate')}
            </Button>
            <span className="text-muted-foreground text-sm" data-testid="ai-remaining">
              {remaining === 0 ? t('ai.remainingNone')
                : remaining != null ? t('ai.remaining', { tokens: remaining.toLocaleString() }) : null}
              {usage.data.dailyJobs != null && ` · ${t('ai.today', { n: usage.data.jobsToday,
                max: usage.data.dailyJobs })}`}
            </span>
          </div>
        )}
        {running && (
          <div role="status" className="flex flex-wrap items-center gap-3 text-sm" data-testid="ai-running">
            <Spinner />
            <span>{!enabled && usage.isSuccess ? t('ai.held')
              : stillPolling(latest) ? t('ai.running', { n: latest.count ?? 0 }) : t('ai.runningLong')}</span>
            <Button size="sm" variant="outline" disabled={cancel.isPending} data-testid="ai-cancel"
              onClick={() => cancel.mutate()}>{t('ai.cancel')}</Button>
          </div>
        )}
        {latest?.status === 'CANCELLED' && !latest.finishedAt && (
          <p className="text-muted-foreground text-sm" data-testid="ai-cancelled">{t('ai.cancelled')}</p>
        )}
        {latest?.status === 'FAILED' && (
          <Alert variant="warning" data-testid="ai-failed" data-code={latest.failureCode ?? undefined}>
            <AlertTitle>{t('ai.title')}</AlertTitle>
            <AlertDescription>{failureText((key) => t(key), latest.failureCode)}</AlertDescription>
          </Alert>
        )}
        {latest?.status === 'EXPIRED' && (
          <p className="text-muted-foreground text-sm" data-testid="ai-expired">{t('ai.failure.expired')}</p>
        )}
        {latest?.status === 'READY' && <Review key={latest.aiJobId} job={latest} />}
      </CardContent>
      {asking && <RequestDialog quiz={quiz} materials={materials}
        maxCount={usage.data?.maxCount ?? AI_LIMITS.maxCount} open onOpenChange={setAsking} />}
    </Card>
  )
}
