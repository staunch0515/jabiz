import { Alert, AlertDescription, Button, PageState } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { QB } from '../qb/messages'
import { navigate } from '../qb/router'
import { useMySponsor } from './OnboardingPage'

/**
 * The console's first page. The analytics (M-10, M-11) come with Q9; until then it says where the onboarding stands
 * and leads to it while the sponsor is not approved.
 */
export function DashboardPage() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const sponsor = useMySponsor()
  const status = sponsor.data?.reviewStatus ?? 'NONE'
  const message = status === 'APPROVED' ? t('dashboard.approved')
    : status === 'PENDING' ? t('dashboard.pending')
      : status === 'REJECTED' ? t('dashboard.rejected')
        : t('dashboard.none')
  return (
    <section className="flex max-w-xl flex-col gap-4" aria-labelledby="dashboard-title" data-testid="dashboard">
      <h1 id="dashboard-title" className="text-xl font-semibold">{t('dashboard.title')}</h1>
      {sponsor.isPending && <PageState kind="loading" />}
      {sponsor.isError && <PageState kind="error" description={tQb('failed')} />}
      {sponsor.isSuccess && (
        <Alert variant={status === 'REJECTED' ? 'warning' : 'default'} data-testid="dashboard-status"
          data-status={status}>
          <AlertDescription className="flex flex-col items-start gap-3">
            <span>{message}</span>
            {status !== 'APPROVED' && (
              <Button size="sm" onClick={() => navigate('/onboarding')}>{t('dashboard.toOnboarding')}</Button>
            )}
          </AlertDescription>
        </Alert>
      )}
    </section>
  )
}
