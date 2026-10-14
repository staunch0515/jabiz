import {
  formatDateTime,
  isProblem,
  listSessions,
  revokeOtherSessions,
  revokeSession,
  type SessionInfo,
  type SessionList as Sessions,
  type SessionsRevoked,
} from '@jabiz/client'
import { Monitor, Smartphone, Tablet, type LucideIcon } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ConfirmDialog } from '../components/confirm-dialog'
import { Spinner } from '../components/spinner'
import { Badge } from '../components/ui/badge'
import { Button } from '../components/ui/button'
import { cn } from '../lib/utils'
import { AuthError, Outcome } from './frame'
import { AUTH_NAMESPACE } from './messages'
import { failure } from './problems'

type Translate = (key: string, options?: Record<string, unknown>) => string

const DEVICE_ICONS: Record<string, LucideIcon> = { MOBILE: Smartphone, TABLET: Tablet }

/** The minutes an ended session's access token may still work, rounded up (at least one). */
export function effectiveMinutes(seconds: number | undefined): number {
  return Math.max(1, Math.ceil((seconds ?? 0) / 60))
}

/** "Chrome on Windows", from the codes the server told from the user agent; the device kind when nothing is known. */
export function sessionDeviceName(t: Translate, session: SessionInfo): string {
  const browser = session.device?.browser ?? 'OTHER'
  const os = session.device?.os ?? 'OTHER'
  if (browser === 'OTHER' && os === 'OTHER') return t('sessions.unknownDevice')
  if (os === 'OTHER') return t(`sessions.browser.${browser}`)
  if (browser === 'OTHER') return t(`sessions.os.${os}`)
  return t('sessions.deviceName', { browser: t(`sessions.browser.${browser}`), os: t(`sessions.os.${os}`) })
}

/** The icon of a session's kind of device (a computer unless it is known to be a phone or a tablet). */
export function SessionDeviceIcon({ session, className }: { session: SessionInfo; className?: string }) {
  const Icon = DEVICE_ICONS[session.device?.deviceType ?? ''] ?? Monitor
  return <Icon aria-hidden className={cn('text-muted-foreground size-5 shrink-0', className)} />
}

export interface SessionListProps {
  /** Lists the sessions; `listSessions` of `@jabiz/client` by default. Pass a stable function: a new one lists again. */
  list?: () => Promise<Sessions>
  /** Ends one; `revokeSession` by default (ending this one clears the client's session). */
  revoke?: (id: string, current: boolean) => Promise<void>
  /** Ends all others; `revokeOtherSessions` by default. */
  revokeOthers?: () => Promise<SessionsRevoked>
  /** Called once this session itself was ended here (the client is signed out already). */
  onSignedOut?: () => void
  className?: string
}

/**
 * The signed-in user's sessions at this frontend's entry (docs/design/10-security.md section 15.9; phase 16g): the
 * device, address and times of each, this one marked; ending one (after confirming; this one signs out here) or all
 * the others. An ended session can no longer be renewed; a page open there keeps working until its access token
 * expires, which the texts say. Goes on a page of the caller's (a security or account page).
 */
export function SessionList({
  list = listSessions,
  revoke = revokeSession,
  revokeOthers = revokeOtherSessions,
  onSignedOut,
  className,
}: SessionListProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const [sessions, setSessions] = useState<Sessions | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)
  // Listed again whenever this changes (after ending sessions).
  const [version, setVersion] = useState(0)

  useEffect(() => {
    let cancelled = false
    list().then(
      (answer) => {
        if (cancelled) return
        setSessions(answer)
        setError(null)
      },
      (e: unknown) => {
        if (!cancelled) setError(failure(e, t('sessions.failed')))
      },
    )
    return () => {
      cancelled = true
    }
  }, [list, version, t])

  const minutes = effectiveMinutes(sessions?.effectiveWithinSeconds)
  const items = sessions?.sessions ?? []
  const others = items.filter((s) => !s.current)

  const end = async (session: SessionInfo) => {
    setDone(null)
    setError(null)
    try {
      await revoke(session.id!, session.current ?? false)
    } catch (e) {
      setError(failure(e, t('sessions.failed')))
      throw e
    }
    if (session.current) {
      onSignedOut?.()
      return
    }
    setDone(t('sessions.revoked', { minutes }))
    setVersion((v) => v + 1)
  }

  const endOthers = async () => {
    setDone(null)
    setError(null)
    try {
      const result = await revokeOthers()
      setDone(t('sessions.revokedOthers', { count: result.revoked ?? 0, minutes: effectiveMinutes(result.effectiveWithinSeconds) }))
    } catch (e) {
      setError(isProblem(e, 'SESSION_UNKNOWN') ? t('sessions.signInAgain') : failure(e, t('sessions.failed')))
      throw e
    }
    setVersion((v) => v + 1)
  }

  return (
    <div className={cn('flex flex-col gap-4', className)} data-testid="session-list">
      <p className="text-muted-foreground text-sm">{t('sessions.hint')}</p>
      {done && (
        <Outcome ok testId="session-list-state">
          {done}
        </Outcome>
      )}
      {error && <AuthError message={error} testId="session-list-error" />}
      {!sessions && !error && <Spinner />}
      {sessions && (
        <ul className="divide-border flex flex-col divide-y rounded-md border" aria-label={t('sessions.title')}>
          {items.map((session) => (
            <li key={session.id} className="flex flex-wrap items-center gap-3 p-3" data-testid="session-item">
              <SessionDeviceIcon session={session} />
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{sessionDeviceName(t, session)}</span>
                  {session.current && (
                    <Badge variant="success" data-testid="session-current">
                      {t('sessions.current')}
                    </Badge>
                  )}
                  {session.mfa && <Badge variant="outline">{t('sessions.mfa')}</Badge>}
                  {session.oidc && <Badge variant="outline">{t('sessions.oidc')}</Badge>}
                </div>
                <dl className="text-muted-foreground flex flex-wrap gap-x-4 gap-y-1 text-xs">
                  <div className="flex gap-1">
                    <dt>{t('sessions.address')}</dt>
                    <dd>{session.clientIp ?? '-'}</dd>
                  </div>
                  <div className="flex gap-1">
                    <dt>{t('sessions.lastUsed')}</dt>
                    <dd>{session.lastUsedAt ? formatDateTime(session.lastUsedAt) : '-'}</dd>
                  </div>
                  <div className="flex gap-1">
                    <dt>{t('sessions.created')}</dt>
                    <dd>{session.createdAt ? formatDateTime(session.createdAt) : '-'}</dd>
                  </div>
                </dl>
              </div>
              <ConfirmDialog
                destructive
                title={session.current ? t('sessions.revokeCurrentTitle') : t('sessions.revokeTitle')}
                description={session.current ? t('sessions.revokeCurrentHint') : t('sessions.revokeHint', { minutes })}
                confirmLabel={session.current ? t('sessions.revokeCurrent') : t('sessions.revoke')}
                cancelLabel={t('mfa.cancel')}
                onConfirm={() => end(session)}
                trigger={
                  <Button variant="outline" size="sm" data-testid="session-revoke">
                    {session.current ? t('sessions.revokeCurrent') : t('sessions.revoke')}
                  </Button>
                }
              />
            </li>
          ))}
        </ul>
      )}
      {sessions?.truncated && (
        <p className="text-muted-foreground text-xs">{t('sessions.truncated', { count: items.length })}</p>
      )}
      {sessions && others.length === 0 && (
        <p className="text-muted-foreground text-sm" data-testid="session-none">
          {t('sessions.none')}
        </p>
      )}
      {others.length > 0 && (
        <ConfirmDialog
          destructive
          title={t('sessions.revokeOthersTitle')}
          description={t('sessions.revokeOthersHint', { minutes })}
          confirmLabel={t('sessions.revokeOthers')}
          cancelLabel={t('mfa.cancel')}
          onConfirm={endOthers}
          trigger={
            <Button variant="destructive" className="self-start" data-testid="session-revoke-others">
              {t('sessions.revokeOthers')}
            </Button>
          }
        />
      )}
    </div>
  )
}
