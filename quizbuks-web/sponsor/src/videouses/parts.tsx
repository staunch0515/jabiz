import { formatDate, formatDateTime } from '@jabiz/client'
import { Badge } from '@jabiz/ui'
import { CheckCircle2, Clapperboard, Eye, MousePointerClick } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Metric } from '../analytics/Metric'
import { SPONSOR } from '../messages'
import type { Figures, VideoUse, VideoUseRow, VideoUseStatus } from './api'
import './messages'

/** A use's state as a badge. */
export function VideoUseBadge({ status }: { status: VideoUseStatus }) {
  const { t } = useTranslation(SPONSOR)
  return (
    <Badge variant={status === 'CONFIRMED' ? 'default' : status === 'SUBMITTED' ? 'secondary' : 'outline'}
      data-testid="video-use-status" data-status={status}>{t(`videoUses.status.${status}`)}</Badge>
  )
}

/**
 * The four figures of a use (F-S14), the same on both sides: each below the minimum group says "fewer than" it (Q9e),
 * and when they were counted, or that they are final.
 */
export function FiguresGrid({ figures, testId }: { figures: Figures; testId: string }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const n = (value: number | null) => value == null
    ? t('videoUses.fewer', { n: figures.minGroup })
    : value.toLocaleString(i18n.language)
  const suppressed = [figures.opens, figures.successes, figures.goes, figures.qualified].some((v) => v == null)
  return (
    <div className="flex flex-col gap-2" data-testid={testId}>
      {figures.refreshedAt == null
        ? <p className="text-muted-foreground text-sm" data-testid="video-use-not-counted">{t('videoUses.notCounted')}</p>
        : (
          <>
            <p className="text-muted-foreground text-sm" data-testid="video-use-counted">
              {figures.finalisedAt
                ? t('videoUses.finalised', { at: formatDateTime(figures.finalisedAt) })
                : t('videoUses.updated', { at: formatDateTime(figures.refreshedAt) })}
            </p>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <Metric testId={`${testId}-opens`} label={t('videoUses.opens')} icon={Eye} value={n(figures.opens)} />
              <Metric testId={`${testId}-successes`} label={t('videoUses.successes')} icon={Clapperboard}
                value={n(figures.successes)} />
              <Metric testId={`${testId}-goes`} label={t('videoUses.goes')} icon={MousePointerClick}
                value={n(figures.goes)} />
              <Metric testId={`${testId}-qualified`} label={t('videoUses.qualified')} icon={CheckCircle2}
                value={n(figures.qualified)} />
            </div>
            {suppressed && <p className="text-muted-foreground text-xs">{t('videoUses.fewerHint', { n: figures.minGroup })}</p>}
          </>
        )}
    </div>
  )
}

/** What a use says: its pricing and price, period, revision and the reference amount. */
export function UseSummary({ row, use }: { row: VideoUseRow; use: VideoUse }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const price = use.price.toLocaleString(i18n.language)
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm" data-testid="video-use-summary">
      <dt className="text-muted-foreground">{t('videoUses.pricingLabel')}</dt>
      <dd data-testid="video-use-pricing">{t(`videoUses.pricing.${use.pricing}`)} · {t(`videoUses.priceOf.${use.pricing}`, { price })}</dd>
      <dt className="text-muted-foreground">{t('videoUses.periodFrom')} – {t('videoUses.periodTo')}</dt>
      <dd data-testid="video-use-period">{period(use)}</dd>
      <dt className="text-muted-foreground">{t('videoUses.amount')}</dt>
      <dd data-testid="video-use-amount">
        {row.amount == null
          ? <span className="text-muted-foreground">{t('videoUses.amountHidden', { n: row.figures.minGroup })}</span>
          : <>{t('videoUses.priceOf.FIXED', { price: row.amount.toLocaleString(i18n.language) })}
            <span className="text-muted-foreground block text-xs">{t('videoUses.amountHint')}</span></>}
      </dd>
      {use.note && (
        <>
          <dt className="text-muted-foreground">{t('videoUses.note')}</dt>
          <dd className="whitespace-pre-wrap" data-testid="video-use-note">{use.note}</dd>
        </>
      )}
      <dt className="text-muted-foreground">{t('videoUses.revisionLabel')}</dt>
      <dd data-testid="video-use-revision">{t('videoUses.revision', { revision: use.revision })}</dd>
    </dl>
  )
}

/** A use's period as two days. */
export function period(use: Pick<VideoUse, 'periodFrom' | 'periodTo'>): string {
  return `${formatDate(use.periodFrom)} – ${formatDate(use.periodTo)}`
}
