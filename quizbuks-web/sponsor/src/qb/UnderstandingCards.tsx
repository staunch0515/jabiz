import { formatDate } from '@jabiz/client'
import { Button, Card, CardContent, CardDescription, CardHeader, CardTitle, cn } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { Award, CircleCheck } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { CertificationKey } from './ids'
import { QB } from './messages'
import { Link } from './router'
import { readUnderstanding, UNDERSTANDING_QUERY, type Understanding } from './understanding'

/**
 * The "confirm your understanding" cards (Q86, D-Q86-2): one per certification asked for, shown only while the switch
 * is on and a round is open; a held one says so instead. Nothing here blocks anything: consent is unchanged, the quiz
 * is an offer. `hrefOf` says where the round's publication is opened (the app's own page, or the app from the console).
 */
export function UnderstandingCards({ kinds, hrefOf, held = true, at: given, className, testId = 'understanding' }: {
  kinds: readonly CertificationKey[]
  hrefOf: (publicationId: string) => string
  /** Whether a certification held is shown too (the account page); the home shows open ones only. */
  held?: boolean
  /** The time the cards are for; a page reading the same rows elsewhere passes its own so the query is one. */
  at?: string
  className?: string
  testId?: string
}) {
  const [own] = useState(() => new Date().toISOString())
  const at = given ?? own
  const rows = useQuery({ queryKey: [...UNDERSTANDING_QUERY, at], queryFn: () => readUnderstanding(at), retry: false })
  if (!rows.isSuccess) return null
  const shown = rows.data.filter((row) => kinds.includes(row.certification) && row.enabled
    && (row.heldUntil ? held : !!row.roundPublicationId))
  if (shown.length === 0) return null
  return (
    <div className={cn('flex flex-col gap-3', className)} data-testid={testId}>
      {shown.map((row) => <UnderstandingCard key={row.certification} row={row} hrefOf={hrefOf} />)}
    </div>
  )
}

function UnderstandingCard({ row, hrefOf }: { row: Understanding; hrefOf: (publicationId: string) => string }) {
  const { t } = useTranslation(QB)
  return (
    <Card data-testid="understanding-card" data-certification={row.certification} data-held={!!row.heldUntil}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          {row.heldUntil ? <CircleCheck aria-hidden className="text-primary size-5" /> : <Award aria-hidden className="size-5" />}
          {t(`certification.names.${row.certification}`)}
        </CardTitle>
        <CardDescription>
          {row.heldUntil ? t('understanding.held', { until: formatDate(row.heldUntil) }) : t('understanding.title')}
        </CardDescription>
      </CardHeader>
      {!row.heldUntil && row.roundPublicationId && (
        <CardContent className="flex flex-col gap-3">
          <p className="text-sm">{t(`understanding.body.${row.certification}`)}</p>
          <Button asChild className="self-start">
            {/^https?:/.test(hrefOf(row.roundPublicationId))
              ? <a href={hrefOf(row.roundPublicationId)} target="_blank" rel="noopener noreferrer"
                data-testid="understanding-start">{t('understanding.start')}</a>
              : <Link to={hrefOf(row.roundPublicationId)} data-testid="understanding-start">{t('understanding.start')}</Link>}
          </Button>
        </CardContent>
      )}
    </Card>
  )
}
