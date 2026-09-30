import { Alert, Button, Input, List, Space, Typography } from 'antd'
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
      QRCode.toCanvas(canvas.current, value, { width: 180, margin: 1 }).catch(() => undefined)
    }
  }, [value])
  return <canvas ref={canvas} data-testid="mfa-qr" aria-hidden />
}

/**
 * Setting up two-step verification (docs/design/10-security.md section 9): the secret as a QR code and as text, a code
 * to confirm it, then the recovery codes, shown once.
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
      <Space direction="vertical" style={{ width: '100%' }}>
        <Typography.Title level={5}>{t('mfa.recoveryTitle')}</Typography.Title>
        <Alert type="warning" showIcon message={t('mfa.recoveryHint')} />
        <List
          size="small"
          bordered
          dataSource={recovery}
          renderItem={(item) => (
            <List.Item>
              <Typography.Text code copyable data-testid="mfa-recovery-code">
                {item}
              </Typography.Text>
            </List.Item>
          )}
        />
        <Button type="primary" onClick={onDone} data-testid="mfa-saved">
          {t('mfa.saved')}
        </Button>
      </Space>
    )
  }

  if (!enrollment) {
    return (
      <Space direction="vertical">
        {error && <Alert type="error" showIcon message={error} />}
        <Button type="primary" loading={busy} data-testid="mfa-start"
          onClick={() => void run(async () => setEnrollment(await begin()))}>
          {t('mfa.start')}
        </Button>
      </Space>
    )
  }

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Paragraph>{t('mfa.scan')}</Typography.Paragraph>
      <QrCode value={enrollment.otpauthUri} />
      <Typography.Text>
        {t('mfa.key')}:{' '}
        <Typography.Text code copyable data-testid="mfa-secret">
          {enrollment.secret}
        </Typography.Text>
      </Typography.Text>
      {error && <Alert type="error" showIcon message={error} data-testid="mfa-error" />}
      <Space.Compact>
        <Input
          value={code}
          onChange={(e) => setCode(e.target.value)}
          placeholder={t('login.code')}
          inputMode="numeric"
          autoComplete="one-time-code"
          aria-label={t('login.code')}
          data-testid="mfa-code"
        />
        <Button type="primary" loading={busy} disabled={!code.trim()} data-testid="mfa-confirm"
          onClick={() => void run(async () => setRecovery(await confirm(code.trim())))}>
          {t('mfa.confirm')}
        </Button>
      </Space.Compact>
    </Space>
  )
}
