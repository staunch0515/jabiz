import { ApiError, formatDateTime } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  Button,
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
  Spinner,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Languages } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { formProblem, ProblemList, useEditing } from '../content/editing'
import { SPONSOR } from '../messages'
import type { ContentLanguage } from '../qb/api'
import { QB } from '../qb/messages'
import { AI_NOTICE_QUERY, AI_QUERY, AI_USAGE_QUERY, AiNoticeSection, failureText, useAiNotice } from './AiQuestionsPanel'
import {
  cancelAiJob,
  discardAiJob,
  readAiUsage,
  readLatestAiJob,
  requestAiTranslation,
  stillPolling,
  type AiJob,
} from './api'
import { AI_CODE, AI_KINDS, AI_LIMITS } from './ids'
import './messages'

export const aiTranslationJobQuery = (quizId: string) => [...AI_QUERY, 'translation', quizId] as const

/**
 * The quiz's latest AI translation job (Q73d-1), read again every few seconds while it runs (not after the page is
 * left). One translation job a quiz at a time, of one of its languages.
 */
export function useAiTranslationJob(quizId: string) {
  return useQuery({
    queryKey: aiTranslationJobQuery(quizId),
    queryFn: () => readLatestAiJob(quizId, AI_KINDS.translation),
    refetchInterval: (query) => (stillPolling(query.state.data ?? null) ? AI_LIMITS.pollMs : false),
  })
}

/** Q73d-2: the latest AI job of one publication's texts, read as the quiz's translation job is. */
export function useAiPublicationTextsJob(quizId: string | null, publicationId: string | null) {
  return useQuery({
    queryKey: [...AI_QUERY, 'publication-texts', publicationId] as const,
    queryFn: () => readLatestAiJob(quizId!, AI_KINDS.publicationTexts, publicationId),
    enabled: !!quizId && !!publicationId,
    refetchInterval: (query) => (stillPolling(query.state.data ?? null) ? AI_LIMITS.pollMs : false),
  })
}

/**
 * "Translate with AI": which texts, what is sent and, on first use, the AI notice. `send` asks (a quiz's texts by
 * default; Q73d-2: a publication's).
 */
export function TranslateDialog({ language, onOpenChange, send, sent }: {
  language: ContentLanguage
  onOpenChange: (open: boolean) => void
  send?: (all: boolean, aiNoticeVersionNo: number | null) => Promise<unknown>
  /** What is sent, when it is not the quiz's texts. */
  sent?: string
}) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const editing = useEditing()
  const id = useId()
  const [scope, setScope] = useState<'pending' | 'all'>('pending')
  const [agreed, setAgreed] = useState(false)
  const [local, setLocal] = useState<string | null>(null)
  const notice = useAiNotice(true)
  const name = tQb(`languages.${language}`)

  const request = useMutation({
    mutationFn: () => {
      const version = notice.data?.mustAccept ? notice.data.versionNo : null
      return send ? send(scope === 'all', version) : requestAiTranslation({
        quizId: editing.quizId, language, all: scope === 'all', aiNoticeVersionNo: version })
    },
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
    <Dialog open onOpenChange={(next) => {
      if (!request.isPending) onOpenChange(next)
    }}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-xl" data-testid="ai-translate-dialog">
        <form noValidate onSubmit={submit} className="flex flex-col gap-5">
          <DialogHeader>
            <DialogTitle>{t('ai.translate.dialogTitle', { language: name })}</DialogTitle>
            <DialogDescription>{t('ai.translate.dialogDescription')}</DialogDescription>
          </DialogHeader>
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1 text-sm font-medium">{t('ai.translate.scope')}</legend>
            <RadioGroup value={scope} onValueChange={(value) => setScope(value as 'pending' | 'all')}>
              {(['pending', 'all'] as const).map((value) => (
                <div key={value} className="flex items-center gap-2">
                  <RadioGroupItem id={`${id}-${value}`} value={value} data-testid={`ai-translate-scope-${value}`} />
                  <Label htmlFor={`${id}-${value}`} className="font-normal">{t(`ai.translate.${value}`)}</Label>
                </div>
              ))}
            </RadioGroup>
          </fieldset>
          <p className="text-muted-foreground text-sm" data-testid="ai-translate-sent">{sent ?? t('ai.translate.sent')}</p>
          <AiNoticeSection notice={notice} agreed={agreed} onAgreed={setAgreed} />
          {local && <p role="alert" className="text-destructive text-sm">{local}</p>}
          <ProblemList problems={formProblem(request.error, [])} testId="ai-translate-request-error" />
          <DialogFooter>
            <Button type="button" variant="outline" disabled={request.isPending} onClick={() => onOpenChange(false)}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={request.isPending || notice.isPending}
              aria-busy={request.isPending || undefined} data-testid="ai-translate-submit">
              <Languages aria-hidden />
              {t('ai.translate.submit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/** The text of a failed translation job: the questions' texts, except where translations differ. */
function translationFailure(t: (key: string) => string, code: string | null): string {
  return code === 'QB_AI_NO_CANDIDATES' ? t('ai.translate.noCandidates') : failureText(t, code)
}

/**
 * The AI translation of one of the quiz's languages (Q73d-1, docs/quizbuks/plans/Q73-content-languages.md §5): asking
 * for candidate translations, the job while it runs (and stopping it), why it failed, and the ready candidates'
 * summary with their discard. The candidates themselves show under each text of the translations' form, which saves
 * the ones the sponsor checked; a job of another language says so here.
 */
export function AiTranslatePanel({ language, job }: { language: ContentLanguage; job: AiJob | null }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const editing = useEditing()
  const [asking, setAsking] = useState(false)
  const usage = useQuery({ queryKey: AI_USAGE_QUERY, queryFn: () => readAiUsage(),
    refetchInterval: stillPolling(job) ? AI_LIMITS.pollMs : false })
  const cancel = useMutation({
    mutationFn: () => cancelAiJob(job!.aiJobId),
    onSettled: () => queryClient.invalidateQueries({ queryKey: AI_QUERY }),
  })
  const discard = () => discardAiJob(job!.aiJobId).then(async () => {
    notify.success(t('ai.translate.discarded'))
    await queryClient.invalidateQueries({ queryKey: AI_QUERY })
  }, (error: unknown) => {
    notify.error(error instanceof ApiError ? error.message : String(error))
  })
  const enabled = usage.data?.enabled ?? false
  const running = job?.status === 'QUEUED'
  const ready = job?.status === 'READY'
  const mine = job?.language === language
  const name = tQb(`languages.${language}`)

  return (
    <section className="flex flex-col gap-3 rounded-md border p-3" aria-label={t('ai.translate.title')}
      data-testid="ai-translate" data-status={job?.status ?? 'NONE'}>
      <div className="flex flex-col gap-1">
        <h3 className="flex items-center gap-2 text-sm font-medium">
          <Languages aria-hidden className="size-4" />
          {t('ai.translate.title')}
        </h3>
        <p className="text-muted-foreground text-sm">{t('ai.translate.intro', { language: name })}</p>
      </div>
      {usage.isError && <p className="text-destructive text-sm">{tQb('failed')}</p>}
      {usage.isSuccess && !enabled && (
        <p className="text-muted-foreground text-sm" data-testid="ai-translate-off">{t('ai.off')}</p>
      )}
      {usage.isSuccess && enabled && !running && !(ready && mine) && (
        <Button variant="outline" className="w-fit" disabled={editing.stale || usage.data.remainingTokens === 0}
          data-testid="ai-translate-generate" onClick={() => setAsking(true)}>
          <Languages aria-hidden />
          {t('ai.translate.generate')}
        </Button>
      )}
      {running && (
        <div role="status" className="flex flex-wrap items-center gap-3 text-sm" data-testid="ai-translate-running">
          <Spinner />
          <span>{!enabled && usage.isSuccess ? t('ai.held')
            : stillPolling(job) ? t('ai.translate.running') : t('ai.runningLong')}</span>
          <Button size="sm" variant="outline" disabled={cancel.isPending} data-testid="ai-translate-cancel"
            onClick={() => cancel.mutate()}>{t('ai.cancel')}</Button>
        </div>
      )}
      {job?.status === 'FAILED' && (
        <Alert variant="warning" data-testid="ai-translate-failed" data-code={job.failureCode ?? undefined}>
          <AlertTitle>{t('ai.translate.title')}</AlertTitle>
          <AlertDescription>{translationFailure((key) => t(key), job.failureCode)}</AlertDescription>
        </Alert>
      )}
      {job?.status === 'EXPIRED' && (
        <p className="text-muted-foreground text-sm" data-testid="ai-translate-expired">{t('ai.translate.expired')}</p>
      )}
      {ready && !mine && job.language && (
        <p className="text-muted-foreground text-sm" data-testid="ai-translate-other">
          {t('ai.translate.otherLanguage', { language: tQb(`languages.${job.language}`) })}
        </p>
      )}
      {ready && mine && (
        <div className="flex flex-wrap items-center gap-3" data-testid="ai-translate-ready">
          <p className="text-sm">{t('ai.translate.ready', { count: job.translations.length })}</p>
          {job.expiresAt && <p className="text-muted-foreground text-xs">
            {t('ai.review.expires', { time: formatDateTime(job.expiresAt) })}</p>}
          <ConfirmDialog destructive title={t('ai.translate.discardTitle')} description={t('ai.translate.discardText')}
            confirmLabel={t('ai.review.discard')} cancelLabel={t('common.cancel')} onConfirm={discard}
            trigger={<Button size="sm" variant="outline" data-testid="ai-translate-discard">
              {t('ai.review.discard')}</Button>} />
        </div>
      )}
      {asking && <TranslateDialog language={language} onOpenChange={setAsking} />}
    </section>
  )
}
