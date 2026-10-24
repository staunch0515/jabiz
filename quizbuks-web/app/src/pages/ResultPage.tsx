import { ApiError } from '@jabiz/client'
import { Alert, AlertDescription, AlertTitle, Badge, Button, PageState, cn } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { CircleCheck, CircleX, Clock, Hourglass, ListChecks, Trophy } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { autoSubmitted, storedResult, storedStart } from '../play/storage'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { readAttemptResult, type AttemptResult, type QuestionResult, type QuestionView, type RewardStatus,
  type StartOutput, type SubmitOutput } from '../qb/play'
import { problemText } from '../qb/problems'
import { Link, Redirect } from '../qb/router'
import { letter } from './PlayPage'

/** How often the page asks again while the server finishes an attempt whose time ran out. */
export const FINISHING_POLL_MS = 5_000

/**
 * The submission's own answer, with this tab's copy of the start, as a result: what the page shows when the server's
 * result cannot be read right after finishing (a phone's connection dropped, a busy quiz).
 */
function fromSubmission(submitted: SubmitOutput, start: StartOutput | null): AttemptResult {
  return { attemptId: submitted.attemptId, publicationId: start?.publicationId ?? '', name: null,
    status: submitted.status, kind: submitted.kind, deadline: null, serverTime: '', score: submitted.score,
    fullScore: submitted.fullScore, passScore: submitted.kind === 'SURVEY' ? null : submitted.passScore,
    passed: submitted.passed, reward: submitted.reward, rewardStatus: submitted.rewardStatus,
    questions: start?.questions ?? [], result: submitted.questions }
}

/** A finished attempt as the page shows it, from the server's result. */
interface Outcome {
  status: string
  score: number
  fullScore: number
  passScore: number | null
  passed: boolean
  reward: unknown
  rewardStatus: RewardStatus
  /** The server's notice of a pending reward, in the caller's language (the submission's answer, this tab only). */
  message: string | null
  questions: QuestionResult[]
}

/** The texts of a question's options, in the order they were shown (letters), for the chosen numbers. */
function optionTexts(question: QuestionView | undefined, numbers: number[]): string[] {
  if (!question) return []
  return question.options
    .map((option, i) => ({ option, label: letter(i) }))
    .filter(({ option }) => numbers.includes(option.no))
    .map(({ option, label }) => (option.text ? `${label}. ${option.text}` : label))
}

/** A survey's question (Q12): what was chosen, never right or wrong. */
function SurveyRow({ result, question, index }: { result: QuestionResult; question?: QuestionView; index: number }) {
  const { t } = useTranslation(APP)
  const mine = optionTexts(question, result.selected)
  return (
    <li className="flex flex-col gap-1 rounded-lg border p-3" data-testid="result-question">
      <p className="font-medium">{t('result.question', { n: index + 1 })}</p>
      {question?.stem && <p className="text-sm">{question.stem}</p>}
      {question && (
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
          <dt className="text-muted-foreground">{t('result.yourAnswer')}</dt>
          <dd data-testid="result-mine">{mine.length > 0 ? mine.join(', ') : t('result.noAnswer')}</dd>
        </dl>
      )}
    </li>
  )
}

function QuestionRow({ result, question, index }: { result: QuestionResult; question?: QuestionView; index: number }) {
  const { t } = useTranslation(APP)
  // Scoring's rule: right when the options chosen are exactly the correct ones.
  const right = result.correct.length > 0 && [...result.selected].sort().join() === [...result.correct].sort().join()
  const Icon = right ? CircleCheck : CircleX
  const mine = optionTexts(question, result.selected)
  const correct = optionTexts(question, result.correct)
  return (
    <li className="flex flex-col gap-1 rounded-lg border p-3" data-testid="result-question"
      data-correct={right ? 'true' : 'false'}>
      <p className="flex items-center gap-2 font-medium">
        <Icon aria-hidden className={cn('size-5 shrink-0', right ? 'text-success' : 'text-destructive')} />
        <span className="flex-1">{t('result.question', { n: index + 1 })}</span>
        <span className="text-sm">{right ? t('result.right') : t('result.wrong')}</span>
      </p>
      {question?.stem && <p className="text-sm">{question.stem}</p>}
      {question && (
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
          <dt className="text-muted-foreground">{t('result.yourAnswer')}</dt>
          <dd data-testid="result-mine">{mine.length > 0 ? mine.join(', ') : t('result.noAnswer')}</dd>
          <dt className="text-muted-foreground">{t('result.correctAnswer')}</dt>
          <dd data-testid="result-correct">{correct.join(', ')}</dd>
        </dl>
      )}
      <p className="text-muted-foreground text-xs">{t('result.earned', { earned: result.earned, points: result.points })}</p>
    </li>
  )
}

function Reward({ outcome, survey }: { outcome: Outcome; survey: boolean }) {
  const { t } = useTranslation(APP)
  if (outcome.rewardStatus === 'PENDING') {
    return (
      <Alert variant="warning" data-testid="result-reward" data-status="PENDING">
        <Hourglass aria-hidden />
        <AlertTitle>{t('result.pendingTitle', { amount: formatKudos(outcome.reward) })}</AlertTitle>
        <AlertDescription className="text-current">{outcome.message ?? t('result.pending')}</AlertDescription>
      </Alert>
    )
  }
  if (outcome.rewardStatus === 'PAID') {
    return (
      <Alert variant="success" data-testid="result-reward" data-status="PAID">
        <Trophy aria-hidden />
        <AlertTitle>{t('result.paid', { amount: formatKudos(outcome.reward) })}</AlertTitle>
        <AlertDescription className="text-current">{t('result.paidHint')}</AlertDescription>
      </Alert>
    )
  }
  return (
    <Alert data-testid="result-reward" data-status="NONE">
      <AlertDescription>{t(survey ? 'result.surveyIncomplete' : 'result.none')}</AlertDescription>
    </Alert>
  )
}

/**
 * The result (U-33, U-34): score, pass mark, passed or not, each question with the answer chosen and the correct
 * one, and the reward paid, pending (the sponsor's budget ran out: paid once they top up) or none. Everything comes
 * from the server (`QB_ATTEMPT_RESULT`, docs/quizbuks/plans/Q20-result-review.md): the questions as this attempt
 * showed them and its stored result, so the page reads the same from the history, another device or after the tab
 * closed. Only the pending reward's notice is the submission's own (this tab), and the submission's answer stands in
 * when the server cannot be read right after finishing. An attempt whose time ran out and is not finished yet is
 * waited for: the server finishes it with its saved answers. A survey's result (Q12) says whether it was complete, the
 * reward, and the answers chosen, never right or wrong.
 */
export function ResultPage({ attemptId }: { attemptId: string }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const [submitted] = useState(() => storedResult(attemptId))
  const result = useQuery({
    queryKey: ['qb', 'attempt-result', attemptId],
    queryFn: () => readAttemptResult(attemptId),
    // A copy of this attempt in progress may be cached from before it was finished: always read again on arrival.
    refetchOnMount: 'always',
    refetchInterval: (query) => (query.state.data?.status === 'IN_PROGRESS' ? FINISHING_POLL_MS : false),
  })

  if (result.isPending) return <PageState kind="loading" />
  // The submission's own answer stands in only when the server could not answer (not when it refused: a 404 or 403 is
  // another user's or no attempt, and must not show what this tab kept).
  const refused = result.error instanceof ApiError && result.error.status < 500
  const data = result.data ?? (submitted && !refused ? fromSubmission(submitted, storedStart(attemptId)) : null)
  if (!data) {
    if (result.error instanceof ApiError && result.error.status === 404) {
      return <PageState kind="notFound" titleAs="h1" description={tQb('notFound')} />
    }
    return <PageState kind="error" description={problemText(result.error)}
      action={<Button variant="outline" onClick={() => void result.refetch()}>{tQb('retry')}</Button>} />
  }
  if (data.status === 'IN_PROGRESS') {
    // What is cached may be stale (the attempt was finished since): wait for the read under way before deciding.
    if (result.isFetching) return <PageState kind="loading" />
    // Still in time by the server's clock: back to answering; else the server is finishing it with the saved answers.
    const inTime = !!data.deadline && Date.parse(data.deadline) > Date.parse(data.serverTime)
    if (inTime) return <Redirect to={`/play/${attemptId}`} />
    return (
      <PageState kind="loading" title={t('result.finishing')} data-testid="result-finishing" />
    )
  }
  const outcome: Outcome = {
    status: data.status,
    score: data.score ?? 0,
    fullScore: data.fullScore ?? 0,
    passScore: data.passScore,
    passed: data.passed === true,
    reward: data.reward,
    rewardStatus: data.rewardStatus ?? 'NONE',
    message: submitted?.message ?? null,
    questions: data.result,
  }
  const automatic = outcome.status === 'EXPIRED' || autoSubmitted(attemptId)
  const byNo = new Map(data.questions.map((q) => [q.no, q]))
  const name = data.name
  // A survey (Q12) has no score to show: complete (every question answered) or not, and what was chosen.
  const survey = data.kind === 'SURVEY'

  return (
    <section className="flex flex-col gap-4" aria-labelledby="result-title" data-testid="result">
      <header className="flex flex-col gap-1">
        <h1 id="result-title" className="text-xl font-semibold">{t('result.title')}</h1>
        {name && <p className="text-muted-foreground" data-testid="result-name">{name}</p>}
      </header>
      {automatic && (
        <Alert data-testid="result-automatic">
          <Clock aria-hidden />
          <AlertDescription>{t('result.automatic')}</AlertDescription>
        </Alert>
      )}
      {survey ? (
        <div className="flex items-center gap-3 rounded-lg border p-4" data-testid="result-survey"
          data-complete={outcome.passed ? 'true' : 'false'}>
          <ListChecks aria-hidden className="size-6 shrink-0" />
          <p className="font-medium">{outcome.passed ? t('result.surveyDone') : t('result.surveyIncomplete')}</p>
        </div>
      ) : (
      <div className="flex items-center gap-4 rounded-lg border p-4">
        <p className="flex flex-col">
          <span className="text-muted-foreground text-sm">{t('result.score')}</span>
          <span className="text-3xl font-bold tabular-nums" data-testid="result-score">
            {t('result.scoreOf', { score: outcome.score, full: outcome.fullScore })}
          </span>
          {outcome.passScore !== null && (
            <span className="text-muted-foreground text-sm" data-testid="result-pass-score">
              {t('result.passScore', { score: outcome.passScore })}
            </span>
          )}
        </p>
        <span className="flex-1" />
        <Badge variant={outcome.passed ? 'success' : 'secondary'} data-testid="result-passed"
          data-passed={outcome.passed ? 'true' : 'false'}>
          {outcome.passed ? t('result.passed') : t('result.failed')}
        </Badge>
      </div>
      )}
      <Reward outcome={outcome} survey={survey} />
      {outcome.questions.length > 0 && (
        <section aria-labelledby="result-questions-title" className="flex flex-col gap-2">
          <h2 id="result-questions-title" className="text-lg font-semibold">
            {survey ? t('result.surveyAnswers') : t('result.questions')}
          </h2>
          <ol className="flex flex-col gap-2">
            {outcome.questions.map((result, i) => survey
              ? <SurveyRow key={result.no} result={result} question={byNo.get(result.no)} index={i} />
              : <QuestionRow key={result.no} result={result} question={byNo.get(result.no)} index={i} />)}
          </ol>
        </section>
      )}
      <div className="flex gap-2">
        <Button asChild variant="outline" className="flex-1"><Link to="/history">{t('nav.history')}</Link></Button>
        <Button asChild className="flex-1"><Link to="/">{t('result.more')}</Link></Button>
      </div>
    </section>
  )
}
