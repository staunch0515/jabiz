import { resendVerification, useOnline, useOptionalAuth, useServiceWorkerUpdate } from '@jabiz/client'
import { MailWarning, RefreshCw, WifiOff } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Alert, AlertDescription } from '../components/ui/alert'
import { Button } from '../components/ui/button'
import { cn } from '../lib/utils'
import { AUTH_NAMESPACE } from './messages'

export interface VerifyEmailBannerProps {
  /** The address and whether it is verified; the session's (`useAuth`) by default. */
  email?: string | null
  emailVerified?: boolean
  className?: string
}

/**
 * Tells a signed-in user whose address is not verified yet (decision D36: operations may require it) and offers the
 * link again. Nothing for a verified address, nor while not signed in. After following the link, the frontend calls
 * `refreshSession({ force: true })` so that new tokens say the address is verified.
 */
export function VerifyEmailBanner({ className, ...given }: VerifyEmailBannerProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const auth = useOptionalAuth()
  const [sent, setSent] = useState(false)
  const [busy, setBusy] = useState(false)
  const email = given.email !== undefined ? given.email : auth?.email ?? null
  const verified = given.emailVerified ?? auth?.emailVerified ?? true
  const signedIn = given.email !== undefined || !!auth?.signedIn
  if (!signedIn || verified || !email) return null
  return (
    <Alert variant="warning" className={cn('flex-wrap', className)} data-testid="verify-email-banner">
      <MailWarning aria-hidden />
      <AlertDescription className="text-current">
        <span>{t('banner.verifyEmailTo', { email })}</span>
        {sent ? (
          <span role="status" data-testid="verify-email-banner-sent">
            {t('banner.resent')}
          </span>
        ) : (
          <Button
            variant="link"
            size="sm"
            className="h-auto p-0"
            disabled={busy}
            aria-busy={busy || undefined}
            onClick={async () => {
              setBusy(true)
              try {
                await resendVerification(email)
                setSent(true)
              } catch {
                // Answered alike for every address; a failure (offline) leaves the button to try again.
              } finally {
                setBusy(false)
              }
            }}
          >
            {t('banner.resend')}
          </Button>
        )}
      </AlertDescription>
    </Alert>
  )
}

/** Says so while the browser is offline (decision D43 item 6: only the shell works offline); nothing otherwise. */
export function OfflineBanner({ className }: { className?: string }) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const online = useOnline()
  if (online) return null
  return (
    <Alert variant="warning" role="status" className={className} data-testid="offline-banner">
      <WifiOff aria-hidden />
      <AlertDescription className="text-current">{t('banner.offline')}</AlertDescription>
    </Alert>
  )
}

export interface PwaUpdatePromptProps {
  /** Applies the new version; the waiting Service Worker's (`useServiceWorkerUpdate` of `@jabiz/client`) by default. */
  update?: (() => void) | null
  className?: string
}

/**
 * Says that a new version of the PWA is ready (decision D43 item 10) and offers to reload; nothing while there is none.
 * The new version takes over only when the user agrees, so nobody is interrupted half-way through what they are doing.
 */
export function PwaUpdatePrompt({ className, ...given }: PwaUpdatePromptProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const waiting = useServiceWorkerUpdate()
  const update = given.update !== undefined ? given.update : waiting
  if (!update) return null
  return (
    <Alert role="status" className={cn('flex-wrap', className)} data-testid="pwa-update">
      <RefreshCw aria-hidden />
      <AlertDescription className="text-current">
        <span>{t('banner.update')}</span>
        <Button size="sm" className="h-auto" data-testid="pwa-update-reload" onClick={() => update()}>
          {t('banner.reload')}
        </Button>
      </AlertDescription>
    </Alert>
  )
}
