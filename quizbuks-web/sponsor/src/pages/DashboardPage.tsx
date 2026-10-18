import { Alert, AlertDescription, Button, PageState } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { DashboardCharts } from '../analytics/DashboardCharts'
import { SPONSOR } from '../messages'
import { QB } from '../qb/messages'
import { navigate } from '../qb/router'
import { useMySponsor } from './OnboardingPage'

/**
 * The console's first page: where the onboarding stands, leading to it while the sponsor is not approved; once
 * approved, the analytics (M-10, M-11; Q9e2).
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
    <section className="flex flex-col gap-4" aria-labelledby="dashboard-title" data-testid="dashboard">
      <h1 id="dashboard-title" className="text-xl font-semibold">{t('dashboard.title')}</h1>
      {sponsor.isPending && <PageState kind="loading" />}
      {sponsor.isError && <PageState kind="error" description={tQb('failed')} />}
      {sponsor.isSuccess && status !== 'APPROVED' && (
        <Alert variant={status === 'REJECTED' ? 'warning' : 'default'} className="max-w-xl"
          data-testid="dashboard-status" data-status={status}>
          <AlertDescription className="flex flex-col items-start gap-3">
            <span>{message}</span>
            <Button size="sm" onClick={() => navigate('/onboarding')}>{t('dashboard.toOnboarding')}</Button>
          </AlertDescription>
        </Alert>
      )}
      {sponsor.isSuccess && status === 'APPROVED' && <>
        <p className="text-muted-foreground text-sm" data-testid="dashboard-status" data-status={status}>{message}</p>
        <DashboardCharts />
      </>}
    </section>
  )
}
