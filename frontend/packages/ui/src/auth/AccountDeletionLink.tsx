import {
  confirmAccountDeletion,
  isProblem,
  requestAccountDeletionLink,
  sitePath,
  unacknowledgedNotices,
  type AccountDeletionNotice,
  type AccountDeletionRequested,
} from '@jabiz/client'
import { Mail } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Button } from '../components/ui/button'
import { formatDueDate, mergeNotices, NoticeChecklist } from './AccountDeletion'
import {
  AUTH_LINK_CLASS,
  AuthError,
  AuthField,
  AuthFrame,
  AuthLink,
  AuthSubmit,
  ContinueTo,
  Outcome,
  type AuthNavigation,
} from './frame'
import type { MailLinkInfo, MailLinkPageBase } from './MailLinkPage'
import { AUTH_NAMESPACE } from './messages'
import { useAppPath } from './PasswordResetRequest'
import { failure, invalidLink } from './problems'

export interface AccountDeletionRequestProps extends AuthNavigation {
  /** The application's name, the page's heading. */
  brand: ReactNode
  appearance?: boolean
  /** The entry whose account is to be deleted; the frontend's when absent. */
  entry?: string
  /** The sign-in page, as a path of this frontend; the entry's frontend (`app-path`) when absent. */
  signInPath?: string
  /** Sends the request; `requestAccountDeletionLink` of `@jabiz/client` by default. */
  send?: (request: { email: string; entry?: string }) => Promise<void>
}

/**
 * Asking, without signing in, for the account of an address to be deleted (docs/design/10-security.md section 17;
 * decision D62), for whoever no longer has the application installed: the address only; a link to confirm is mailed
 * to it. The answer is the same whatever the address, so that nobody learns who has an account.
 */
export function AccountDeletionRequest({
  brand,
  appearance,
  entry,
  signInPath,
  onNavigate,
  hrefOf,
  send = requestAccountDeletionLink,
}: AccountDeletionRequestProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const form = useForm<{ email: string }>({ defaultValues: { email: '' } })
  const appPath = useAppPath(entry, !signInPath)
  return (
    <AuthFrame brand={brand} appearance={appearance} title={t('accountDeletion.requestTitle')}
      testId="account-deletion-request">
      {done ? (
        <Outcome ok testId="account-deletion-request-state">
          {t('accountDeletion.sent')}
        </Outcome>
      ) : (
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={form.handleSubmit(async ({ email }) => {
            setError(null)
            try {
              await send({ email, entry })
              setDone(true)
            } catch (e) {
              setError(isProblem(e, 'ACCOUNT_DELETION_UNAVAILABLE', 422)
                ? t('accountDeletion.unavailableHere')
                : failure(e, t('account.failed')))
            }
          })}
        >
          <p className="text-muted-foreground text-sm">{t('accountDeletion.requestHint')}</p>
          <AuthField
            icon={Mail}
            type="email"
            placeholder={t('account.email')}
            autoComplete="email"
            error={form.formState.errors.email?.message ?? error ?? undefined}
            {...form.register('email', { validate: (v) => v.trim() !== '' || t('account.emailRequired') })}
          />
          <AuthSubmit busy={form.formState.isSubmitting}>{t('accountDeletion.send')}</AuthSubmit>
        </form>
      )}
      {signInPath ? (
        <AuthLink to={signInPath} onNavigate={onNavigate} hrefOf={hrefOf} className={AUTH_LINK_CLASS}>
          {t('login.back')}
        </AuthLink>
      ) : (
        <a href={appPath} className={AUTH_LINK_CLASS}>
          {t('login.back')}
        </a>
      )}
    </AuthFrame>
  )
}

export type AccountDeletionConfirmProps = MailLinkPageBase & {
  link: MailLinkInfo
  /** Asks for the deletion with the link's token; `confirmAccountDeletion` of `@jabiz/client` by default. */
  confirm?: (request: { token: string; acknowledgedNotices: readonly string[] }) => Promise<AccountDeletionRequested>
}

type ConfirmState = 'ready' | 'done' | 'invalid'

/**
 * Where the link of an account deletion mail leads (decision D62): nothing is sent before the person clicks, so that a
 * mail scanner opening the link changes nothing. The server answers with the notes to confirm first (the link stays
 * good meanwhile); once confirmed, the account is deleted at the end of a waiting period, until which its owner can
 * sign in and withdraw the request. A link that is no good leads to asking for a new one.
 */
export function AccountDeletionConfirm({ link, brand, appearance, confirm = confirmAccountDeletion }:
  AccountDeletionConfirmProps) {
  const { t, i18n } = useTranslation(AUTH_NAMESPACE)
  const { entry, token, appPath } = link
  const [state, setState] = useState<ConfirmState>(token ? 'ready' : 'invalid')
  const [notices, setNotices] = useState<AccountDeletionNotice[]>([])
  const [acknowledged, setAcknowledged] = useState<string[]>([])
  const [noticeError, setNoticeError] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [dueTime, setDueTime] = useState<string | undefined>()
  // Only a path of this site: the same page without its token asks for a new link.
  const again = `${sitePath(window.location.pathname)}?entry=${encodeURIComponent(entry)}`

  const send = async () => {
    setError(null)
    if (notices.some((n) => !acknowledged.includes(n.code!))) {
      setNoticeError(true)
      return
    }
    setNoticeError(false)
    setBusy(true)
    try {
      const answer = await confirm({ token: token!, acknowledgedNotices: acknowledged })
      setDueTime(answer.dueTime)
      setState('done')
    } catch (e) {
      const missing = unacknowledgedNotices(e)
      if (missing.length > 0) {
        setNotices((shown) => mergeNotices(shown, missing))
        setAcknowledged((a) => a.filter((code) => !missing.some((m) => m.code === code)))
        // Notes already shown that changed are read again; the first time, the list says enough.
        if (notices.length > 0) setError(t('accountDeletion.noticesChanged'))
      } else if (invalidLink(e)) {
        setState('invalid')
      } else if (isProblem(e, 'ACCOUNT_DELETION_PROTECTED', 422)) {
        setError(t('accountDeletion.protected'))
      } else if (isProblem(e, 'ACCOUNT_DELETION_PENDING', 422)) {
        setError(t('accountDeletion.alreadyPending'))
      } else {
        setError(failure(e, t('account.failed')))
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthFrame brand={brand} appearance={appearance} title={t('accountDeletion.requestTitle')}
      testId="account-deletion-confirm">
      {state === 'ready' && (
        <>
          {error && <AuthError message={error} testId="account-deletion-error" />}
          <p className="text-sm">{t('accountDeletion.linkHint')}</p>
          <NoticeChecklist
            notices={notices}
            acknowledged={acknowledged}
            invalid={noticeError}
            onChange={(next) => {
              setAcknowledged(next)
              if (notices.every((n) => next.includes(n.code!))) setNoticeError(false)
            }}
          />
          <Button variant="destructive" size="lg" className="w-full" disabled={busy} aria-busy={busy || undefined}
            data-testid="account-deletion-start" onClick={() => void send()}>
            {t('accountDeletion.start')}
          </Button>
        </>
      )}
      {state === 'done' && (
        <Outcome ok testId="account-deletion-state"
          next={<ContinueTo path={appPath} label={t('account.signIn')} />}>
          {t('accountDeletion.linkRequested', { date: formatDueDate(dueTime, i18n.language) })}
        </Outcome>
      )}
      {state === 'invalid' && (
        <Outcome
          ok={false}
          testId="account-deletion-state"
          next={
            <a href={again} className={AUTH_LINK_CLASS} data-testid="account-deletion-again">
              {t('accountDeletion.newLink')}
            </a>
          }
        >
          {t('account.invalidLink')}
        </Outcome>
      )}
    </AuthFrame>
  )
}
