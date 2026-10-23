import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { Profile } from '../qb/api'
import { APP } from '../messages'
import { navigate } from '../qb/router'
import { forgetShareNext, shareNext } from '../share/share'
import { ProfileForm } from './ProfileForm'

/**
 * Registration's second step (U-02; D-Q2-2): a signed-in taker without a complete profile comes here first. Saving it
 * (`QB_PROFILE_SAVE`, which needs a verified address) completes registration and sends the welcome mail; then home.
 * A profile may exist already with the name only (a sponsor's, when the same person signs up for the app). The
 * takers' terms and the privacy policy in effect are accepted here (Q7-3; with Google sign-up too). After a share
 * link (Q15) the step is the short one, country and terms, and leads to the publication.
 */
export function WelcomePage({ profile }: { profile: Profile | null }) {
  const { t } = useTranslation(APP)
  const [next] = useState(shareNext)
  return (
    <section className="flex flex-col gap-4" aria-labelledby="welcome-title" data-testid="welcome">
      <h1 id="welcome-title" className="text-xl font-semibold">{t('welcome.title')}</h1>
      <p className="text-muted-foreground text-sm">{next ? t('welcome.briefIntro') : t('welcome.intro')}</p>
      <ProfileForm profile={profile} submitLabel={t('welcome.save')} consent brief={next !== null}
        onSaved={() => {
          forgetShareNext()
          navigate(next ?? '/', { replace: true })
        }} />
    </section>
  )
}
