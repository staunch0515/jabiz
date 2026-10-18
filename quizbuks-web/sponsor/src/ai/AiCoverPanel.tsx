import { ApiError, formatDateTime } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  ConfirmDialog,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Label,
  notify,
  RadioGroup,
  RadioGroupItem,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Spinner,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ImageIcon } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import type { Quiz } from '../content/api'
import { Thumbnail } from '../content/ContentImage'
import { formProblem, ProblemList, useEditing } from '../content/editing'
import { SPONSOR } from '../messages'
import { QB } from '../qb/messages'
import {
  AI_NOTICE_QUERY,
  AI_QUERY,
  AI_USAGE_QUERY,
  AiNoticeSection,
  consoleLanguage,
  failureText,
  useAiNotice,
} from './AiQuestionsPanel'
import {
  acceptAiCover,
  cancelAiJob,
  discardAiJob,
  readAiUsage,
  readLatestAiJob,
  requestAiCovers,
  stillPolling,
  type AiJob,
  type AiLanguage,
} from './api'
import { AI_CODE, AI_KINDS, AI_LANGUAGES, AI_LIMITS } from './ids'
import './messages'

export const aiCoverJobQuery = (quizId: string) => [...AI_QUERY, 'cover', quizId] as const

/** "Covers with AI": the language of the covers' text and, on first use, the AI notice. */
function CoverDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const editing = useEditing()
  const id = useId()
  const [language, setLanguage] = useState<AiLanguage>(() => consoleLanguage(i18n.language))
  const [agreed, setAgreed] = useState(false)
  const [local, setLocal] = useState<string | null>(null)
  const notice = useAiNotice(open)

  const request = useMutation({
    mutationFn: () => requestAiCovers({
      quizId: editing.quizId,
      language,
      aiNoticeVersionNo: notice.data?.mustAccept ? notice.data.versionNo : null,
    }),
    onSuccess: async () => {
      onOpenChange(false)
      await queryClient.invalidateQueries({ queryKey: AI_QUERY })
    },
    onError: async (error) => {
      if (error instanceof ApiError && error.violations.some((v) => v.ruleCode === AI_CODE.noticeRequired)) {
        setAgreed(false)
        await queryClient.invalidateQueries({ queryKey: AI_NOTICE_QUERY })
      }
    },
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
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
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-xl" data-testid="ai-cover-dialog">
        <form noValidate onSubmit={submit} className="flex flex-col gap-5">
          <DialogHeader>
            <DialogTitle>{t('ai.cover.dialogTitle')}</DialogTitle>
            <DialogDescription>{t('ai.cover.dialogDescription', { n: AI_LIMITS.covers })}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label id={`${id}-language`}>{t('ai.cover.language')}</Label>
            <Select value={language} onValueChange={(value) => setLanguage(value as AiLanguage)}>
              <SelectTrigger aria-labelledby={`${id}-language`} className="w-48" data-testid="ai-cover-language">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {AI_LANGUAGES.map((l) => <SelectItem key={l} value={l}>{t(`ai.dialog.languages.${l}`)}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
          <p className="text-muted-foreground text-sm" data-testid="ai-cover-sent">{t('ai.cover.sent')}</p>
          <AiNoticeSection notice={notice} agreed={agreed} onAgreed={setAgreed} />
          {local && <p role="alert" className="text-destructive text-sm" data-testid="ai-cover-local-problem">{local}</p>}
          <ProblemList problems={formProblem(request.error, [])} testId="ai-cover-request-error" />
          <DialogFooter>
            <Button type="button" variant="outline" disabled={request.isPending} onClick={() => onOpenChange(false)}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={request.isPending || notice.isPending}
              aria-busy={request.isPending || undefined} data-testid="ai-cover-submit">
              <ImageIcon aria-hidden />
              {t('ai.cover.submit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * The candidate covers of a ready job: the pictures the server drew of the AI's designs, one chosen with a radio
 * button and applied as the quiz's cover; or all discarded.
 */
function Choice({ job, onApplied }: { job: AiJob; onApplied?: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const editing = useEditing()
  const id = useId()
  const [chosen, setChosen] = useState<string>('')

  const apply = useMutation({
    mutationFn: () => acceptAiCover(job.aiJobId, editing.quizId, Number(chosen), editing.revision()),
    onSuccess: async (output) => {
      notify.success(t('ai.cover.applied'))
      await editing.edited(output)
      await queryClient.invalidateQueries({ queryKey: AI_QUERY })
      onApplied?.()
    },
    onError: editing.failed,
  })
  const discard = () => discardAiJob(job.aiJobId).then(async () => {
    notify.success(t('ai.cover.discarded'))
    await queryClient.invalidateQueries({ queryKey: AI_QUERY })
  }, (error: unknown) => {
    notify.error(error instanceof ApiError ? error.message : String(error))
  })

  return (
    <section className="flex flex-col gap-4" aria-labelledby={`${id}-title`} data-testid="ai-cover-choice">
      <div className="flex flex-col gap-1">
        <h3 id={`${id}-title`} className="font-medium">{t('ai.cover.chooseTitle')}</h3>
        <p className="text-muted-foreground text-sm">{t('ai.cover.chooseIntro')}</p>
        {job.expiresAt && <p className="text-muted-foreground text-xs" data-testid="ai-cover-expires">
          {t('ai.review.expires', { time: formatDateTime(job.expiresAt) })}</p>}
      </div>
      <RadioGroup value={chosen} onValueChange={setChosen} aria-labelledby={`${id}-title`}
        className="grid gap-3 sm:grid-cols-3">
        {job.covers.map((cover) => (
          <div key={cover.no} className="flex flex-col gap-2 rounded-md border p-2" data-testid="ai-cover-candidate">
            <Thumbnail fileId={cover.fileId} width={640} className="aspect-[40/21] w-full rounded-sm"
              alt={t('ai.cover.picture', { n: cover.no, text: cover.text })} />
            <div className="flex items-start gap-2">
              <RadioGroupItem id={`${id}-${cover.no}`} value={String(cover.no)} data-testid="ai-cover-pick" />
              <Label htmlFor={`${id}-${cover.no}`} className="flex flex-col items-start gap-0.5 leading-snug">
                <span>{t('ai.cover.candidate', { n: cover.no })}</span>
                {/* The design's text, as plain text: the picture shows it too. */}
                <span className="text-muted-foreground text-xs font-normal">{cover.text}</span>
              </Label>
            </div>
          </div>
        ))}
      </RadioGroup>
      <ProblemList problems={formProblem(apply.error, [])} testId="ai-cover-apply-error" />
      <div className="flex flex-wrap gap-2">
        <Button disabled={!chosen || apply.isPending || editing.stale} aria-busy={apply.isPending || undefined}
          data-testid="ai-cover-apply" onClick={() => apply.mutate()}>
          {chosen ? t('ai.cover.apply') : t('ai.cover.applyNone')}
        </Button>
        <ConfirmDialog destructive title={t('ai.cover.discardTitle')} description={t('ai.cover.discardText')}
          confirmLabel={t('ai.review.discard')} cancelLabel={t('common.cancel')} onConfirm={discard}
          trigger={<Button variant="outline" disabled={apply.isPending} data-testid="ai-cover-discard">
            {t('ai.review.discard')}</Button>} />
      </div>
    </section>
  )
}

/** The text of a failed cover job: the questions' texts, except where covers differ. */
function coverFailure(t: (key: string) => string, code: string | null): string {
  return code === 'QB_AI_NO_CANDIDATES' ? t('ai.cover.noCandidates') : failureText(t, code)
}

/**
 * The quiz page's AI covers (Q8b, M-25): asking for {@link AI_LIMITS.covers} candidates drawn from the quiz's title and
 * introduction, the job while it runs (every five seconds for at most five minutes, not after the page is left), the
 * pictures to choose from, or why it failed. The chosen cover is written as a quiz edit; `onApplied` lets the heading
 * show it.
 */
export function AiCoverPanel({ quiz, onApplied }: { quiz: Quiz; onApplied?: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const editing = useEditing()
  const [asking, setAsking] = useState(false)
  const usage = useQuery({ queryKey: AI_USAGE_QUERY, queryFn: () => readAiUsage() })
  const job = useQuery({
    queryKey: aiCoverJobQuery(quiz.quizId),
    queryFn: () => readLatestAiJob(quiz.quizId, AI_KINDS.cover),
    refetchInterval: (query) => (stillPolling(query.state.data ?? null) ? AI_LIMITS.pollMs : false),
  })
  const latest = job.data ?? null
  const cancel = useMutation({
    mutationFn: () => cancelAiJob(latest!.aiJobId),
    onSettled: () => queryClient.invalidateQueries({ queryKey: AI_QUERY }),
  })
  const enabled = usage.data?.enabled ?? false
  const running = latest?.status === 'QUEUED'
  const remaining = usage.data?.remainingTokens

  return (
    <Card className="max-w-2xl" data-testid="ai-cover-panel" data-status={latest?.status ?? 'NONE'}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <ImageIcon aria-hidden className="size-4" />
          {t('ai.cover.title')}
        </CardTitle>
        <CardDescription>{t('ai.cover.intro')}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {usage.isError && <p className="text-destructive text-sm">{tQb('failed')}</p>}
        {usage.isSuccess && !enabled && (
          <p className="text-muted-foreground text-sm" data-testid="ai-cover-off">{t('ai.off')}</p>
        )}
        {usage.isSuccess && enabled && (
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
            <Button variant="outline" disabled={running || editing.stale || remaining === 0}
              data-testid="ai-cover-generate" onClick={() => setAsking(true)}>
              <ImageIcon aria-hidden />
              {t('ai.cover.generate')}
            </Button>
            {remaining === 0 && <span className="text-muted-foreground text-sm">{t('ai.remainingNone')}</span>}
          </div>
        )}
        {running && (
          <div role="status" className="flex flex-wrap items-center gap-3 text-sm" data-testid="ai-cover-running">
            <Spinner />
            <span>{stillPolling(latest) ? t('ai.cover.running') : t('ai.runningLong')}</span>
            <Button size="sm" variant="outline" disabled={cancel.isPending} data-testid="ai-cover-cancel"
              onClick={() => cancel.mutate()}>{t('ai.cancel')}</Button>
          </div>
        )}
        {latest?.status === 'CANCELLED' && !latest.finishedAt && (
          <p className="text-muted-foreground text-sm" data-testid="ai-cover-cancelled">{t('ai.cancelled')}</p>
        )}
        {latest?.status === 'FAILED' && (
          <Alert variant="warning" data-testid="ai-cover-failed" data-code={latest.failureCode ?? undefined}>
            <AlertTitle>{t('ai.cover.title')}</AlertTitle>
            <AlertDescription>{coverFailure((key) => t(key), latest.failureCode)}</AlertDescription>
          </Alert>
        )}
        {latest?.status === 'EXPIRED' && (
          <p className="text-muted-foreground text-sm" data-testid="ai-cover-expired">{t('ai.cover.expired')}</p>
        )}
        {latest?.status === 'READY' && <Choice key={latest.aiJobId} job={latest} onApplied={onApplied} />}
      </CardContent>
      {asking && <CoverDialog open onOpenChange={setAsking} />}
    </Card>
  )
}
