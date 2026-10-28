import type { IdentityLinked } from '@jabiz/client'
import { PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { AccountInstallEntry } from '../install'
import { AccountSection, AccountSections } from '../qb/AccountSections'
import { readMyProfile } from '../qb/api'
import { LanguageSelect } from '../qb/LanguageSelect'
import { QB } from '../qb/messages'
import { legalPath } from '../qb/legal'
import { Link, navigate, useLocation } from '../qb/router'
import { ProfileDetails } from './ProfileDetails'
import { PROFILE_QUERY, ProfileForm } from './ProfileForm'
import { StaffAccount } from '../staff/StaffAccount'

/** The account page, where a round trip through an identity provider to link an account comes back to. */
export const ACCOUNT_PATH = '/account'

/** The account page opened at the details for matching (Q39), from a quiz the caller's profile does not match. */
export const ACCOUNT_DETAILS_PATH = '/account?section=details'

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
 * the latest sign-ins, the mail settings (Q7a) and the interface language; the taker's ID and their place on a
 * sponsor's staff (Q39).
 */
export function AccountPage() {
  const { t } = useTranslation(QB)
  const { t: tApp } = useTranslation(APP)
  const [linked] = useState(takeLinked)
  const profile = useQuery({ queryKey: PROFILE_QUERY, queryFn: readMyProfile })
  const { search } = useLocation()
  const details = useRef<HTMLDivElement>(null)
  const toDetails = new URLSearchParams(search).get('section') === 'details'
  useEffect(() => {
    if (toDetails) details.current?.scrollIntoView?.({ block: 'start' })
  }, [toDetails])
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
      <div ref={details}>
        <AccountSection title={tApp('details.title')} testId="account-details">
          <ProfileDetails />
        </AccountSection>
      </div>
      <StaffAccount />
      <AccountInstallEntry />
      <AccountSections returnTo={ACCOUNT_PATH} linked={linked} mailHint={tApp('mail.hint')}
        onSignedOut={() => navigate('/login', { replace: true })} />
      <AccountSection title={tApp('account.info')} testId="account-info">
        <ul className="flex flex-col gap-2 text-sm">
          <li><Link to="/help" className="text-primary underline">{t('help.title')}</Link></li>
          <li><Link to={legalPath('TERMS_APP')} className="text-primary underline">{t('legal.kinds.TERMS_APP')}</Link></li>
          <li><Link to={legalPath('PRIVACY')} className="text-primary underline">{t('legal.kinds.PRIVACY')}</Link></li>
          <li><Link to="/about" className="text-primary underline" data-testid="account-about">{tApp('account.about')}</Link></li>
        </ul>
      </AccountSection>
    </section>
  )
}
