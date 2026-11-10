import type { IdentityLinked } from '@jabiz/client'
import { AccountDeletion, PageState, PushPreferences } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AppearanceChoice } from '../frame/AppearanceChoice'
import { AreaHeader } from '../frame/AreaHeader'
import { MePage } from '../frame/MePage'
import '../frame/messages'
import { APP } from '../messages'
import { AccountInstallEntry } from '../install'
import { AccountSection, MailSection, SecuritySections } from '../qb/AccountSections'
import { readMyProfile } from '../qb/api'
import { LanguageSelect } from '../qb/LanguageSelect'
import { QB } from '../qb/messages'
import { SoundSwitch } from '../qb/SoundSettings'
import { Link, navigate, Redirect } from '../qb/router'
import { LikedTopics } from './LikedTopics'
import { BlockList } from '../blocks/BlockList'
import { ProfileDetails } from './ProfileDetails'
import { QuizLanguages } from './QuizLanguages'
import { PROFILE_QUERY, ProfileForm } from './ProfileForm'
import { StaffAccount } from '../staff/StaffAccount'
import { CertificatesPart } from '../certificates/CertificatesPart'
import {
  ACCOUNT_BLOCKS_PATH,
  ACCOUNT_DELETION_PATH,
  ACCOUNT_DETAILS_PATH,
  ACCOUNT_PARTS,
  ACCOUNT_PATH,
  ACCOUNT_SECURITY_PATH,
  accountPath,
  isAccountPath,
  type AccountPart,
} from './accountPaths'

// The addresses live in `accountPaths.ts` (no imports, so that `frame/areas.ts` can read them too); the pages that
// were used to take them from here still can.
export {
  ACCOUNT_BLOCKS_PATH,
  ACCOUNT_DELETION_PATH,
  ACCOUNT_DETAILS_PATH,
  ACCOUNT_PARTS,
  ACCOUNT_PATH,
  ACCOUNT_SECURITY_PATH,
  accountPath,
  isAccountPath,
  type AccountPart,
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

/**
 * "Me" and the parts of the account under it (U-70, U-71; M-02 for sign-ins; Q56, F-F4): the profile, the details for
 * matching (Q47), signing in and security (the address, which cannot be changed, U-70; the password, two-step
 * verification, linked accounts, the sessions and the latest sign-ins), the notifications (the mail, Q7a; push on this
 * device, platform 17k), blocks and topics (F-S8, F-S9), the app's settings (the interface language, the sound
 * effects, the appearance, installing the app), and the taker's ID and their place on a sponsor's staff (Q39). The
 * menu itself is `frame/MePage.tsx`. The old `/account?section=details` leads to the details (`frame/areas.ts`,
 * `LEGACY_APP_PATHS`).
 */
export function AccountPage({ path }: { path: string }) {
  const part = partOf(path)
  if (path === ACCOUNT_DELETION_PATH) return <DeletionPage />
  if (part === undefined) return <Redirect to={ACCOUNT_PATH} />
  if (part === null) return <MePage />
  return <AccountPartPage part={part} />
}

type T = (key: string) => string

/** A part's title: the same words on its page and in the menu. */
function partTitle(part: AccountPart, tApp: T): string {
  switch (part) {
    case 'profile': return tApp('profile.title')
    case 'details': return tApp('details.title')
    case 'mail': return tApp('account.titles.mail')
    default: return tApp(`account.titles.${part}`)
  }
}

/** The way back to "Me" above a part's title. */
function BackToMe() {
  const { t } = useTranslation(APP)
  return (
    <Link to={ACCOUNT_PATH} data-testid="account-back"
      className="text-muted-foreground hover:text-foreground inline-flex w-fit items-center gap-1 text-sm">
      <ArrowLeft aria-hidden className="size-4" />
      {t('account.back')}
    </Link>
  )
}

/** Deleting the account: what is erased and what stays, then the platform's block (the request, its notices). */
function DeletionPage() {
  const { t: tApp } = useTranslation(APP)
  return (
    <section className="flex flex-col gap-6" aria-labelledby="account-part-title" data-testid="account-delete-page">
      <div className="flex flex-col gap-2">
        <BackToMe />
        <AreaHeader area="me" id="account-part-title" title={tApp('account.deletion.title')} />
      </div>
      <p className="text-muted-foreground text-sm">{tApp('account.deletion.hint')}</p>
      <AccountDeletion />
    </section>
  )
}

/** A part of the account on its own page, with the way back to the menu. */
function AccountPartPage({ part }: { part: AccountPart }) {
  const { t: tApp } = useTranslation(APP)
  return (
    <section className="flex flex-col gap-6" aria-labelledby="account-part-title" data-testid={`account-${part}-page`}>
      <div className="flex flex-col gap-2">
        <BackToMe />
        <AreaHeader area="me" id="account-part-title" title={partTitle(part, tApp)} />
      </div>
      <PartContent part={part} />
    </section>
  )
}

function PartContent({ part }: { part: AccountPart }) {
  switch (part) {
    case 'profile': return <ProfilePart />
    case 'details': return <DetailsPart />
    case 'certificates': return <CertificatesPart />
    case 'security': return <SecurityPart />
    case 'mail': return <MailPart />
    case 'preferences': return <PreferencesPart />
    case 'blocks': return <BlocksPart />
    case 'id': return <div className="flex flex-col gap-8"><StaffAccount /></div>
  }
}

/** "About me": the details for matching (Q47). The topics liked moved to "Blocks and topics" (F-F4). */
function DetailsPart() {
  return (
    <div className="flex flex-col gap-8">
      <div data-testid="account-details"><ProfileDetails /></div>
    </div>
  )
}

/** "Blocks and topics" (F-F4): everything blocked, each unblocked on its own (F-S8), and the topics liked (F-S9). */
function BlocksPart() {
  const { t: tApp } = useTranslation(APP)
  return (
    <div className="flex flex-col gap-8">
      <AccountSection title={tApp('blocks.title')} testId="account-blocks">
        <BlockList />
      </AccountSection>
      <AccountSection title={tApp('topics.title')} testId="account-topics">
        <LikedTopics />
      </AccountSection>
    </div>
  )
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

/** The notifications: the mail (Q7a) and, on this device, the push notifications (platform 17k). */
function MailPart() {
  const { t: tApp } = useTranslation(APP)
  return (
    <div className="flex flex-col gap-8">
      <MailSection hint={tApp('mail.hint')} />
      <AccountSection title={tApp('account.push')} testId="account-push">
        <PushPreferences />
      </AccountSection>
    </div>
  )
}

/** The app's own settings: the interface language, the quizzes' languages (Q59a), the sound (Q57), the appearance, installing the app. */
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
      {/* F-F4: the choice the top-right menu had. */}
      <AccountSection title={tApp('account.appearance')} testId="account-appearance">
        <AppearanceChoice />
      </AccountSection>
      <AccountInstallEntry />
    </div>
  )
}
