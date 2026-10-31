import { formatDateTime, joinEntry } from '@jabiz/client'
import { Alert, AlertDescription, Button, Spinner } from '@jabiz/ui'
import { useMutation, useQuery } from '@tanstack/react-query'
import { BadgeCheck, ExternalLink, Store } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import { claimSponsorApply, MY_SPONSOR_QUERY, useMySponsor } from './api'

/** The sponsor console's onboarding, where a new sponsor's application is written and reviewed. */
export const SPONSOR_ONBOARDING = '/sponsor/onboarding'

/**
 * "Become a sponsor" on the account page (Q69, docs/quizbuks/plans/Q69-certifications.md): only for a taker who is no
 * sponsor yet. Applying needs the certification "may apply to become a sponsor": passing the current round of its
 * quiz (linked here) or a grant. With it, the sponsor sign-in is added to the account (the platform's signed-in join,
 * which checks the certification again), and the application is written in the sponsor console and reviewed as
 * always.
 */
export function BecomeSponsor() {
  const { t } = useTranslation(APP)
  const sponsor = useMySponsor()
  const claim = useQuery({ queryKey: [...MY_SPONSOR_QUERY, 'certification'], queryFn: claimSponsorApply,
    enabled: sponsor.isSuccess && !sponsor.data })
  const join = useMutation({ mutationFn: () => joinEntry('sponsor') })
  if (!sponsor.isSuccess || sponsor.data) return null
  return (
    <section className="flex flex-col gap-3 rounded-lg border p-3" aria-labelledby="become-sponsor-title"
      data-testid="become-sponsor">
      <h2 id="become-sponsor-title" className="flex items-center gap-2 font-semibold">
        <Store aria-hidden className="text-primary size-5" />
        {t('becomeSponsor.title')}
      </h2>
      <p className="text-muted-foreground text-sm">{t('becomeSponsor.intro')}</p>
      {claim.isPending ? <Spinner label={t('becomeSponsor.checking')} />
        : claim.isError ? (
          <Alert variant="destructive"><AlertDescription>{problemText(claim.error)}</AlertDescription></Alert>
        ) : join.isSuccess ? (
          <div className="flex flex-col gap-2" data-testid="become-sponsor-joined">
            <p className="text-sm">{t('becomeSponsor.joined')}</p>
            <Button asChild className="self-start">
              <a href={SPONSOR_ONBOARDING} data-testid="become-sponsor-onboarding">
                <ExternalLink aria-hidden />
                {t('becomeSponsor.onboarding')}
              </a>
            </Button>
          </div>
        ) : claim.data.heldUntil ? (
          <div className="flex flex-col gap-2" data-testid="become-sponsor-held">
            <p className="flex items-center gap-1 text-sm">
              <BadgeCheck aria-hidden className="text-primary size-4" />
              {t('becomeSponsor.held', { time: formatDateTime(claim.data.heldUntil) })}
            </p>
            {join.isError && (
              <Alert variant="destructive"><AlertDescription>{problemText(join.error)}</AlertDescription></Alert>
            )}
            <Button className="self-start" disabled={join.isPending} onClick={() => join.mutate()}
              data-testid="become-sponsor-apply">
              {join.isPending ? <Spinner /> : <Store aria-hidden />}
              {t('becomeSponsor.apply')}
            </Button>
          </div>
        ) : (
          <div className="flex flex-col gap-2" data-testid="become-sponsor-needed">
            <p className="text-sm">{t('becomeSponsor.needed')}</p>
            {claim.data.roundPublicationId
              ? (
                <Button asChild variant="outline" className="self-start">
                  <Link to={`/p/${claim.data.roundPublicationId}`} data-testid="become-sponsor-quiz">
                    {t('becomeSponsor.quiz')}
                  </Link>
                </Button>
              )
              : <p className="text-muted-foreground text-sm" data-testid="become-sponsor-no-round">{t('becomeSponsor.noRound')}</p>}
          </div>
        )}
    </section>
  )
}
