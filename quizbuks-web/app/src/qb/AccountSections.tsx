import { formatDateTime, listIdentities, useAuth, type IdentityLinked } from '@jabiz/client'
import {
  AUTH_NAMESPACE,
  Badge,
  ChangePasswordForm,
  LinkedIdentities,
  MfaSettings,
  PageState,
  SessionList,
  useOidcProviders,
} from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { readDictionary, readMySignIns } from './api'
import { deviceOf } from './device'
import { DICTIONARY } from './ids'
import { QB } from './messages'

/** One part of an account page: a heading and its content. */
export function AccountSection({ title, children, testId }: { title: string; children: ReactNode; testId?: string }) {
  return (
    <section className="flex flex-col gap-3" data-testid={testId}>
      <h2 className="text-lg font-medium">{title}</h2>
      {children}
    </section>
  )
}

/**
 * The caller's latest sign-ins (M-02; template `qb.me.sign-ins`): when, where to, the result, the address and the
 * device. A list rather than a table, so that it reads on a phone.
 */
export function SignInHistory() {
  const { t } = useTranslation(QB)
  const signIns = useQuery({ queryKey: ['qb', 'sign-ins'], queryFn: () => readMySignIns(10) })
  const outcomes = useQuery({
    queryKey: ['qb', 'dictionary', DICTIONARY.loginOutcome],
    queryFn: () => readDictionary(DICTIONARY.loginOutcome),
    staleTime: Infinity,
  })
  const outcomeLabel = (code: string) => outcomes.data?.find((item) => item.code === code)?.label ?? code
  const entryLabel = (entry: string | null) =>
    entry ? t(`signIns.entries.${entry}`, { defaultValue: entry }) : ''
  if (signIns.isPending) return <PageState kind="loading" />
  if (signIns.isError) return <PageState kind="error" description={t('failed')} />
  if (signIns.data.length === 0) return <p className="text-muted-foreground text-sm">{t('signIns.empty')}</p>
  return (
    <ul className="divide-border flex flex-col divide-y rounded-md border" data-testid="sign-ins">
      {signIns.data.map((row) => (
        <li key={row.loginRecordId} className="flex flex-col gap-1 p-3 text-sm" data-testid="sign-in">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-medium">{formatDateTime(row.attemptTime)}</span>
            <Badge variant={row.outcome === 'SUCCESS' ? 'secondary' : 'outline'}>{outcomeLabel(row.outcome)}</Badge>
            <span className="text-muted-foreground">{entryLabel(row.entry)}</span>
          </div>
          <div className="text-muted-foreground flex flex-wrap gap-x-3">
            <span>
              <span className="sr-only">{t('signIns.device')}: </span>
              {deviceOf(row.userAgent) ?? t('signIns.unknownDevice')}
            </span>
            {row.clientIp && (
              <span>
                <span className="sr-only">{t('signIns.address')}: </span>
                {row.clientIp}
              </span>
            )}
          </div>
        </li>
      ))}
    </ul>
  )
}

/**
 * The parts of an account page both front ends share (docs/quizbuks/plans/QP-pages.md section 3): the address, the
 * password, two-step verification (optional, Q-7), the accounts of identity providers when the entry offers one or
 * some are linked already, the
 * sessions at this entry and the latest sign-ins. All of them are the platform's blocks but the last.
 */
export function AccountSections({ returnTo, linked, onSignedOut }: {
  /** The account page's path, where linking a provider account comes back to. */
  returnTo: string
  /** What the provider's return just linked, said once. */
  linked?: IdentityLinked | null
  /** This session was ended from the list. */
  onSignedOut: () => void
}) {
  const { t } = useTranslation(QB)
  const { t: tAuth } = useTranslation(AUTH_NAMESPACE)
  const { email } = useAuth()
  const providers = useOidcProviders()
  // Accounts linked earlier stay visible (and removable) when the entry no longer offers their provider.
  const identities = useQuery({ queryKey: ['qb', 'identities'], queryFn: listIdentities })
  const hasLinked = (identities.data?.length ?? 0) > 0
  return (
    <>
      <AccountSection title={t('account.email')} testId="account-email">
        <p className="text-sm">{email}</p>
        <p className="text-muted-foreground text-sm">{t('account.emailHint')}</p>
      </AccountSection>
      <AccountSection title={tAuth('password.changeTitle')}>
        <ChangePasswordForm />
      </AccountSection>
      <AccountSection title={t('account.mfa')}>
        <MfaSettings />
      </AccountSection>
      {(providers.length > 0 || linked || hasLinked) && (
        <AccountSection title={tAuth('identities.title')}>
          <LinkedIdentities returnTo={returnTo} linked={linked} providers={providers} />
        </AccountSection>
      )}
      <AccountSection title={tAuth('sessions.title')}>
        <SessionList onSignedOut={onSignedOut} />
      </AccountSection>
      <AccountSection title={t('signIns.title')}>
        <SignInHistory />
      </AccountSection>
    </>
  )
}
