import { api, unwrap, type components } from '@jabiz/client'
import { CircleCheck } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Spinner } from '../components/spinner'
import { Alert, AlertDescription, AlertTitle } from '../components/ui/alert'
import { cn, UI_SCOPE } from '../lib/utils'
import { AuthError } from './frame'
import { AUTH_NAMESPACE } from './messages'
import { MfaEnrollment, type Enrollment } from './MfaEnrollment'
import { failure } from './problems'

export type MfaStatus = components['schemas']['MfaStatus']

/** The signed-in user's two-step verification (`GET /api/auth/mfa`). */
async function mfaStatus(): Promise<MfaStatus> {
  return unwrap(api.GET('/api/auth/mfa'))
}

/** Starts setting it up (`POST /api/auth/mfa/enroll`): a new secret. */
async function beginEnrollment(): Promise<Enrollment> {
  const answer = await unwrap(api.POST('/api/auth/mfa/enroll'))
  return { secret: answer.secret!, otpauthUri: answer.otpauthUri! }
}

/** Confirms it with a code (`POST /api/auth/mfa/enroll/confirm`): the recovery codes. */
async function confirmEnrollment(code: string): Promise<string[]> {
  return (await unwrap(api.POST('/api/auth/mfa/enroll/confirm', { body: { code } }))).recoveryCodes ?? []
}

export interface MfaSettingsProps {
  /** Reads the status; `GET /api/auth/mfa` by default. Pass a stable function: a new one reads again. */
  status?: () => Promise<MfaStatus>
  /** Starts setting it up; `POST /api/auth/mfa/enroll` by default. */
  begin?: () => Promise<Enrollment>
  /** Confirms it with a code; `POST /api/auth/mfa/enroll/confirm` by default. */
  confirm?: (code: string) => Promise<string[]>
  /** Called once it is set up and the recovery codes were seen (the status is read again here as well). */
  onEnrolled?: () => void
  className?: string
}

/**
 * The signed-in user's two-step verification (docs/design/10-security.md section 9): whether it is on and how many
 * recovery codes are left, or, while it is off, setting it up ({@link MfaEnrollment}). A part of an account page (the
 * admin's security page, an application's account page); the caller gives the heading. The status element is
 * `data-testid="mfa-status"`.
 */
export function MfaSettings({
  status = mfaStatus,
  begin = beginEnrollment,
  confirm = confirmEnrollment,
  onEnrolled,
  className,
}: MfaSettingsProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const [current, setCurrent] = useState<MfaStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  // Read again whenever this changes (once set up).
  const [version, setVersion] = useState(0)

  useEffect(() => {
    let cancelled = false
    status().then(
      (answer) => {
        if (cancelled) return
        setCurrent(answer)
        setError(null)
      },
      (e: unknown) => {
        if (!cancelled) setError(failure(e, t('common.error')))
      },
    )
    return () => {
      cancelled = true
    }
  }, [status, version, t])

  return (
    <div className={cn(UI_SCOPE, 'flex flex-col gap-4', className)} data-testid="mfa-settings">
      {error && <AuthError message={error} testId="mfa-settings-error" />}
      {!current && !error && <Spinner />}
      {current?.enrolled && (
        <Alert role="status" data-testid="mfa-status">
          <CircleCheck aria-hidden className="text-success" />
          <AlertTitle>{t('mfa.on')}</AlertTitle>
          <AlertDescription>{t('mfa.recoveryLeft', { count: current.recoveryCodesLeft ?? 0 })}</AlertDescription>
        </Alert>
      )}
      {current && !current.enrolled && (
        <>
          <p data-testid="mfa-status">{t('mfa.off')}</p>
          <MfaEnrollment
            begin={begin}
            confirm={confirm}
            onDone={() => {
              setVersion((v) => v + 1)
              onEnrolled?.()
            }}
          />
        </>
      )}
    </div>
  )
}
