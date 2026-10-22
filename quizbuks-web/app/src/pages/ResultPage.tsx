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
import { isSurveyResult, readAttempt, readPublication, type QuestionResult, type QuestionView, type RewardStatus }
  from '../qb/play'
import { problemText } from '../qb/problems'
import { Link, Redirect } from '../qb/router'
import { letter } from './PlayPage'

/** How often the page asks again while the server finishes an attempt whose time ran out. */
export const FINISHING_POLL_MS = 5_000

/** A finished attempt as the page shows it, from the submission's answer or from the caller's attempt record. */
interface Outcome {
  status: string
  score: number
  fullScore: number
  passScore: number | null
  passed: boolean
  reward: unknown
  rewardStatus: RewardStatus
  /** The server's notice of a pending reward, in the caller's language. */
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
 * The result (U-33, U-34): score, pass mark, passed or not, each question right or wrong, and the reward paid,
 * pending (the sponsor's budget ran out: paid once they top up) or none. Right after finishing, this tab still has
 * the questions' text (the start's output, docs/quizbuks/plans/QP-pages.md A-3) and shows the answers chosen and the
 * correct ones; otherwise only right or wrong per question, from the attempt's record. An attempt whose time ran out
 * and is not finished yet is waited for: the server finishes it with its saved answers. A survey's result (Q12) says
 * whether it was complete, the reward, and the answers chosen, never right or wrong.
 */
export function ResultPage({ attemptId, country }: { attemptId: string; country: string | null }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const [submitted] = useState(() => storedResult(attemptId))
  const [start] = useState(() => storedStart(attemptId))
  const record = useQuery({
    queryKey: ['qb', 'attempt', attemptId],
    // Whether it can still be answered, by the device's clock when read (a hint: the play page asks the server).
    queryFn: async () => {
      const found = await readAttempt(attemptId)
      return { ...found, inTime: !!found.deadline && Date.parse(found.deadline) > Date.now() }
    },
    enabled: !submitted,
    refetchInterval: (query) => (query.state.data?.status === 'IN_PROGRESS' ? FINISHING_POLL_MS : false),
  })
  const publicationId = start?.publicationId ?? record.data?.publicationId
  const [at] = useState(() => new Date().toISOString())
  const publication = useQuery({
    queryKey: ['qb', 'publication', { publicationId, at, country }],
    queryFn: () => readPublication({ publicationId: publicationId!, at, country }),
    enabled: !!publicationId,
  })

  if (!submitted) {
    if (record.isPending) return <PageState kind="loading" />
    if (record.isError) {
      if (record.error instanceof ApiError && record.error.status === 404) {
        return <PageState kind="notFound" titleAs="h1" description={tQb('notFound')} />
      }
      return <PageState kind="error" description={problemText(record.error)}
        action={<Button variant="outline" onClick={() => void record.refetch()}>{tQb('retry')}</Button>} />
    }
  }
  const data = record.data
  if (!submitted && data && data.status === 'IN_PROGRESS') {
    // Still in time: back to answering; else the server is finishing it with the saved answers.
    if (data.inTime) return <Redirect to={`/play/${attemptId}`} />
    return (
      <PageState kind="loading" title={t('result.finishing')} data-testid="result-finishing" />
    )
  }
  const outcome: Outcome | null = submitted ? submitted : data ? {
    status: data.status,
    score: data.score ?? 0,
    fullScore: data.fullScore ?? 0,
    passScore: publication.data?.passScore ?? null,
    passed: data.passed === true,
    reward: data.reward,
    rewardStatus: data.rewardStatus ?? 'NONE',
    message: null,
    questions: data.result ?? [],
  } : null
  if (!outcome) return <PageState kind="loading" />
  const automatic = outcome.status === 'EXPIRED' || autoSubmitted(attemptId)
  const byNo = new Map((start?.questions ?? []).map((q) => [q.no, q]))
  const name = publication.data?.name
  // A survey (Q12) has no score to show: complete (every question answered) or not, and what was chosen.
  const survey = submitted?.kind === 'SURVEY' || start?.kind === 'SURVEY' || publication.data?.kind === 'SURVEY'
    || isSurveyResult(outcome.questions)

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
          {!start && <p className="text-muted-foreground text-sm">{t('result.textsGone')}</p>}
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
