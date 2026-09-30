import { LockOutlined, SafetyOutlined, UserOutlined } from '@ant-design/icons'
import { LoginForm, ProFormText } from '@ant-design/pro-components'
import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Card, Divider, Select, Space } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import { lastUserName, useAuth, type SignInStep } from '../auth/AuthContext'
import { startProviderSignIn } from '../auth/oidc'
import MfaEnrollment from '../components/MfaEnrollment'
import { changeLanguage, languages, type Language } from '../i18n'
import { LANGUAGE_NAMES } from '../i18n/languages'

type Step =
  | { kind: 'password'; notice?: 'enrolled' }
  | { kind: 'code'; challenge: string }
  | { kind: 'enroll'; challenge: string }

/**
 * Signing in (docs/design/10-security.md sections 4 and 9): the password, then — for users with two-step
 * verification — a code, or the enrolment a role requires before the user signs in again.
 */
export default function LoginPage() {
  const { t, i18n } = useTranslation()
  const { signIn, verify, signedIn, ready } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [error, setError] = useState<string | null>(null)
  const state = location.state as { from?: string; idle?: boolean; step?: SignInStep } | null
  // Back from an identity provider with a second step to take (docs/design/10-security.md section 12).
  const [step, setStep] = useState<Step>(() => {
    const pending = state?.step
    if (pending?.status === 'MFA_REQUIRED') return { kind: 'code', challenge: pending.challenge }
    if (pending?.status === 'MFA_ENROLLMENT_REQUIRED') return { kind: 'enroll', challenge: pending.challenge }
    return { kind: 'password' }
  })
  const from = state?.from ?? '/data'
  const providers = useQuery({
    queryKey: ['auth', 'oidc', 'providers', i18n.language],
    queryFn: () => unwrap(api.GET('/api/auth/oidc/providers')),
    staleTime: Infinity,
  })

  if (ready && signedIn) return <Navigate to={from} replace />

  const failed = (e: unknown) => setError(e instanceof ApiError ? e.display : t('app.error'))

  const language = languages.length > 1 && (
    <Select
      size="small"
      value={i18n.language}
      onChange={(lang) => void changeLanguage(lang as Language)}
      options={languages.map((lang) => ({ value: lang, label: LANGUAGE_NAMES[lang] }))}
      aria-label={t('app.language')}
    />
  )

  if (step.kind === 'enroll') {
    const challenge = step.challenge
    return (
      <div style={{ paddingTop: 80, display: 'flex', justifyContent: 'center' }}>
        <Card title={t('login.enrollTitle')} style={{ width: 420 }} data-testid="mfa-enroll">
          <Space direction="vertical" style={{ width: '100%' }}>
            <Alert type="info" showIcon message={t('login.enrollHint')} />
            <MfaEnrollment
              begin={async () => {
                const answer = await unwrap(api.POST('/api/auth/challenge/enroll', { body: { challenge } }))
                return { secret: answer.secret!, otpauthUri: answer.otpauthUri! }
              }}
              confirm={async (code) =>
                (await unwrap(api.POST('/api/auth/challenge/enroll/confirm', { body: { challenge, code } })))
                  .recoveryCodes ?? []
              }
              onDone={() => setStep({ kind: 'password', notice: 'enrolled' })}
            />
            <Button type="link" onClick={() => setStep({ kind: 'password' })}>{t('login.back')}</Button>
          </Space>
        </Card>
      </div>
    )
  }

  if (step.kind === 'code') {
    const challenge = step.challenge
    return (
      <div style={{ paddingTop: 80 }}>
        <LoginForm
          title={t('app.title')}
          subTitle={t('login.codeTitle')}
          submitter={{ searchConfig: { submitText: t('login.verify') } }}
          actions={<Button type="link" onClick={() => { setError(null); setStep({ kind: 'password' }) }}>{t('login.back')}</Button>}
          onFinish={async (values: { code: string }) => {
            setError(null)
            try {
              await verify(challenge, values.code.trim())
              navigate(from, { replace: true })
            } catch (e) {
              failed(e)
            }
          }}
        >
          <Alert type="info" showIcon message={t('login.codeHint')} style={{ marginBottom: 24 }} />
          {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 24 }} data-testid="login-error" />}
          <ProFormText
            name="code"
            fieldProps={{ size: 'large', prefix: <SafetyOutlined />, autoComplete: 'one-time-code', inputMode: 'numeric', autoFocus: true }}
            placeholder={t('login.code')}
            rules={[{ required: true, message: t('login.codeRequired') }]}
          />
        </LoginForm>
      </div>
    )
  }

  return (
    <div style={{ paddingTop: 80 }}>
      <LoginForm
        title={t('app.title')}
        subTitle={t('login.title')}
        submitter={{ searchConfig: { submitText: t('login.submit') } }}
        actions={language}
        initialValues={{ userName: state?.idle ? lastUserName() : undefined }}
        onFinish={async (values: { userName: string; password: string }) => {
          setError(null)
          try {
            const next = await signIn(values.userName, values.password)
            if (next.status === 'SIGNED_IN') {
              navigate(from, { replace: true })
            } else {
              setStep(next.status === 'MFA_REQUIRED'
                ? { kind: 'code', challenge: next.challenge }
                : { kind: 'enroll', challenge: next.challenge })
            }
          } catch (e) {
            // The server answers every refusal with the same LOGIN_FAILED, in the language of the request.
            failed(e)
          }
        }}
      >
        {state?.idle && step.notice !== 'enrolled' && (
          <Alert type="warning" showIcon message={t('login.idleLocked')} style={{ marginBottom: 24 }} data-testid="idle-locked" />
        )}
        {step.notice === 'enrolled' && (
          <Alert type="success" showIcon message={t('login.enrolled')} style={{ marginBottom: 24 }} data-testid="mfa-enrolled" />
        )}
        {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 24 }} data-testid="login-error" />}
        <ProFormText
          name="userName"
          fieldProps={{ size: 'large', prefix: <UserOutlined />, autoComplete: 'username' }}
          placeholder={t('login.userName')}
          rules={[{ required: true, message: t('login.userNameRequired') }]}
        />
        <ProFormText.Password
          name="password"
          fieldProps={{ size: 'large', prefix: <LockOutlined />, autoComplete: 'current-password' }}
          placeholder={t('login.password')}
          rules={[{ required: true, message: t('login.passwordRequired') }]}
        />
        {(providers.data ?? []).length > 0 && (
          <>
            <Divider plain>{t('login.orWith')}</Divider>
            <Space direction="vertical" style={{ width: '100%', marginBottom: 24 }}>
              {(providers.data ?? []).map((provider) => (
                <Button
                  key={provider.id}
                  block
                  data-testid={`oidc-${provider.id}`}
                  onClick={() => {
                    setError(null)
                    startProviderSignIn(provider.id!, from).catch(failed)
                  }}
                >
                  {provider.label}
                </Button>
              ))}
            </Space>
          </>
        )}
      </LoginForm>
    </div>
  )
}
