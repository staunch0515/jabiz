import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { EXTENSION_NAMESPACE } from '@jabiz/admin'
import { LoadFailed } from './components'
import { readSponsorVisits } from './product-api'

/** A date as `YYYY-MM-DD` in the browser's calendar. */
function isoDate(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/**
 * A sponsor's page views and shop-link clicks in the last 30 days (Q60c-2), on their page's review: the platform's
 * public counters, approximate. An operator's session reads its own sponsors' (others' are none).
 */
export function SponsorVisits({ userId, today = new Date() }: { userId: string; today?: Date }) {
  const { t, i18n } = useTranslation(EXTENSION_NAMESPACE)
  const from = isoDate(new Date(today.getFullYear(), today.getMonth(), today.getDate() - 29))
  const to = isoDate(today)
  const visits = useQuery({ queryKey: ['qb', 'sponsor-visits', userId, from, to],
    queryFn: () => readSponsorVisits(userId, from, to) })
  if (visits.isError) return <LoadFailed error={visits.error} onRetry={() => void visits.refetch()} />
  const number = (n: number | undefined) => (n === undefined ? '…' : n.toLocaleString(i18n.language))
  return (
    <div className="flex flex-col gap-2" data-testid="sponsor-visits">
      <dl className="grid grid-cols-2 gap-3 sm:max-w-md">
        <div className="flex flex-col">
          <dt className="text-muted-foreground text-xs">{t('sponsorProducts.views')}</dt>
          <dd className="text-lg font-semibold tabular-nums" data-testid="sponsor-visits-views">
            {number(visits.data?.views)}</dd>
        </div>
        <div className="flex flex-col">
          <dt className="text-muted-foreground text-xs">{t('sponsorProducts.clicks')}</dt>
          <dd className="text-lg font-semibold tabular-nums" data-testid="sponsor-visits-clicks">
            {number(visits.data?.clicks)}</dd>
        </div>
      </dl>
      <p className="text-muted-foreground text-xs">{t('sponsorProducts.visitsNote')}</p>
    </div>
  )
}
