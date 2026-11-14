import { ApiError } from '@jabiz/client'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { Link } from '../qb/router'
import { AI_CREDIT_CODE, AI_CREDIT_PATH, AI_CREDIT_QUERY, readAiQuote, type AiQuote } from './api'
import './messages'

/** How long the inputs rest before the cost is asked again (the server limits the quotes to 300 an hour). */
const QUOTE_DELAY_MS = 500

function useDebounced<T>(value: T, delayMs: number): T {
  const [shown, setShown] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setShown(value), delayMs)
    return () => clearTimeout(timer)
  }, [value, delayMs])
  return shown
}

/**
 * What the request in a dialog would cost at most (F-M9 b1): asked of the server's read-only quote with the same input
 * as the request, once when the dialog opens and again when the inputs have rested for a moment. `input` null: nothing
 * to ask yet (the form is not valid). `blocked` is true only when the server says it is charged and the credit is short.
 */
export function useAiQuote(process: string, input: Record<string, unknown> | null) {
  const key = input === null ? null : JSON.stringify(input)
  const rested = useDebounced(key, QUOTE_DELAY_MS)
  const quote = useQuery({
    queryKey: [...AI_CREDIT_QUERY, 'quote', process, rested],
    queryFn: () => readAiQuote(process, JSON.parse(rested!) as Record<string, unknown>),
    enabled: rested !== null,
    staleTime: 0,
    gcTime: 0,
    retry: false,
  })
  // While the inputs still change, the answer of the earlier inputs is not shown as this request's.
  const current = rested === key && key !== null
  const data = current ? quote.data : undefined
  // A refused quote (4xx) means the input is not valid yet (an address half typed): the request itself will say so.
  const failed = current && quote.isError && !(quote.error instanceof ApiError && quote.error.status < 500)
  return { data, isError: failed, blocked: data?.charged === true && !data.enough }
}

/** "This request may cost up to X; Y available", or the shortfall with the way to buy; nothing for a free request. */
export function AiQuoteNote({ quote }: { quote: ReturnType<typeof useAiQuote> }) {
  const { t } = useTranslation(SPONSOR)
  const data = quote.data
  if (quote.isError) {
    return <p className="text-muted-foreground text-sm" data-testid="ai-quote-failed">{t('aiCredit.quote.failed')}</p>
  }
  if (!data?.charged) return null
  return (
    <div className="flex flex-col gap-1 text-sm" data-testid="ai-quote-block">
      <p data-testid="ai-quote" data-enough={data.enough}>
        {t('aiCredit.quote.may', { max: formatKudos(data.maxYen), available: formatKudos(data.availableYen) })}
      </p>
      {!data.enough && (
        <p className="text-destructive" role="alert" data-testid="ai-quote-short">
          {t('aiCredit.quote.short', { short: formatKudos(data.shortYen) })}{' '}
          <BuyLink />
        </p>
      )}
    </div>
  )
}

function BuyLink() {
  const { t } = useTranslation(SPONSOR)
  return <Link to={AI_CREDIT_PATH} className="underline" data-testid="ai-quote-buy">{t('aiCredit.quote.buy')}</Link>
}

/** A refusal for the credit (`QB_AI_CREDIT_SHORT`) gives the same way to the page where it is bought. */
export function AiCreditRefusal({ error }: { error: unknown }) {
  const { t } = useTranslation(SPONSOR)
  if (!(error instanceof ApiError) || !error.violations.some((v) => v.ruleCode === AI_CREDIT_CODE.short)) return null
  return (
    <p className="text-destructive text-sm" data-testid="ai-credit-refused">
      {t('aiCredit.quote.refused')} <BuyLink />
    </p>
  )
}

export type { AiQuote }
