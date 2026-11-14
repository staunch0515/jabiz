import { runQuery } from '@jabiz/client'
import { useQuery } from '@tanstack/react-query'
import { Clapperboard, Eye, MousePointerClick, ShoppingCart } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Metric } from '../analytics/Metric'
import { SPONSOR } from '../messages'
import { ExposureFeeSection } from './ExposureFee'
import './messages'

/** The template of the figures (`qb.sponsor.exposure`). */
export const EXPOSURE_TEMPLATE = 'qb.sponsor.exposure'

/** A publication's exposures as the server counts them (F-S13): what is charged is never beyond what was bought. */
export interface ExposureCounts {
  /** None: unlimited. */
  purchased: number | null
  successes: number
  billable: number
  overshoot: number
  opens: number
  goes: number
  status: string
}

const num = (value: unknown) => (value == null || value === '' ? 0 : Number(value))

/** The publication's figures; none when it uses no other Sponsor's video (nothing is counted). */
export async function readExposureCounts(publicationId: string): Promise<ExposureCounts | null> {
  const page = await runQuery<Record<string, unknown>>(EXPOSURE_TEMPLATE, { params: { publicationId }, limit: 1 })
  const row = page.items[0]
  if (!row) return null
  return {
    purchased: row.purchased == null || row.purchased === '' ? null : Number(row.purchased),
    successes: num(row.successes),
    billable: num(row.billable),
    overshoot: num(row.overshoot),
    opens: num(row.opens),
    goes: num(row.goes),
    status: String(row.status ?? ''),
  }
}

/**
 * F-S13 (docs/quizbuks/plans/impl/F-S13.md): on a publication's page, the exposures of the other Sponsor's shared video
 * it uses: bought (or unlimited), counted, charged (never beyond what was bought; what goes beyond is said, not
 * charged), the pushes opened and the Takers who went on to answer. Nothing for a publication using its own videos.
 * F-M8: what they cost and buying more are in {@link ExposureFeeSection}.
 */
export function ExposureSection({ publicationId }: { publicationId: string }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const counts = useQuery({ queryKey: ['qb', 'sponsor', 'exposure', publicationId],
    queryFn: () => readExposureCounts(publicationId) })
  if (!counts.data) return null
  const c = counts.data
  const n = (value: number) => value.toLocaleString(i18n.language)
  const usedUp = c.purchased != null && c.successes >= c.purchased && c.status !== 'ENDED'
  return (
    <section className="flex flex-col gap-2" aria-labelledby="exposure-title" data-testid="exposure-section">
      <h2 id="exposure-title" className="text-sm font-medium">{t('exposure.title')}</h2>
      <p className="text-muted-foreground text-sm">{t('exposure.hint')}</p>
      {usedUp && <p className="text-sm" role="status" data-testid="exposure-used-up">{t('exposure.usedUp')}</p>}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Metric testId="exposure-purchased" label={t('exposure.purchased')} icon={ShoppingCart}
          value={c.purchased == null ? t('exposure.unlimited') : n(c.purchased)} />
        <Metric testId="exposure-successes" label={t('exposure.successes')} icon={Clapperboard}
          value={n(c.successes)}
          hint={c.purchased == null ? undefined : <>
            <span data-testid="exposure-billable">{t('exposure.billable')} {n(c.billable)}</span>
            {c.overshoot > 0 && <span className="block" data-testid="exposure-overshoot">
              {t('exposure.overshoot', { n: n(c.overshoot) })}</span>}
          </>} />
        <Metric testId="exposure-opens" label={t('exposure.opens')} icon={Eye} value={n(c.opens)} />
        <Metric testId="exposure-goes" label={t('exposure.goes')} icon={MousePointerClick} value={n(c.goes)} />
      </div>
      <ExposureFeeSection publicationId={publicationId} status={c.status} />
    </section>
  )
}
