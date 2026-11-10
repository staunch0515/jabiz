import { Alert, AlertDescription, Button, Checkbox, ConfirmDialog, PageState, RadioGroup, RadioGroupItem, cn }
  from '@jabiz/ui'
import { ApiError } from '@jabiz/client'
import { ChevronLeft, ChevronRight, Clock, Send } from 'lucide-react'
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { FileImage } from '../FileImage'
import { CODE } from '../ids'
import { QB } from '../messages'
import { unanswered as unansweredOf, type Given, type SaveInput, type SaveOutput, type StartOutput,
  type SubmitInput, type SubmitOutput } from '../play'
import { navigate } from '../router'
import { formatRemaining, threshold, useCountdown } from './countdown'
import { isBusy, playProblemText, retryWhenBusy } from './busy'
import { AnswerSaver } from './saver'
import './messages'

/** An attempt started (or continued), with the offset of the server's clock from the device's. */
export interface Started {
  output: StartOutput
  offset: number
}

/**
 * Who answers (F-S4c): the Taker app's processes (`QB_ATTEMPT_*`) or the Sponsor app's for its challenges
 * (`QB_SPONSOR_ATTEMPT_*`, the same inputs and outputs), where a finished attempt's result is, and what the app keeps
 * of a submission (the Taker app's tab storage; none for the Sponsor's).
 */
export interface AnswerFlow {
  save: (input: SaveInput) => Promise<SaveOutput>
  submit: (input: SubmitInput, idempotencyKey: string) => Promise<SubmitOutput>
  resultPath: (attemptId: string) => string
  onSubmitted?: (result: SubmitOutput) => void
  onAutoSubmitted?: (attemptId: string) => void
}

/** Whether a call was refused with one of these codes. */
export function refusedWith(error: unknown, ...codes: string[]): boolean {
  return error instanceof ApiError && error.violations.some((v) => codes.includes(v.ruleCode))
}

/** The grace after the deadline in which a submission is still taken (`PlayLimits.SUBMIT_GRACE`). */
export const SUBMIT_GRACE_MS = 5_000

/** An option's letter in the order shown (A, B, C…). */
export const letter = (index: number) => String.fromCharCode(65 + index)

/** The answers as the server gives them, by question number. */
function answerMap(answers: Given[]): Map<number, number[]> {
  return new Map(answers.map((a) => [a.questionNo, [...a.selected]]))
}

type SaveState = 'idle' | 'saving' | 'saved' | 'failed'

/** The countdown, always on screen; screen readers hear it only at its thresholds (a minute, ten seconds). */
function Timer({ left }: { left: number }) {
  const { t } = useTranslation(QB)
  const reached = threshold(left)
  const low = reached !== null
  return (
    <>
      <span role="timer" aria-label={t('answer.timeLeft')} data-testid="play-timer"
        className={cn('inline-flex items-center gap-1 rounded-md px-2 py-1 font-mono text-sm font-semibold tabular-nums',
          low ? 'bg-destructive text-destructive-foreground' : 'bg-muted')}>
        <Clock aria-hidden className="size-4" />
        {formatRemaining(left)}
      </span>
      <span className="sr-only" aria-live="polite" data-testid="play-timer-announcement">
        {reached === 60_000 ? t('answer.oneMinuteLeft') : reached === 10_000 ? t('answer.tenSecondsLeft') : ''}
      </span>
    </>
  )
}

/**
 * One question per page (U-30 – U-32, U-41; docs/quizbuks/plans/QP-pages.md), shared by the Taker app and the Sponsor
 * app's challenges (F-S4c): the progress, the countdown by the server's clock, the options (radio buttons for a single
 * choice, check boxes otherwise), previous and next; answers saved as they change, the current question on every move.
 * Submitting asks first; when the time runs out the answers are submitted without asking.
 */
export function Player({ started, flow }: { started: Started; flow: AnswerFlow }) {
  const { t } = useTranslation(QB)
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
      return flow.save(input)
    },
    onSaved: () => setSaveState('saved'),
    onError: () => setSaveState('failed'),
    // The attempt is over: its time ran out (the countdown submits) or it was submitted elsewhere (its result).
    isFatal: (error) => refusedWith(error, CODE.attemptExpired, CODE.attemptNotInProgress),
    onFatal: (error) => {
      setSaveState('idle')
      if (refusedWith(error, CODE.attemptNotInProgress)) navigate(flow.resultPath(attemptId), { replace: true })
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
    if (how === 'auto') flow.onAutoSubmitted?.(attemptId)
    // Saves what is still pending first (saving is not queued): a submission that queues past the deadline and its
    // grace ends EXPIRED, and the server then finishes the attempt with the saved answers - all of them.
    await saver.settle()
    const signal = page.current?.signal
    // By the server's clock: past the deadline and the grace a submission is refused, retrying is no use.
    const inTime = () => Date.now() + offset < Date.parse(output.deadline) + SUBMIT_GRACE_MS
    try {
      // The publication is the queue's key before its lock; a busy answer is tried again with the same key.
      const input = { attemptId, publicationId: output.publicationId, currentIndex: index, answers: allAnswers() }
      const result = await retryWhenBusy(() => flow.submit(input, submitKey), { onBusy: setBusy, signal,
        shouldRetry: inTime })
      if (signal?.aborted) return
      flow.onSubmitted?.(result)
      navigate(flow.resultPath(attemptId), { replace: true })
    } catch (error) {
      if (signal?.aborted) return
      // Too late, or finished meanwhile, or still busy when its time ran out: the server finishes it with the saved
      // answers; the result page waits for it.
      if (how === 'auto' || refusedWith(error, CODE.attemptExpired, CODE.attemptFinished, CODE.attemptNotInProgress)
        || (isBusy(error) && !inTime())) {
        if (refusedWith(error, CODE.attemptExpired) || isBusy(error)) flow.onAutoSubmitted?.(attemptId)
        if (refusedWith(error, CODE.attemptExpired)) flow.onAutoSubmitted?.(attemptId)
        navigate(flow.resultPath(attemptId), { replace: true })
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

  if (total === 0) return <PageState kind="empty" description={t('answer.noQuestions')} />
  const question = questions[index]
  const chosen = answers.get(question.no) ?? []
  // A survey (Q12) is submitted only with every question answered (a single choice by one option). A scored
  // question with one correct option is a single choice too (the server says which, D-Q5-2): radio buttons.
  const survey = output.kind === 'SURVEY'
  const single = question.selection === 'SINGLE'
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
      <PageState kind="loading" title={busy ? t('answer.busy') : submitting === 'auto' ? t('answer.timeUp')
        : t('answer.submitting')} data-testid="play-submitting" data-busy={busy || undefined} />
    )
  }

  return (
    <section className="flex flex-col gap-4" data-testid="play" aria-labelledby="play-question">
      <div className="bg-background sticky top-12 z-[5] -mx-4 flex flex-col gap-2 border-b px-4 py-2">
        <div className="flex items-center justify-between gap-2">
          <span className="text-sm font-medium" data-testid="play-progress">
            {t('answer.progress', { n: index + 1, total })}
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
            <span className="sr-only">{t('answer.progress', { n: index + 1, total })}: </span>
            {question.stem}
          </h1>
        </legend>
        <p id="play-hint" className="text-muted-foreground text-sm">
          <span className="text-foreground font-medium" data-testid="play-choice"
            data-choice={single ? 'single' : 'multiple'}>{t(single ? 'answer.single' : 'answer.multiple')}</span>
          {' · '}
          <span>{survey ? t(single ? 'answer.chooseOne' : 'answer.chooseAny')
            : <>{t('answer.points', { count: question.points })} · {t(single ? 'answer.chooseCorrect' : 'answer.chooseAll')}</>}</span>
        </p>
        {question.image && (
          <FileImage fileId={question.image} variant="w640" alt={t('answer.questionImage')}
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
                      <FileImage fileId={option.image} variant="w320" alt={option.text ? '' : t('answer.optionImage',
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
        {saveState === 'saving' ? t('answer.saving') : saveState === 'saved' ? t('answer.saved')
          : saveState === 'failed' ? t('answer.saveFailed') : ''}
      </p>
      {submitError !== null && (
        <Alert variant="destructive" data-testid="play-submit-error">
          <AlertDescription>{playProblemText(submitError)}</AlertDescription>
        </Alert>
      )}
      <nav aria-label={t('answer.navigation')} className="flex items-center gap-2">
        <Button variant="outline" disabled={index === 0} onClick={() => go(index - 1)} data-testid="play-previous">
          <ChevronLeft aria-hidden />
          {t('answer.previous')}
        </Button>
        <span className="flex-1" />
        {last ? (
          // aria-disabled rather than disabled: the button keeps its focus and says why it waits.
          <Button onClick={() => !blocked && setConfirmOpen(true)} aria-disabled={blocked || undefined}
            aria-describedby={blocked ? 'play-incomplete' : undefined} data-testid="play-submit"
            className={blocked ? 'cursor-not-allowed opacity-50' : undefined}>
            <Send aria-hidden />
            {t('answer.submit')}
          </Button>
        ) : (
          <Button onClick={() => go(index + 1)} data-testid="play-next">
            {t('answer.next')}
            <ChevronRight aria-hidden />
          </Button>
        )}
      </nav>
      {blocked && last && (
        <p id="play-incomplete" className="text-muted-foreground text-sm" data-testid="play-incomplete">
          {t('answer.surveyIncomplete', { count: unanswered })}{' '}
          <Button variant="link" className="h-auto p-0" onClick={() => go(questions.findIndex((q) => q.no === open[0]))}
            data-testid="play-first-unanswered">
            {t('answer.toUnanswered')}
          </Button>
        </p>
      )}
      <ConfirmDialog open={confirmOpen} onOpenChange={setConfirmOpen} title={t('answer.confirmTitle')}
        description={unanswered > 0 ? t('answer.confirmUnanswered', { count: unanswered }) : t('answer.confirmAll')}
        confirmLabel={t('answer.submit')} cancelLabel={t('answer.keepAnswering')}
        onConfirm={() => void submit('manual')} />
    </section>
  )
}

/**
 * The options of a question: check boxes, or radio buttons for a single choice (a survey's, Q12, or a scored question
 * with one correct option), which hold one option.
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

