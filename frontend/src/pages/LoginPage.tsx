import { LockOutlined, UserOutlined } from '@ant-design/icons'
import { LoginForm, ProFormText } from '@ant-design/pro-components'
import { Alert, Select } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { ApiError } from '../api/problem'
import { useAuth } from '../auth/AuthContext'
import { changeLanguage, languages, type Language } from '../i18n'

const LANGUAGE_NAMES: Record<Language, string> = { zh: '中文', ja: '日本語', en: 'English' }

export default function LoginPage() {
  const { t, i18n } = useTranslation()
  const { signIn, signedIn, ready } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [error, setError] = useState<string | null>(null)
  const from = (location.state as { from?: string } | null)?.from ?? '/data'

  if (ready && signedIn) return <Navigate to={from} replace />

  return (
    <div style={{ paddingTop: 80 }}>
      <LoginForm
        title={t('app.title')}
        subTitle={t('login.title')}
        submitter={{ searchConfig: { submitText: t('login.submit') } }}
        actions={
          <Select
            size="small"
            value={i18n.language}
            onChange={(lang) => void changeLanguage(lang as Language)}
            options={languages.map((lang) => ({ value: lang, label: LANGUAGE_NAMES[lang] }))}
            aria-label={t('app.language')}
          />
        }
        onFinish={async (values: { userName: string; password: string }) => {
          setError(null)
          try {
            await signIn(values.userName, values.password)
            navigate(from, { replace: true })
          } catch (e) {
            // The server answers every refusal with the same LOGIN_FAILED, in the language of the request.
            setError(e instanceof ApiError ? e.display : t('app.error'))
          }
        }}
      >
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
      </LoginForm>
    </div>
  )
}
