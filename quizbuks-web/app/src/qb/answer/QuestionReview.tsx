import { cn } from '@jabiz/ui'
import { CircleCheck, CircleX } from 'lucide-react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { QB } from '../messages'
import type { QuestionResult, QuestionView } from '../play'
import { letter } from './Player'
import './messages'

/*
 * A finished attempt's questions (Q20), shared by the Taker app's result page and the Sponsor app's challenge result
 * (F-S4c): what was chosen, and for a scored question whether it was right, the right answer where it differs and the
 * points.
 */

/** The texts of a question's options, in the order they were shown (letters), for the chosen numbers. */
export function optionTexts(question: QuestionView | undefined, numbers: number[]): string[] {
  if (!question) return []
  return question.options
    .map((option, i) => ({ option, label: letter(i) }))
    .filter(({ option }) => numbers.includes(option.no))
    .map(({ option, label }) => (option.text ? `${label}. ${option.text}` : label))
}

/** A survey's question (Q12): what was chosen, never right or wrong. */
export function SurveyRow({ result, question, index, extra }: { result: QuestionResult; question?: QuestionView;
  index: number; extra?: ReactNode }) {
  const { t } = useTranslation(QB)
  const mine = optionTexts(question, result.selected)
  return (
    <li className="flex flex-col gap-1 rounded-lg border p-3" data-testid="result-question">
      <p className="font-medium">{t('review.question', { n: index + 1 })}</p>
      {question?.stem && <p className="text-sm">{question.stem}</p>}
      {question && (
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
          <dt className="text-muted-foreground">{t('review.yourAnswer')}</dt>
          <dd data-testid="result-mine">{mine.length > 0 ? mine.join(', ') : t('review.noAnswer')}</dd>
        </dl>
      )}
      {extra}
    </li>
  )
}

export function QuestionRow({ result, question, index, extra }: { result: QuestionResult; question?: QuestionView;
  index: number; extra?: ReactNode }) {
  const { t } = useTranslation(QB)
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
        <span className="flex-1">{t('review.question', { n: index + 1 })}</span>
        <span className="text-sm">{right ? t('review.right') : t('review.wrong')}</span>
      </p>
      {question?.stem && <p className="text-sm">{question.stem}</p>}
      {question && (
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
          <dt className="text-muted-foreground">{t('review.yourAnswer')}</dt>
          <dd data-testid="result-mine">{mine.length > 0 ? mine.join(', ') : t('review.noAnswer')}</dd>
          {/* Q65: the right answer only where it differs from the taker's. */}
          {!right && (
            <>
              <dt className="text-muted-foreground">{t('review.correctAnswer')}</dt>
              <dd data-testid="result-correct">{correct.join(', ')}</dd>
            </>
          )}
        </dl>
      )}
      <p className="text-muted-foreground text-xs">{t('review.earned', { earned: result.earned, points: result.points })}</p>
      {/* Q60b: what the app shows after the answer (the Taker app: the Sponsor's product for this question). */}
      {extra}
    </li>
  )
}

