import { Alert, Input, Modal, Typography } from 'antd'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { setStepUpHandler, stepUp } from '../api/client'
import { ApiError } from '../api/problem'

/**
 * Asks for a second factor when an operation requires a recent one (403 MFA_REQUIRED; docs/design/10-security.md
 * section 10). The API client waits for the answer and repeats the request with the new access token; cancelling
 * leaves the original refusal to the caller.
 */
export function StepUpProvider({ children }: { children: ReactNode }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notEnrolled, setNotEnrolled] = useState(false)
  const [busy, setBusy] = useState(false)
  const pending = useRef<((passed: boolean) => void) | null>(null)

  useEffect(() => {
    setStepUpHandler(
      () =>
        new Promise<boolean>((resolve) => {
          pending.current = resolve
          setCode('')
          setError(null)
          setNotEnrolled(false)
          setOpen(true)
        }),
    )
    return () => setStepUpHandler(null)
  }, [])

  const finish = (passed: boolean) => {
    setOpen(false)
    pending.current?.(passed)
    pending.current = null
  }

  const submit = async () => {
    setBusy(true)
    setError(null)
    try {
      await stepUp(code.trim())
      finish(true)
    } catch (e) {
      if (e instanceof ApiError && e.violations.some((v) => v.ruleCode === 'MFA_NOT_ENROLLED')) {
        setNotEnrolled(true)
      } else {
        setError(e instanceof ApiError ? e.display : t('app.error'))
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      {children}
      <Modal
        open={open}
        title={t('mfa.stepUpTitle')}
        okText={t('login.verify')}
        cancelText={t('mfa.cancel')}
        okButtonProps={{ disabled: !code.trim() || notEnrolled, loading: busy, id: 'step-up-submit' }}
        onOk={() => void submit()}
        onCancel={() => finish(false)}
        destroyOnHidden
      >
        {notEnrolled ? (
          <Alert type="warning" showIcon message={t('mfa.stepUpNotEnrolled')}
            description={<Link to="/account/security" onClick={() => finish(false)}>{t('app.security')}</Link>} />
        ) : (
          <>
            <Typography.Paragraph>{t('mfa.stepUpHint')}</Typography.Paragraph>
            {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} data-testid="step-up-error" />}
            <Input
              autoFocus
              value={code}
              onChange={(e) => setCode(e.target.value)}
              onPressEnter={() => code.trim() && void submit()}
              placeholder={t('login.code')}
              inputMode="numeric"
              autoComplete="one-time-code"
              aria-label={t('login.code')}
              data-testid="step-up-code"
            />
          </>
        )}
      </Modal>
    </>
  )
}
