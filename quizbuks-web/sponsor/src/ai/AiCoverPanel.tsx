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
  notify,
  RadioGroup,
  RadioGroupItem,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Spinner,
  Textarea,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ImageIcon, RefreshCw } from 'lucide-react'
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
  type AiCoverParams,
  type AiCoverStyle,
  type AiJob,
  type AiLanguage,
} from './api'
import { AI_CODE, AI_KINDS, AI_LANGUAGES, AI_LIMITS } from './ids'
import './messages'

export const aiCoverJobQuery = (quizId: string) => [...AI_QUERY, 'cover', quizId] as const

const STYLES: readonly AiCoverStyle[] = ['ILLUSTRATION', 'GRAPHIC']

/**
 * "Cover with AI" (Q96): the style, what the picture should show, whether and which title it shows, the language of
 * that text and, on first use, the AI notice. Asked again from a cover, the dialog starts from what that one was asked
 * with; the cover not used yet is replaced (the server closes it with the new request).
 */
function CoverDialog({ open, onOpenChange, initial, replacing }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  initial: AiCoverParams | null
  replacing: boolean
}) {
  const { t, i18n } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const editing = useEditing()
  const id = useId()
  const [style, setStyle] = useState<AiCoverStyle>(initial?.style ?? 'ILLUSTRATION')
  const [requirements, setRequirements] = useState(initial?.requirements ?? '')
  const [titleOverlay, setTitleOverlay] = useState(initial?.titleOverlay ?? true)
  const [titleText, setTitleText] = useState(initial?.titleText ?? '')
  const [language, setLanguage] = useState<AiLanguage>(() => initial?.language ?? consoleLanguage(i18n.language))
  const [agreed, setAgreed] = useState(false)
  const [local, setLocal] = useState<string | null>(null)
  const notice = useAiNotice(open)
  // An illustration may show no text; a catalogue design always shows its text.
  const showsText = style === 'GRAPHIC' || titleOverlay

  const request = useMutation({
    mutationFn: () => requestAiCovers({
      quizId: editing.quizId,
      language,
      aiNoticeVersionNo: notice.data?.mustAccept ? notice.data.versionNo : null,
      style,
      requirements: requirements.trim() || null,
      titleOverlay: style === 'ILLUSTRATION' ? titleOverlay : true,
      titleText: showsText ? titleText.trim() || null : null,
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
            <DialogDescription>{t('ai.cover.dialogDescription')}</DialogDescription>
          </DialogHeader>
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-2 text-sm font-medium">{t('ai.cover.style')}</legend>
            <RadioGroup value={style} onValueChange={(value) => setStyle(value as AiCoverStyle)}
              className="grid gap-2 sm:grid-cols-2" data-testid="ai-cover-style">
              {STYLES.map((s) => (
                <div key={s} className="flex items-start gap-2 rounded-md border p-3">
                  <RadioGroupItem id={`${id}-style-${s}`} value={s} data-testid={`ai-cover-style-${s}`} />
                  <Label htmlFor={`${id}-style-${s}`} className="flex flex-col items-start gap-1 leading-snug">
                    <span>{t(`ai.cover.styles.${s}`)}</span>
                    <span className="text-muted-foreground text-xs font-normal">{t(`ai.cover.styleHints.${s}`)}</span>
                  </Label>
                </div>
              ))}
            </RadioGroup>
          </fieldset>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-requirements`}>{t('ai.cover.requirements')}</Label>
            <Textarea id={`${id}-requirements`} value={requirements} rows={4}
              maxLength={AI_LIMITS.coverRequirements} aria-describedby={`${id}-requirements-hint`}
              placeholder={t('ai.cover.requirementsPlaceholder')} data-testid="ai-cover-requirements"
              onChange={(event) => setRequirements(event.target.value)} />
            <p id={`${id}-requirements-hint`} className="text-muted-foreground flex justify-between gap-2 text-xs">
              <span>{t('ai.cover.requirementsHint')}</span>
              <span>{t('ai.cover.count', { n: [...requirements].length, max: AI_LIMITS.coverRequirements })}</span>
            </p>
          </div>
          {style === 'ILLUSTRATION' && (
            <div className="flex items-center gap-2">
              <Checkbox id={`${id}-overlay`} checked={titleOverlay} data-testid="ai-cover-overlay"
                onCheckedChange={(checked) => setTitleOverlay(checked === true)} />
              <Label htmlFor={`${id}-overlay`}>{t('ai.cover.titleOverlay')}</Label>
            </div>
          )}
          {showsText && (
            <div className="grid gap-4 sm:grid-cols-[1fr_auto]">
              <div className="flex flex-col gap-2">
                <Label htmlFor={`${id}-text`}>{t('ai.cover.titleText')}</Label>
                <Input id={`${id}-text`} value={titleText} maxLength={AI_LIMITS.coverText}
                  placeholder={t('ai.cover.titleTextPlaceholder')} data-testid="ai-cover-text"
                  onChange={(event) => setTitleText(event.target.value)} />
              </div>
              <div className="flex flex-col gap-2">
                <Label id={`${id}-language`}>{t('ai.cover.language')}</Label>
                <Select value={language} onValueChange={(value) => setLanguage(value as AiLanguage)}>
                  <SelectTrigger aria-labelledby={`${id}-language`} className="w-40" data-testid="ai-cover-language">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {AI_LANGUAGES.map((l) => <SelectItem key={l} value={l}>{t(`ai.dialog.languages.${l}`)}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
            </div>
          )}
          <p className="text-muted-foreground text-sm" data-testid="ai-cover-sent">{t('ai.cover.sent')}</p>
          {replacing && <p className="text-sm" data-testid="ai-cover-replacing">{t('ai.cover.replacing')}</p>}
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
 * The cover of a ready job (Q96: one): the picture the server drew, used as the quiz's cover, asked again with other
 * requirements, or discarded.
 */
function Preview({ job, onApplied, onAgain }: { job: AiJob; onApplied?: () => void; onAgain: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const editing = useEditing()
  const id = useId()
  const cover = job.covers[0]

  const apply = useMutation({
    mutationFn: () => acceptAiCover(job.aiJobId, editing.quizId, cover.no, editing.revision()),
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

  if (!cover) return null
  return (
    <section className="flex flex-col gap-4" aria-labelledby={`${id}-title`} data-testid="ai-cover-choice">
      <div className="flex flex-col gap-1">
        <h3 id={`${id}-title`} className="font-medium">{t('ai.cover.previewTitle')}</h3>
        <p className="text-muted-foreground text-sm">{t('ai.cover.previewIntro')}</p>
        {job.expiresAt && <p className="text-muted-foreground text-xs" data-testid="ai-cover-expires">
          {t('ai.review.expires', { time: formatDateTime(job.expiresAt) })}</p>}
      </div>
      <div data-testid="ai-cover-candidate" data-style={cover.style}>
        <Thumbnail fileId={cover.fileId} width={1200} className="aspect-[40/21] w-full rounded-md border"
          alt={cover.text ? t('ai.cover.picture', { text: cover.text }) : t('ai.cover.pictureUntitled')} />
      </div>
      {cover.text && (
        // The text on the picture, as plain text.
        <p className="text-sm" data-testid="ai-cover-shown-text">{t('ai.cover.shownText', { text: cover.text })}</p>
      )}
      {job.coverParams?.requirements && (
        <p className="text-muted-foreground text-sm" data-testid="ai-cover-asked">
          {t('ai.cover.asked', { requirements: job.coverParams.requirements })}</p>
      )}
      <ProblemList problems={formProblem(apply.error, [])} testId="ai-cover-apply-error" />
      <div className="flex flex-wrap gap-2">
        <Button disabled={apply.isPending || editing.stale} aria-busy={apply.isPending || undefined}
          data-testid="ai-cover-apply" onClick={() => apply.mutate()}>
          {t('ai.cover.apply')}
        </Button>
        <Button variant="outline" disabled={apply.isPending || editing.stale} data-testid="ai-cover-again"
          onClick={onAgain}>
          <RefreshCw aria-hidden />
          {t('ai.cover.again')}
        </Button>
        <ConfirmDialog destructive title={t('ai.cover.discardTitle')} description={t('ai.cover.discardText')}
          confirmLabel={t('ai.cover.discard')} cancelLabel={t('common.cancel')} onConfirm={discard}
          trigger={<Button variant="ghost" disabled={apply.isPending} data-testid="ai-cover-discard">
            {t('ai.cover.discard')}</Button>} />
      </div>
    </section>
  )
}

/** The text of a failed cover job: the questions' texts, except where covers differ. */
function coverFailure(t: (key: string) => string, code: string | null): string {
  return code === 'QB_AI_NO_CANDIDATES' ? t('ai.cover.noCandidates') : failureText(t, code)
}

/**
 * The quiz page's AI covers (Q8b, M-25; Q96): asking for a cover (an illustration or a catalogue design, with the
 * sponsor's requirements), the job while it runs (every five seconds for at most five minutes, not after the page is
 * left), the picture to use or to ask again from, or why it failed. The cover used is written as a quiz edit;
 * `onApplied` lets the heading show it.
 */
export function AiCoverPanel({ quiz, onApplied }: { quiz: Quiz; onApplied?: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const editing = useEditing()
  const [asking, setAsking] = useState(false)
  const job = useQuery({
    queryKey: aiCoverJobQuery(quiz.quizId),
    queryFn: () => readLatestAiJob(quiz.quizId, AI_KINDS.cover),
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
            <span>{!enabled && usage.isSuccess ? t('ai.held')
              : stillPolling(latest) ? t('ai.cover.running') : t('ai.runningLong')}</span>
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
        {latest?.status === 'READY' && (
          <Preview key={latest.aiJobId} job={latest} onApplied={onApplied} onAgain={() => setAsking(true)} />
        )}
      </CardContent>
      {asking && <CoverDialog open onOpenChange={setAsking} initial={latest?.coverParams ?? null}
        replacing={latest?.status === 'READY'} />}
    </Card>
  )
}
