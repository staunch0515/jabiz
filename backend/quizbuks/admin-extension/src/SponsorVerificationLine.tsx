import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { EXTENSION_NAMESPACE } from '@jabiz/admin'
import { Section, StatusBadge } from './components'
import { QUEUE } from './hooks'
import { PATH } from './ids'
import { readLatestVerification } from './verification-api'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

/**
 * A Sponsor's company verification now (F-M3), one line on its page: the state of its latest verification, linking to
 * the list of its verifications. An operator's session reads its own Sponsors' only (the server's scope).
 */
export function SponsorVerificationLine({ userId }: { userId: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const latest = useQuery({
    queryKey: [...QUEUE.sponsorVerifications, 'latest', userId],
    queryFn: () => readLatestVerification(userId),
  })
  if (latest.isPending || latest.isError) return null
  return (
    <Section title={t('verifications.sponsorLine.title')} testId="sponsor-verification">
      <p className="flex flex-wrap items-center gap-3 text-sm">
        {latest.data
          ? <StatusBadge code={latest.data.status} label={t(`verifications.status.${latest.data.status}`)}
            testId="sponsor-verification-status" />
          : <span data-testid="sponsor-verification-none">{t('verifications.sponsorLine.none')}</span>}
        <Link to={`${PATH.sponsorVerifications}?${new URLSearchParams({ sponsorId: userId })}`} className={linkClass}
          data-testid="sponsor-verification-link">{t('verifications.sponsorLine.show')}</Link>
      </p>
    </Section>
  )
}
