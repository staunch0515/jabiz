import { Alert, Button, Result, Spin } from 'antd'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate, useSearchParams } from 'react-router'
import { ApiError } from '../api/problem'
import { useAuth } from '../auth/AuthContext'
import { takeReturnPath } from '../auth/oidc'

/**
 * Where an identity provider sends the user back (docs/design/10-security.md section 12): hands state and code to the
 * server once, then continues like a sign-in with a password — into the application, or to the second step.
 */
export default function OidcCallbackPage() {
  const { t } = useTranslation()
  const [params] = useSearchParams()
  const { signInWithProvider } = useAuth()
  const navigate = useNavigate()
  const [failure, setFailure] = useState<string | null>(null)
  const started = useRef(false)
  const state = params.get('state')
  const code = params.get('code')
  // The provider refused, or the page was opened without what it needs.
  const refused = !state || !code
    ? params.get('error_description') ?? params.get('error') ?? t('login.providerFailed')
    : null
  const error = refused ?? failure

  useEffect(() => {
    // The code works once: never send it twice (React may run effects twice in development).
    if (started.current || !state || !code) return
    started.current = true
    signInWithProvider(state, code)
      .then((next) => {
        const returnTo = takeReturnPath()
        if (next.status === 'SIGNED_IN') {
          navigate(returnTo, { replace: true })
        } else {
          navigate('/login', { replace: true, state: { from: returnTo, step: next } })
        }
      })
      .catch((e: unknown) => setFailure(e instanceof ApiError ? e.display : t('app.error')))
  }, [state, code, signInWithProvider, navigate, t])

  if (!error) return <Spin fullscreen tip={t('app.loading')} />
  return (
    <Result
      status="warning"
      title={t('login.providerFailed')}
      subTitle={<Alert type="error" showIcon message={error} data-testid="oidc-error" />}
      extra={<Button type="primary" onClick={() => navigate('/login', { replace: true })}>{t('login.back')}</Button>}
    />
  )
}
