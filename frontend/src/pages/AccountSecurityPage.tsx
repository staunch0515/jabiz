import { PageContainer } from '@ant-design/pro-components'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Card, Spin, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { api, unwrap } from '../api/client'
import MfaEnrollment from '../components/MfaEnrollment'

/** The signed-in user's two-step verification (docs/design/10-security.md section 9). */
export default function AccountSecurityPage() {
  const { t } = useTranslation()
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
    </PageContainer>
  )
}
