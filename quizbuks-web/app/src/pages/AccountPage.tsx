import { useAuth, type IdentityLinked } from '@jabiz/client'
import { BecomeSponsor } from '../mysponsor/BecomeSponsor'
import { TitleBadge } from './TitleBadge'
import { PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import {
  ArrowLeft,
  CircleHelp,
  FileText,
  IdCard,
  Info,
  Mail,
  ShieldCheck,
  SlidersHorizontal,
  Sparkles,
  UserRound,
  type LucideIcon,
} from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { AccountInstallEntry } from '../install'
import { AccountSection, MailSection, SecuritySections } from '../qb/AccountSections'
import { readMyProfile } from '../qb/api'
import { LanguageSelect } from '../qb/LanguageSelect'
import { QB } from '../qb/messages'
import { legalPath } from '../qb/legal'
import { SoundSwitch } from '../qb/SoundSettings'
import { Link, navigate, Redirect, useLocation } from '../qb/router'
import { MenuItem, MenuList } from './Menu'
import { ProfileDetails } from './ProfileDetails'
import { QuizLanguages } from './QuizLanguages'
import { PROFILE_QUERY, ProfileForm } from './ProfileForm'
import { StaffAccount } from '../staff/StaffAccount'

/** The account's menu (Q56); each part of the account is a page of its own under it. */
export const ACCOUNT_PATH = '/account'

/** The parts of the account, each at `/account/<part>` (docs/quizbuks/plans/Q56-account-menu.md). */
export const ACCOUNT_PARTS = ['profile', 'details', 'security', 'mail', 'preferences', 'id'] as const
export type AccountPart = (typeof ACCOUNT_PARTS)[number]

/** A part's page. */
export function accountPath(part: AccountPart): string {
  return `${ACCOUNT_PATH}/${part}`
}

/** The details for matching (Q47), from a quiz the caller's profile does not match. */
export const ACCOUNT_DETAILS_PATH = accountPath('details')

/** Where a round trip through an identity provider to link an account comes back to, and the step-up's setup. */
export const ACCOUNT_SECURITY_PATH = accountPath('security')

/** Whether a path is the account's menu or one of its parts (the bottom navigation marks the account for all). */
export function isAccountPath(path: string): boolean {
  return path === ACCOUNT_PATH || path.startsWith(`${ACCOUNT_PATH}/`)
}

/** The part a path names; null for the menu, undefined for a part there is not. */
function partOf(path: string): AccountPart | null | undefined {
  if (path === ACCOUNT_PATH) return null
  const part = path.slice(ACCOUNT_PATH.length + 1)
  return (ACCOUNT_PARTS as readonly string[]).includes(part) ? (part as AccountPart) : undefined
}

let justLinked: IdentityLinked | null = null

/** Remembers what the identity provider's return linked, for the security page to say once. */
export function rememberLinked(linked: IdentityLinked) {
  justLinked = linked
}

function takeLinked(): IdentityLinked | null {
  const linked = justLinked
  justLinked = null
  return linked
}

const ICONS: Record<AccountPart, LucideIcon> = {
  profile: UserRound,
  details: Sparkles,
  security: ShieldCheck,
  mail: Mail,
  preferences: SlidersHorizontal,
  id: IdCard,
}

/**
 * The taker's own account (U-70, U-71; M-02 for sign-ins), as a menu of its parts (Q56): the profile, the details for
 * matching (Q47), signing in and security (the address, which cannot be changed, U-70; the password, two-step
 * verification, linked accounts, the sessions and the latest sign-ins), the mail (Q7a), the app's settings (the
 * interface language, the sound effects, installing the app), the taker's ID and their place on a sponsor's staff (Q39); then help and
 * the legal texts. The old `/account?section=details` still opens the details.
 */
export function AccountPage({ path }: { path: string }) {
  const { search } = useLocation()
  const part = partOf(path)
  if (part === null && new URLSearchParams(search).get('section') === 'details') {
    return <Redirect to={ACCOUNT_DETAILS_PATH} />
  }
  if (part === undefined) return <Redirect to={ACCOUNT_PATH} />
  if (part === null) return <AccountMenu />
  return <AccountPartPage part={part} />
}

type T = (key: string) => string

/** A part's title: the same words on its page and in the menu. */
function partTitle(part: AccountPart, t: T, tApp: T): string {
  switch (part) {
    case 'profile': return tApp('profile.title')
    case 'details': return tApp('details.title')
    case 'mail': return t('account.mail')
    default: return tApp(`account.titles.${part}`)
  }
}

/** The menu: who is signed in, the parts, and help and information. */
function AccountMenu() {
  const { t } = useTranslation(QB)
  const { t: tApp } = useTranslation(APP)
  const { email } = useAuth()
  const profile = useQuery({ queryKey: PROFILE_QUERY, queryFn: readMyProfile })
  const name = profile.data?.displayName
  return (
    <section className="flex flex-col gap-6" aria-labelledby="account-title" data-testid="account">
      <div className="flex items-center gap-3">
        <TitleBadge>{(name || email || '?').trim().charAt(0).toUpperCase()}</TitleBadge>
        <div className="min-w-0">
          <h1 id="account-title" className="text-xl font-semibold">{t('account.title')}</h1>
          <p className="text-muted-foreground truncate text-sm" data-testid="account-who">
            {name && email ? `${name} · ${email}` : name || email}
          </p>
        </div>
      </div>
      <MenuList label={tApp('account.settings')}>
        {ACCOUNT_PARTS.map((part) => (
          <MenuItem key={part} to={accountPath(part)} icon={ICONS[part]} title={partTitle(part, t, tApp)}
            hint={tApp(`account.hints.${part}`)} testId={`account-menu-${part}`} />
        ))}
      </MenuList>
      {/* Q69: a taker who is no sponsor may apply, with the certification. */}
      <BecomeSponsor />
      <MenuList label={tApp('account.info')} testId="account-info">
        <MenuItem to="/help" icon={CircleHelp} title={t('help.title')} />
        <MenuItem to={legalPath('TERMS_APP')} icon={FileText} title={t('legal.kinds.TERMS_APP')} />
        <MenuItem to={legalPath('PRIVACY')} icon={FileText} title={t('legal.kinds.PRIVACY')} />
        <MenuItem to="/about" icon={Info} title={tApp('account.about')} testId="account-about" />
      </MenuList>
    </section>
  )
}

/** A part of the account on its own page, with the way back to the menu. */
function AccountPartPage({ part }: { part: AccountPart }) {
  const { t } = useTranslation(QB)
  const { t: tApp } = useTranslation(APP)
  return (
    <section className="flex flex-col gap-6" aria-labelledby="account-part-title" data-testid={`account-${part}-page`}>
      <div className="flex flex-col gap-2">
        <Link to={ACCOUNT_PATH} data-testid="account-back"
          className="text-muted-foreground hover:text-foreground inline-flex w-fit items-center gap-1 text-sm">
          <ArrowLeft aria-hidden className="size-4" />
          {tApp('account.back')}
        </Link>
        <h1 id="account-part-title" className="text-xl font-semibold">{partTitle(part, t, tApp)}</h1>
      </div>
      <PartContent part={part} />
    </section>
  )
}

function PartContent({ part }: { part: AccountPart }) {
  switch (part) {
    case 'profile': return <ProfilePart />
    case 'details': return <div data-testid="account-details"><ProfileDetails /></div>
    case 'security': return <SecurityPart />
    case 'mail': return <MailPart />
    case 'preferences': return <PreferencesPart />
    case 'id': return <div className="flex flex-col gap-8"><StaffAccount /></div>
  }
}

function ProfilePart() {
  const { t } = useTranslation(QB)
  const { t: tApp } = useTranslation(APP)
  const profile = useQuery({ queryKey: PROFILE_QUERY, queryFn: readMyProfile })
  return (
    <div className="flex flex-col gap-3" data-testid="account-profile">
      {profile.isPending && <PageState kind="loading" />}
      {profile.isError && <PageState kind="error" description={t('failed')} />}
      {profile.isSuccess && (
        // The form keeps what was typed; each save sends the revision the server answered last.
        <ProfileForm profile={profile.data} submitLabel={tApp('profile.save')} />
      )}
    </div>
  )
}

function SecurityPart() {
  const [linked] = useState(takeLinked)
  return (
    <div className="flex flex-col gap-8">
      <SecuritySections returnTo={ACCOUNT_SECURITY_PATH} linked={linked}
        onSignedOut={() => navigate('/login', { replace: true })} />
    </div>
  )
}

function MailPart() {
  const { t: tApp } = useTranslation(APP)
  return <MailSection hint={tApp('mail.hint')} title={false} />
}

/** The app's own settings: the interface language, the quizzes' languages (Q59a), the sound (Q57), installing the app. */
function PreferencesPart() {
  const { t } = useTranslation(QB)
  const { t: tApp } = useTranslation(APP)
  return (
    <div className="flex flex-col gap-8">
      <AccountSection title={tApp('account.language')} testId="account-language">
        <div><LanguageSelect /></div>
      </AccountSection>
      <AccountSection title={tApp('account.quizLanguages.title')} testId="account-quiz-languages">
        <QuizLanguages />
      </AccountSection>
      <AccountSection title={t('sound.title')} testId="account-sound">
        <SoundSwitch />
      </AccountSection>
      <AccountInstallEntry />
    </div>
  )
}
