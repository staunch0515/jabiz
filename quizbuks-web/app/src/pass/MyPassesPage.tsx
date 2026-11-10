import { formatDateTime } from '@jabiz/client'
import { Button, PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { AreaHeader } from '../frame/AreaHeader'
import { DUE_PASSES_QUERY, readDuePasses } from '../frame/prizes'
import { PRIZES_PATH } from '../frame/areas'
import '../frame/messages'
import { APP } from '../messages'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import { PassButton } from './PassDialog'

/**
 * "Show my pass" (F-F4 section 2.3), the second tab of scanning: the prizes to collect at a Sponsor's place, each
 * with its pass (QR and six digits, Q89c's `PassButton`). It needs no camera, so it works when the camera is refused
 * or missing.
 */
export function MyPassesPage() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const passes = useQuery({ queryKey: DUE_PASSES_QUERY, queryFn: readDuePasses, staleTime: 0, gcTime: 0 })
  return (
    <section className="flex flex-col gap-4" aria-labelledby="scan-title" data-testid="my-passes">
      <AreaHeader area="scan" tab="pass" id="scan-title" title={t('frame.areas.scan')} />
      <p className="text-muted-foreground text-sm">{t('pass.mine.intro')}</p>
      {passes.isPending ? (
        <PageState kind="loading" />
      ) : passes.isError ? (
        <PageState kind="error" description={problemText(passes.error)}
          action={<Button variant="outline" onClick={() => void passes.refetch()}>{tQb('retry')}</Button>} />
      ) : passes.data.length === 0 ? (
        <PageState kind="empty" title={t('pass.mine.emptyTitle')} description={t('pass.mine.empty')}
          action={<Button asChild variant="outline"><Link to={PRIZES_PATH}>{t('pass.mine.toCollect')}</Link></Button>}
          data-testid="my-passes-empty" />
      ) : (
        <ul className="flex flex-col gap-2" data-testid="my-passes-list">
          {passes.data.map((prize) => (
            <li key={prize.prizeId} className="flex flex-col gap-2 rounded-lg border p-3" data-testid="my-pass">
              <p className="font-medium">{prize.description ?? prize.publicationName}</p>
              <p className="text-muted-foreground text-sm">
                {[prize.publicationName, prize.sponsorCompany].filter(Boolean).join(' · ')}
              </p>
              <p className="text-muted-foreground text-xs">
                {formatKudos(prize.amount)}
                {prize.expiresAt ? ` · ${t('prizes.collectBy', { time: formatDateTime(prize.expiresAt) })}` : ''}
              </p>
              <PassButton redemptionId={prize.prizeId} collected={false} />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
