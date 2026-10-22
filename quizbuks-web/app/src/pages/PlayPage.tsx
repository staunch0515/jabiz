import { ApiError } from '@jabiz/client'
import { Alert, AlertDescription, Button, Checkbox, ConfirmDialog, PageState, RadioGroup, RadioGroupItem, cn }
  from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, Clock, Send } from 'lucide-react'
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { clockOffset, formatRemaining, threshold, useCountdown } from '../play/countdown'
import { AnswerSaver } from '../play/saver'
import { markAutoSubmitted, storedStart, storeResult, storeStart, takeHandedOver, type Started } from '../play/storage'
import { FileImage } from '../qb/FileImage'
import { CODE } from '../qb/ids'
import { QB } from '../qb/messages'
import { readAttempt, saveAttempt, startAttempt, submitAttempt, unanswered as unansweredOf, type Given }
  from '../qb/play'
import { Link, navigate, Redirect } from '../qb/router'
import { isBusy, playProblemText, retryWhenBusy } from '../play/busy'
import { refusedWith } from '../play/useStartAttempt'

/** The grace after the deadline in which a submission is still taken (`PlayLimits.SUBMIT_GRACE`). */
export const SUBMIT_GRACE_MS = 5_000

/** An option's letter in the order shown (A, B, C…). */
export const letter = (index: number) => String.fromCharCode(65 + index)

/** Where the play page goes instead of playing: the attempt's result, or nothing (it plays). */
type Resolved = { started: Started } | { result: string } | { expired: string }

/**
 * The attempt to play: handed over by the page that started it, else continued from the server (`QB_ATTEMPT_START`
 * returns the attempt in progress with its answers and current question: a reload resumes it). The publication comes
 * from this tab's copy of the start, else from the caller's attempt record. A finished attempt goes to its result.
 */
async function resolve(attemptId: string, onBusy: (busy: boolean) => void, signal?: AbortSignal):
  Promise<Resolved> {
  const handed = takeHandedOver(attemptId)
  if (handed) return { started: handed }
  let publicationId = storedStart(attemptId)?.publicationId
  if (!publicationId) {
    const record = await readAttempt(attemptId)
    if (record.status !== 'IN_PROGRESS') return { result: attemptId }
    publicationId = record.publicationId
  }
  try {
    // A hot quiz may answer busy (its queue is full): try again with the same key (play/busy.ts).
    const key = crypto.randomUUID()
    const from = publicationId
    const output = await retryWhenBusy(() => startAttempt(from, key), { onBusy, signal })
    storeStart(output)
    return { started: { output, offset: clockOffset(output.serverTime) } }
  } catch (error) {
    if (refusedWith(error, CODE.attemptAlreadyTaken)) return { result: attemptId }
    if (refusedWith(error, CODE.attemptExpired)) return { expired: attemptId }
    throw error
  }
}

/** The answers as the server gives them, by question number. */
function answerMap(answers: Given[]): Map<number, number[]> {
  return new Map(answers.map((a) => [a.questionNo, [...a.selected]]))
}

type SaveState = 'idle' | 'saving' | 'saved' | 'failed'

/** The countdown, always on screen; screen readers hear it only at its thresholds (a minute, ten seconds). */
function Timer({ left }: { left: number }) {
  const { t } = useTranslation(APP)
  const reached = threshold(left)
  const low = reached !== null
  return (
    <>
      <span role="timer" aria-label={t('play.timeLeft')} data-testid="play-timer"
        className={cn('inline-flex items-center gap-1 rounded-md px-2 py-1 font-mono text-sm font-semibold tabular-nums',
          low ? 'bg-destructive text-destructive-foreground' : 'bg-muted')}>
        <Clock aria-hidden className="size-4" />
        {formatRemaining(left)}
      </span>
      <span className="sr-only" aria-live="polite" data-testid="play-timer-announcement">
        {reached === 60_000 ? t('play.oneMinuteLeft') : reached === 10_000 ? t('play.tenSecondsLeft') : ''}
      </span>
    </>
  )
}

function Player({ started }: { started: Started }) {
  const { t } = useTranslation(APP)
  const { output, offset } = started
  const questions = output.questions
  const total = questions.length
  const [index, setIndex] = useState(() => Math.min(Math.max(output.currentIndex, 0), Math.max(total - 1, 0)))
  const [answers, setAnswers] = useState(() => answerMap(output.answers))
  const [saveState, setSaveState] = useState<SaveState>('idle')
  const [submitting, setSubmitting] = useState<'manual' | 'auto' | null>(null)
  const [busy, setBusy] = useState(false)
  const [submitError, setSubmitError] = useState<unknown>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const heading = useRef<HTMLHeadingElement>(null)
  const firstRender = useRef(true)
  // One key for this attempt's submission, whoever retries it: a lost answer's retry returns the first one.
  const [submitKey] = useState(() => crypto.randomUUID())
  // Aborted when the player goes away: a busy submission stops trying (the server finishes it when its time is up).
  const page = useRef<AbortController | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    page.current = controller
    return () => controller.abort()
  }, [])
  const attemptId = output.attemptId
  // The player is keyed by the attempt: one saver for its life.
  const [saver] = useState(() => new AnswerSaver({
    attemptId,
    save: (input) => {
      setSaveState('saving')
      return saveAttempt(input)
    },
    onSaved: () => setSaveState('saved'),
    onError: () => setSaveState('failed'),
    // The attempt is over: its time ran out (the countdown submits) or it was submitted elsewhere (its result).
    isFatal: (error) => refusedWith(error, CODE.attemptExpired, CODE.attemptNotInProgress),
    onFatal: (error) => {
      setSaveState('idle')
      if (refusedWith(error, CODE.attemptNotInProgress)) navigate(`/result/${attemptId}`, { replace: true })
    },
  }))
  // Leaving the page saves what is pending (the server keeps it for continuing).
  useEffect(() => () => void saver.flush(), [saver])

  // Each move to another question puts focus on its heading, so screen readers read where they are.
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false
      return
    }
    heading.current?.focus()
  }, [index])

  const allAnswers = (): Given[] =>
    [...answers].filter(([, selected]) => selected.length > 0).map(([questionNo, selected]) => ({ questionNo, selected }))

  const submit = useCallback(async (how: 'manual' | 'auto') => {
    if (submitting) return
    setSubmitting(how)
    setSubmitError(null)
    if (how === 'auto') markAutoSubmitted(attemptId)
    // Saves what is still pending first (saving is not queued): a submission that queues past the deadline and its
    // grace ends EXPIRED, and the server then finishes the attempt with the saved answers - all of them.
    await saver.settle()
    const signal = page.current?.signal
    // By the server's clock: past the deadline and the grace a submission is refused, retrying is no use.
    const inTime = () => Date.now() + offset < Date.parse(output.deadline) + SUBMIT_GRACE_MS
    try {
      // The publication is the queue's key before its lock; a busy answer is tried again with the same key.
      const input = { attemptId, publicationId: output.publicationId, currentIndex: index, answers: allAnswers() }
      const result = await retryWhenBusy(() => submitAttempt(input, submitKey), { onBusy: setBusy, signal,
        shouldRetry: inTime })
      if (signal?.aborted) return
      storeResult(result)
      navigate(`/result/${attemptId}`, { replace: true })
    } catch (error) {
      if (signal?.aborted) return
      // Too late, or finished meanwhile, or still busy when its time ran out: the server finishes it with the saved
      // answers; the result page waits for it.
      if (how === 'auto' || refusedWith(error, CODE.attemptExpired, CODE.attemptFinished, CODE.attemptNotInProgress)
        || (isBusy(error) && !inTime())) {
        if (refusedWith(error, CODE.attemptExpired) || isBusy(error)) markAutoSubmitted(attemptId)
        if (refusedWith(error, CODE.attemptExpired)) markAutoSubmitted(attemptId)
        navigate(`/result/${attemptId}`, { replace: true })
        return
      }
      // Still in progress (offline, a refusal to read): answering, and saving, go on; submitting can be tried again.
      // What stopping dropped is saved again: every answer as the page has it.
      saver.resume()
      answers.forEach((selected, questionNo) => saver.answer(questionNo, selected))
      setSubmitError(error)
      setSubmitting(null)
    }
    // allAnswers reads the current answers; the callback is rebuilt when they change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [submitting, attemptId, output.publicationId, output.deadline, offset, index, answers, saver, submitKey])

  const left = useCountdown(output.deadline, offset, () => void submit('auto'))
  // Shortly before the deadline what is pending is saved (saving is refused after it): should the automatic
  // submission then queue past its grace, the server finishes the attempt with these answers.
  const closing = left > 0 && left <= 2_000
  useEffect(() => {
    if (closing) void saver.flush()
  }, [closing, saver])

  if (total === 0) return <PageState kind="empty" description={t('play.noQuestions')} />
  const question = questions[index]
  const chosen = answers.get(question.no) ?? []
  // A survey (Q12) is submitted only with every question answered (a single choice by one option).
  const survey = output.kind === 'SURVEY'
  const single = survey && question.selection === 'SINGLE'
  const open = unansweredOf(questions, answers)
  const unanswered = open.length
  const blocked = survey && unanswered > 0
  const last = index === total - 1

  const choose = (next: number[]) => {
    setAnswers((current) => new Map(current).set(question.no, next))
    setSaveState('idle')
    saver.answer(question.no, next)
  }
  const toggle = (option: number, on: boolean) =>
    choose(on ? [...new Set([...chosen, option])] : chosen.filter((o) => o !== option))
  const go = (to: number) => {
    setIndex(to)
    void saver.move(to)
  }

  if (submitting) {
    return (
      <PageState kind="loading" title={busy ? t('play.busy') : submitting === 'auto' ? t('play.timeUp')
        : t('play.submitting')} data-testid="play-submitting" data-busy={busy || undefined} />
    )
  }

  return (
    <section className="flex flex-col gap-4" data-testid="play" aria-labelledby="play-question">
      <div className="bg-background sticky top-12 z-[5] -mx-4 flex flex-col gap-2 border-b px-4 py-2">
        <div className="flex items-center justify-between gap-2">
          <span className="text-sm font-medium" data-testid="play-progress">
            {t('play.progress', { n: index + 1, total })}
          </span>
          <Timer left={left} />
        </div>
        <div aria-hidden className="bg-muted h-1.5 overflow-hidden rounded-full">
          <div className="bg-primary h-full transition-[width]" style={{ width: `${((index + 1) / total) * 100}%` }} />
        </div>
      </div>
      <fieldset className="flex flex-col gap-3" aria-describedby="play-hint">
        <legend className="contents">
          <h1 id="play-question" ref={heading} tabIndex={-1} className="text-lg font-semibold outline-none"
            data-testid="play-question">
            <span className="sr-only">{t('play.progress', { n: index + 1, total })}: </span>
            {question.stem}
          </h1>
        </legend>
        <p id="play-hint" className="text-muted-foreground text-sm">
          {survey ? t(single ? 'play.chooseOne' : 'play.chooseAny')
            : <>{t('play.points', { count: question.points })} · {t('play.chooseAll')}</>}
        </p>
        {question.image && (
          <FileImage fileId={question.image} variant="w640" alt={t('play.questionImage')}
            className="max-h-64 w-full rounded-md object-contain" />
        )}
        <OptionList single={single} value={chosen} onChoose={(option) => choose([option])}>
          {question.options.map((option, i) => {
            const id = `option-${question.no}-${option.no}`
            const checked = chosen.includes(option.no)
            return (
              <li key={option.no}>
                <label htmlFor={id} data-testid="play-option" data-checked={checked || undefined}
                  className={cn('flex cursor-pointer items-center gap-3 rounded-lg border p-3',
                    checked ? 'border-primary bg-accent' : 'hover:bg-accent')}>
                  {single ? <RadioGroupItem id={id} value={String(option.no)} />
                    : <Checkbox id={id} checked={checked} onCheckedChange={(value) => toggle(option.no, value === true)} />}
                  <span aria-hidden className="text-muted-foreground w-4 text-sm font-semibold">{letter(i)}</span>
                  <span className="flex flex-1 flex-col gap-2">
                    {option.text && <span>{option.text}</span>}
                    {option.image && (
                      <FileImage fileId={option.image} variant="w320" alt={option.text ? '' : t('play.optionImage',
                        { letter: letter(i) })} className="max-h-40 w-full rounded-md object-contain" />
                    )}
                  </span>
                </label>
              </li>
            )
          })}
        </OptionList>
      </fieldset>
      <p className="text-muted-foreground min-h-5 text-xs" role="status" data-testid="play-save-state">
        {saveState === 'saving' ? t('play.saving') : saveState === 'saved' ? t('play.saved')
          : saveState === 'failed' ? t('play.saveFailed') : ''}
      </p>
      {submitError !== null && (
        <Alert variant="destructive" data-testid="play-submit-error">
          <AlertDescription>{playProblemText(submitError)}</AlertDescription>
        </Alert>
      )}
      <nav aria-label={t('play.navigation')} className="flex items-center gap-2">
        <Button variant="outline" disabled={index === 0} onClick={() => go(index - 1)} data-testid="play-previous">
          <ChevronLeft aria-hidden />
          {t('play.previous')}
        </Button>
        <span className="flex-1" />
        {last ? (
          // aria-disabled rather than disabled: the button keeps its focus and says why it waits.
          <Button onClick={() => !blocked && setConfirmOpen(true)} aria-disabled={blocked || undefined}
            aria-describedby={blocked ? 'play-incomplete' : undefined} data-testid="play-submit"
            className={blocked ? 'cursor-not-allowed opacity-50' : undefined}>
            <Send aria-hidden />
            {t('play.submit')}
          </Button>
        ) : (
          <Button onClick={() => go(index + 1)} data-testid="play-next">
            {t('play.next')}
            <ChevronRight aria-hidden />
          </Button>
        )}
      </nav>
      {blocked && last && (
        <p id="play-incomplete" className="text-muted-foreground text-sm" data-testid="play-incomplete">
          {t('play.surveyIncomplete', { count: unanswered })}{' '}
          <Button variant="link" className="h-auto p-0" onClick={() => go(questions.findIndex((q) => q.no === open[0]))}
            data-testid="play-first-unanswered">
            {t('play.toUnanswered')}
          </Button>
        </p>
      )}
      <ConfirmDialog open={confirmOpen} onOpenChange={setConfirmOpen} title={t('play.confirmTitle')}
        description={unanswered > 0 ? t('play.confirmUnanswered', { count: unanswered }) : t('play.confirmAll')}
        confirmLabel={t('play.submit')} cancelLabel={t('play.keepAnswering')}
        onConfirm={() => void submit('manual')} />
    </section>
  )
}

/**
 * The options of a question: check boxes, or radio buttons for a survey's single choice (Q12), which hold one option.
 */
function OptionList({ single, value, onChoose, children }: { single: boolean; value: number[];
  onChoose: (option: number) => void; children: ReactNode }) {
  if (!single) return <ul className="flex flex-col gap-2" data-testid="play-options">{children}</ul>
  return (
    <RadioGroup value={value.length === 1 ? String(value[0]) : ''} aria-labelledby="play-question"
      onValueChange={(next) => onChoose(Number(next))}>
      <ul className="flex flex-col gap-2" data-testid="play-options">{children}</ul>
    </RadioGroup>
  )
}

/**
 * One question per page (U-30 – U-32, U-41; docs/quizbuks/plans/QP-pages.md): the progress, the countdown by the
 * server's clock, the options as check boxes (the start never says which questions have several correct options, so
 * any number may be chosen; a survey's single choice as radio buttons), previous and next; answers saved as they change, the current question on every move.
 * Submitting asks first; when the time runs out the answers are submitted without asking.
 */
export function PlayPage({ attemptId }: { attemptId: string }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const [busy, setBusy] = useState(false)
  const resolved = useQuery({ queryKey: ['qb', 'play', attemptId], queryFn: ({ signal }) => resolve(attemptId, setBusy, signal),
    staleTime: Infinity, gcTime: 0 })
  if (resolved.isPending) {
    return <PageState kind="loading" title={busy ? t('play.busy') : undefined} data-testid="play-loading" />
  }
  if (resolved.isError) {
    if (resolved.error instanceof ApiError && resolved.error.status === 404) {
      return <PageState kind="notFound" titleAs="h1" description={tQb('notFound')}
        action={<Button asChild variant="outline"><Link to="/history">{t('nav.history')}</Link></Button>} />
    }
    return <PageState kind="error" description={playProblemText(resolved.error)}
      action={<Button variant="outline" onClick={() => void resolved.refetch()}>{tQb('retry')}</Button>} />
  }
  const data = resolved.data
  if ('result' in data) return <Redirect to={`/result/${data.result}`} />
  if ('expired' in data) {
    return (
      <PageState kind="warning" titleAs="h1" title={t('play.expiredTitle')} description={t('play.expired')}
        data-testid="play-expired"
        action={<Button asChild><Link to={`/result/${data.expired}`}>{t('play.toResult')}</Link></Button>} />
    )
  }
  return <Player key={attemptId} started={data.started} />
}

