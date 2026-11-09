import { joinEntry } from '@jabiz/client'
import { Alert, AlertDescription, Button, Spinner } from '@jabiz/ui'
import { useMutation } from '@tanstack/react-query'
import { ExternalLink, Store } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { problemText } from '../qb/problems'
import { useMySponsor } from './api'

/** The sponsor console's onboarding, where a new sponsor's application is written and reviewed. */
export const SPONSOR_ONBOARDING = '/sponsor/onboarding'

/**
 * "Become a sponsor" on the account page: only for a Taker who is no Sponsor yet. Applying is open once they lit the
 * knowledge map's node headquarters named for it (F-S2c2b, D7); the platform's signed-in join checks it (the sign-up
 * guard) and says why when it is not open. With it, the Sponsor sign-in is added to the account, and the application
 * is written in the Sponsor app and reviewed as always.
 */
export function BecomeSponsor() {
  const { t } = useTranslation(APP)
  const sponsor = useMySponsor()
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
      {join.isSuccess ? (
        <div className="flex flex-col gap-2" data-testid="become-sponsor-joined">
          <p className="text-sm">{t('becomeSponsor.joined')}</p>
          <Button asChild className="self-start">
            <a href={SPONSOR_ONBOARDING} data-testid="become-sponsor-onboarding">
              <ExternalLink aria-hidden />
              {t('becomeSponsor.onboarding')}
            </a>
          </Button>
        </div>
      ) : (
        <div className="flex flex-col gap-2" data-testid="become-sponsor-needed">
          <p className="text-sm">{t('becomeSponsor.needed')}</p>
          {join.isError && (
            <Alert variant="destructive" data-testid="become-sponsor-refused">
              <AlertDescription>{problemText(join.error)}</AlertDescription>
            </Alert>
          )}
          <Button className="self-start" disabled={join.isPending} onClick={() => join.mutate()}
            data-testid="become-sponsor-apply">
            {join.isPending ? <Spinner /> : <Store aria-hidden />}
            {t('becomeSponsor.apply')}
          </Button>
        </div>
      )}
    </section>
  )
}
