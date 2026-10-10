import { Alert, AlertDescription, Button, cn, CopyButton, Input, Spinner, UI_SCOPE } from '@jabiz/ui'
import { TriangleAlert } from 'lucide-react'
import QRCode from 'qrcode'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../api/problem'

export interface Enrollment {
  secret: string
  otpauthUri: string
}

interface Props {
  /** Starts the enrolment: a new secret. */
  begin(): Promise<Enrollment>
  /** Confirms it with a code: the recovery codes. */
  confirm(code: string): Promise<string[]>
  /** The user has seen the recovery codes. */
  onDone(): void
}

/** Draws the otpauth address as a QR code; without a canvas (tests) the key alone serves. */
function QrCode({ value }: { value: string }) {
  const canvas = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    if (canvas.current) {
      // Dark modules on white whatever the appearance: authenticator apps read it best that way.
      QRCode.toCanvas(canvas.current, value, { width: 180, margin: 1 }).catch(() => undefined)
    }
  }, [value])
  return <canvas ref={canvas} data-testid="mfa-qr" aria-hidden className="rounded-sm" />
}

function ErrorAlert({ message, testId }: { message: string; testId?: string }) {
  return (
    <Alert variant="destructive" data-testid={testId}>
      <TriangleAlert aria-hidden />
      <AlertDescription>{message}</AlertDescription>
    </Alert>
  )
}

/**
 * Setting up two-step verification (docs/design/10-security.md section 9): the secret as a QR code and as text, a code
 * to confirm it, then the recovery codes, shown once. Used at sign-in (a role requires it) and on the security page.
 */
export default function MfaEnrollment({ begin, confirm, onDone }: Props) {
  const { t } = useTranslation()
  const [enrollment, setEnrollment] = useState<Enrollment | null>(null)
  const [code, setCode] = useState('')
  const [recovery, setRecovery] = useState<string[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const run = async (work: () => Promise<void>) => {
    setError(null)
    setBusy(true)
    try {
      await work()
    } catch (e) {
      setError(e instanceof ApiError ? e.display : t('app.error'))
    } finally {
      setBusy(false)
    }
  }

  if (recovery) {
    return (
      <div className={cn(UI_SCOPE, 'flex flex-col gap-3')}>
        <h2 className="text-base font-semibold">{t('mfa.recoveryTitle')}</h2>
        <Alert variant="warning">
          <TriangleAlert aria-hidden />
          <AlertDescription>{t('mfa.recoveryHint')}</AlertDescription>
        </Alert>
        <ul className="divide-border divide-y rounded-md border">
          {recovery.map((item) => (
            <li key={item} className="flex items-center justify-between gap-2 px-3 py-1">
              <code className="font-mono text-sm" data-testid="mfa-recovery-code">
                {item}
              </code>
              <CopyButton value={item} label={`${t('copy.copy', { ns: 'ui' })} ${item}`} />
            </li>
          ))}
        </ul>
        <Button onClick={onDone} data-testid="mfa-saved">
          {t('mfa.saved')}
        </Button>
      </div>
    )
  }

  if (!enrollment) {
    return (
      <div className={cn(UI_SCOPE, 'flex flex-col items-start gap-3')}>
        {error && <ErrorAlert message={error} />}
        <Button
          disabled={busy}
          aria-busy={busy || undefined}
          data-testid="mfa-start"
          onClick={() => void run(async () => setEnrollment(await begin()))}
        >
          {busy && <Spinner className="text-current" />}
          {t('mfa.start')}
        </Button>
      </div>
    )
  }

  return (
    <div className={cn(UI_SCOPE, 'flex flex-col gap-3')}>
      <p className="text-sm">{t('mfa.scan')}</p>
      <div className="w-fit rounded-md bg-white p-2">
        <QrCode value={enrollment.otpauthUri} />
      </div>
      <p className="flex flex-wrap items-center gap-1 text-sm">
        {t('mfa.key')}:{' '}
        <code className="bg-muted rounded px-1.5 py-0.5 font-mono break-all" data-testid="mfa-secret">
          {enrollment.secret}
        </code>
        <CopyButton value={enrollment.secret} label={`${t('copy.copy', { ns: 'ui' })} ${t('mfa.key')}`} />
      </p>
      {error && <ErrorAlert message={error} testId="mfa-error" />}
      <form
        className="flex gap-2"
        onSubmit={(event) => {
          event.preventDefault()
          if (code.trim()) void run(async () => setRecovery(await confirm(code.trim())))
        }}
      >
        <Input
          value={code}
          onChange={(e) => setCode(e.target.value)}
          placeholder={t('login.code')}
          inputMode="numeric"
          autoComplete="one-time-code"
          aria-label={t('login.code')}
          data-testid="mfa-code"
        />
        <Button type="submit" disabled={busy || !code.trim()} aria-busy={busy || undefined} data-testid="mfa-confirm">
          {busy && <Spinner className="text-current" />}
          {t('mfa.confirm')}
        </Button>
      </form>
    </div>
  )
}
