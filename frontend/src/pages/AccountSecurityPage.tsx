import { PageContainer } from '@ant-design/pro-components'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Card, Spin, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { AUTH_NAMESPACE, ChangePasswordForm, SessionList } from '@jabiz/ui'
import { api, unwrap } from '../api/client'
import MfaEnrollment from '../components/MfaEnrollment'

/**
 * The signed-in user's two-step verification (docs/design/10-security.md section 9), own password (section 15.8,
 * decision D43 item 3) and sessions (section 15.9). Phase 15c moves the page itself to @jabiz/ui.
 */
export default function AccountSecurityPage() {
  const { t } = useTranslation()
  const { t: tAuth } = useTranslation(AUTH_NAMESPACE)
  const queryClient = useQueryClient()
  const status = useQuery({
    queryKey: ['auth', 'mfa'],
    queryFn: () => unwrap(api.GET('/api/auth/mfa')),
  })

  return (
    <PageContainer title={t('app.security')}>
      <Card title={t('mfa.title')}>
        {status.isLoading && <Spin />}
        {status.data?.enrolled ? (
          <Alert
            type="success"
            showIcon
            data-testid="mfa-status"
            message={t('mfa.enrolled')}
            description={t('mfa.recoveryLeft', { count: status.data.recoveryCodesLeft ?? 0 })}
          />
        ) : (
          status.data && (
            <>
              <Typography.Paragraph data-testid="mfa-status">{t('mfa.notEnrolled')}</Typography.Paragraph>
              <MfaEnrollment
                begin={async () => {
                  const answer = await unwrap(api.POST('/api/auth/mfa/enroll'))
                  return { secret: answer.secret!, otpauthUri: answer.otpauthUri! }
                }}
                confirm={async (code) =>
                  (await unwrap(api.POST('/api/auth/mfa/enroll/confirm', { body: { code } }))).recoveryCodes ?? []
                }
                onDone={() => void queryClient.invalidateQueries({ queryKey: ['auth', 'mfa'] })}
              />
            </>
          )
        )}
      </Card>
      <Card title={tAuth('password.changeTitle')} style={{ marginTop: 16 }}>
        <ChangePasswordForm className="max-w-md" />
      </Card>
      <Card title={tAuth('sessions.title')} style={{ marginTop: 16 }}>
        <SessionList className="max-w-3xl" />
      </Card>
    </PageContainer>
  )
}
