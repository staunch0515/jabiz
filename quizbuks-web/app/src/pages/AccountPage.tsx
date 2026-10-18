import type { IdentityLinked } from '@jabiz/client'
import { PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { AccountSection, AccountSections } from '../qb/AccountSections'
import { readMyProfile } from '../qb/api'
import { LanguageSelect } from '../qb/LanguageSelect'
import { QB } from '../qb/messages'
import { navigate } from '../qb/router'
import { PROFILE_QUERY, ProfileForm } from './ProfileForm'

/** The account page, where a round trip through an identity provider to link an account comes back to. */
export const ACCOUNT_PATH = '/account'

let justLinked: IdentityLinked | null = null

/** Remembers what the identity provider's return linked, for the account page to say once. */
export function rememberLinked(linked: IdentityLinked) {
  justLinked = linked
}

function takeLinked(): IdentityLinked | null {
  const linked = justLinked
  justLinked = null
  return linked
}

/**
 * The taker's own account (U-70, U-71; M-02 for sign-ins): the profile, the address (not changeable, U-70), the
 * password, two-step verification, linked Google accounts when the app offers Google, the sessions at the app and
 * the latest sign-ins, the mail settings (Q7a) and the interface language.
 */
export function AccountPage() {
  const { t } = useTranslation(QB)
  const { t: tApp } = useTranslation(APP)
  const [linked] = useState(takeLinked)
  const profile = useQuery({ queryKey: PROFILE_QUERY, queryFn: readMyProfile })
  return (
    <section className="flex flex-col gap-8" aria-labelledby="account-title" data-testid="account">
      <div className="flex items-center justify-between gap-2">
        <h1 id="account-title" className="text-xl font-semibold">{t('account.title')}</h1>
        <LanguageSelect />
      </div>
      <AccountSection title={tApp('profile.title')} testId="account-profile">
        {profile.isPending && <PageState kind="loading" />}
        {profile.isError && <PageState kind="error" description={t('failed')} />}
        {profile.isSuccess && (
          // The form keeps what was typed; each save sends the revision the server answered last.
          <ProfileForm profile={profile.data} submitLabel={tApp('profile.save')} />
        )}
      </AccountSection>
      <AccountSections returnTo={ACCOUNT_PATH} linked={linked} mailHint={tApp('mail.hint')}
        onSignedOut={() => navigate('/login', { replace: true })} />
    </section>
  )
}
