import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { AccountSection, AccountSections } from '../qb/AccountSections'
import { LanguageSelect } from '../qb/LanguageSelect'
import { QB } from '../qb/messages'
import { navigate } from '../qb/router'
import { SoundSwitch } from '../qb/SoundSettings'

export const ACCOUNT_PATH = '/account'

/**
 * The sponsor's own account (M-02, M-03, M-70): the address, the password, optional two-step verification (Q-7), the
 * sessions at the console, the latest sign-ins, the mail settings (Q7a), the interface language and the sound effects (Q57). The name and company are the sponsor
 * profile's (onboarding page). No provider accounts: the console offers no identity provider (Q-6).
 */
export function AccountPage() {
  const { t } = useTranslation(QB)
  const { t: tSponsor } = useTranslation(SPONSOR)
  return (
    <section className="flex max-w-xl flex-col gap-8" aria-labelledby="account-title" data-testid="account">
      <div className="flex items-center justify-between gap-2">
        <h1 id="account-title" className="text-xl font-semibold">{t('account.title')}</h1>
        <LanguageSelect />
      </div>
      <AccountSections returnTo={ACCOUNT_PATH} mailHint={tSponsor('mail.hint')}
        onSignedOut={() => navigate('/login', { replace: true })} />
      <AccountSection title={t('sound.title')} testId="account-sound">
        <SoundSwitch />
      </AccountSection>
    </section>
  )
}
