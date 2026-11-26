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
 * points. Q113d-1 (as x.com's list): a row across the column, its mark in a column of its own (40 px), a divider under
 * it; the list is drawn across the page by the caller.
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
    <li className="border-border flex gap-2 border-b px-4 py-3" data-testid="result-question">
      <span aria-hidden className="text-muted-foreground flex w-10 shrink-0 justify-center pt-0.5 text-sm font-bold">
        {index + 1}
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <p className="font-bold">{t('review.question', { n: index + 1 })}</p>
        {question?.stem && <p>{question.stem}</p>}
        {question && (
          <dl className="text-muted-foreground grid grid-cols-[auto_1fr] gap-x-2 gap-y-0.5">
            <dt>{t('review.yourAnswer')}</dt>
            <dd data-testid="result-mine">{mine.length > 0 ? mine.join(', ') : t('review.noAnswer')}</dd>
          </dl>
        )}
        {extra}
      </div>
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
    <li className="border-border flex gap-2 border-b px-4 py-3" data-testid="result-question"
      data-correct={right ? 'true' : 'false'}>
      <span className="flex w-10 shrink-0 justify-center">
        <Icon aria-hidden className={cn('size-6', right ? 'text-success' : 'text-destructive')} strokeWidth={2.2} />
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <p className="flex min-h-5 flex-wrap gap-x-1">
          <span className="font-bold">{t('review.question', { n: index + 1 })}</span>
          <span className="text-muted-foreground">· {right ? t('review.right') : t('review.wrong')}</span>
          <span className="text-muted-foreground">· {t('review.earned', { earned: result.earned, points: result.points })}</span>
        </p>
        {question?.stem && <p>{question.stem}</p>}
        {question && (
          <dl className="text-muted-foreground grid grid-cols-[auto_1fr] gap-x-2 gap-y-0.5">
            <dt>{t('review.yourAnswer')}</dt>
            <dd className="text-foreground" data-testid="result-mine">{mine.length > 0 ? mine.join(', ') : t('review.noAnswer')}</dd>
            {/* Q65: the right answer only where it differs from the taker's. */}
            {!right && (
              <>
                <dt>{t('review.correctAnswer')}</dt>
                <dd className="text-foreground" data-testid="result-correct">{correct.join(', ')}</dd>
              </>
            )}
          </dl>
        )}
        {/* Q60b: what the app shows after the answer (the Taker app: the Sponsor's product for this question). */}
        {extra}
      </div>
    </li>
  )
}

