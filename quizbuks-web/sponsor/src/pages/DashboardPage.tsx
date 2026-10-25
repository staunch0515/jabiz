import { Button, PageState } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { DashboardCharts } from '../analytics/DashboardCharts'
import { SPONSOR } from '../messages'
import { QB } from '../qb/messages'
import { navigate } from '../qb/router'
import { GrowthArt, PlanArt } from '../qb/Illustrations'
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
  const name = sponsor.data?.company || sponsor.data?.contactName
  return (
    <section className="flex flex-col gap-4" aria-labelledby="dashboard-title" data-testid="dashboard">
      {/* The hero band (Q32): the greeting and where the sponsor stands, the illustration beside the text. */}
      <div className="from-qb-hero-from to-qb-hero-to text-qb-hero-foreground relative overflow-hidden rounded-2xl bg-linear-to-br p-5 shadow-sm sm:p-6"
        data-testid="dashboard-hero">
        <div className="relative z-10 flex max-w-xl flex-col gap-2 pr-28 sm:pr-40">
          <h1 id="dashboard-title" className="text-xl font-semibold sm:text-2xl">
            {name ? t('dashboard.greeting', { name }) : t('dashboard.title')}
          </h1>
          {sponsor.isSuccess && (
            <p className="text-qb-hero-muted text-sm" data-testid="dashboard-status" data-status={status}>{message}</p>
          )}
          {sponsor.isSuccess && status !== 'APPROVED' && (
            <Button size="sm" variant="secondary" className="self-start" onClick={() => navigate('/onboarding')}>
              {t('dashboard.toOnboarding')}
            </Button>
          )}
        </div>
        {status === 'APPROVED'
          ? <GrowthArt className="absolute -right-2 -bottom-3 h-28 w-32 sm:h-36 sm:w-40" />
          : <PlanArt className="absolute -right-2 -bottom-3 h-28 w-32 sm:h-36 sm:w-40" />}
      </div>
      {sponsor.isPending && <PageState kind="loading" />}
      {sponsor.isError && <PageState kind="error" description={tQb('failed')} />}
      {sponsor.isSuccess && status === 'APPROVED' && <DashboardCharts />}
    </section>
  )
}
