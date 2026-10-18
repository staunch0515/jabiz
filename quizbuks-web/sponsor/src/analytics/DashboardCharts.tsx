import { Button, PageState, Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { AiUsageMetric } from '../ai/AiUsageMetric'
import { SPONSOR } from '../messages'
import { StatusBadge } from '../publishing/shared'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { Link } from '../qb/router'
import { analyticsPath, zoneTime, type Dashboard } from './api'
import { ScrollRegion, TrendCharts } from './charts'
import { useDashboard, useTop, useTrend } from './hooks'
import { Metric } from './Metric'
import './messages'

/** "Updated every 10 minutes", with the job's last run in the deployment's zone. */
export function UpdatedNote({ dashboard }: { dashboard: Dashboard | undefined }) {
  const { t, i18n } = useTranslation(SPONSOR)
  if (!dashboard) return null
  return (
    <p className="text-muted-foreground text-sm" data-testid="analytics-updated" data-refreshed={dashboard.refreshedAt ?? ''}>
      {dashboard.refreshedAt
        ? t('analytics.updated', { time: zoneTime(dashboard.refreshedAt, dashboard.zone, i18n.language),
          zone: dashboard.zone })
        : t('analytics.notYet')}
    </p>
  )
}

/** The key figures (M-10), live: the publications by state, the takers answering now, today's spending, the wallet. */
function Metrics({ d }: { d: Dashboard }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const locale = i18n.language
  const n = (value: number) => value.toLocaleString(locale)
  return (
    <section aria-label={t('analytics.metrics.label')}
      className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3" data-testid="dashboard-metrics">
      <Metric testId="metric-publications" label={t('analytics.metrics.publications')}
        value={<span className="flex flex-wrap items-baseline gap-x-3 gap-y-1 text-base font-normal">
          <span data-testid="metric-live"><strong className="text-2xl font-semibold">{n(d.live)}</strong>{' '}
            {t('analytics.metrics.live')}</span>
          <span data-testid="metric-paused"><strong className="text-lg font-semibold">{n(d.paused)}</strong>{' '}
            {t('analytics.metrics.paused')}</span>
          <span data-testid="metric-funds-short"><strong className="text-lg font-semibold">{n(d.fundsShort)}</strong>{' '}
            {t('analytics.metrics.fundsShort')}</span>
        </span>} />
      <Metric testId="metric-answering" label={t('analytics.metrics.answering')} value={n(d.answering)}
        hint={t('analytics.live')} />
      <Metric testId="metric-spent-today" label={t('analytics.metrics.spentToday')} value={formatKudos(d.spentToday)}
        hint={d.dayStart ? t('analytics.metrics.spentTodayHint', { time: zoneTime(d.dayStart, d.zone, locale),
          zone: d.zone }) : undefined} />
      <Metric testId="metric-available" label={t('analytics.metrics.available')} value={formatKudos(d.available)} />
      <Metric testId="metric-pending" label={t('analytics.metrics.pending')}
        value={t('analytics.metrics.pendingValue', { amount: formatKudos(d.pendingAmount), count: n(d.pendingCount) })} />
      <Metric testId="metric-budget" label={t('analytics.metrics.budgetRemaining')}
        value={formatKudos(d.budgetRemaining)} hint={t('analytics.metrics.budgetRemainingHint')} />
      {/* Q8a: the AI usage (tokens) of the month (M-10). */}
      <AiUsageMetric zone={d.zone} />
    </section>
  )
}

/** The five publications with the most completions in the last seven days, each leading to its analytics. */
function TopPublications() {
  const { t, i18n } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const top = useTop()
  const locale = i18n.language
  return (
    <section className="flex flex-col gap-2" aria-labelledby="top-title" data-testid="dashboard-top">
      <h2 id="top-title" className="text-base font-semibold">{t('analytics.top.title')}</h2>
      {top.isPending && <PageState kind="loading" />}
      {top.isError && <PageState kind="error" description={t('analytics.failed')}
        action={<Button variant="outline" onClick={() => void top.refetch()}>{tQb('retry')}</Button>} />}
      {top.isSuccess && top.data.length === 0 && (
        <p className="text-muted-foreground text-sm" data-testid="top-empty">{t('analytics.top.empty')}</p>
      )}
      {top.isSuccess && top.data.length > 0 && (
        <ScrollRegion label={t('analytics.top.title')}>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead scope="col">{t('analytics.top.name')}</TableHead>
                <TableHead scope="col">{t('analytics.top.status')}</TableHead>
                <TableHead scope="col" className="text-right">{t('analytics.top.completions7d')}</TableHead>
                <TableHead scope="col" className="text-right">{t('analytics.top.spent7d')}</TableHead>
                <TableHead scope="col" className="text-right">{t('analytics.top.completionsTotal')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {top.data.map((p) => (
                <TableRow key={p.publicationId} data-testid="top-row" data-publication={p.publicationId}>
                  <TableCell>
                    <Link to={analyticsPath(p.publicationId)} className="underline underline-offset-4"
                      aria-label={t('analytics.top.open', { name: p.name })}>
                      {p.name}
                    </Link>
                  </TableCell>
                  <TableCell><StatusBadge status={p.status} startAt={null} endAt={null} /></TableCell>
                  <TableCell className="text-right" data-testid="top-completions">
                    {p.completions7d.toLocaleString(locale)}
                  </TableCell>
                  <TableCell className="text-right">{formatKudos(p.spent7d)}</TableCell>
                  <TableCell className="text-right">{p.completionsTotal.toLocaleString(locale)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </ScrollRegion>
      )}
    </section>
  )
}

/** The trend of the sponsor's publications over the last seven days (M-11). */
function WeekTrend({ zone }: { zone: string }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const trend = useTrend('DAYS_7', null)
  return (
    <section className="flex flex-col gap-2" aria-labelledby="week-title" data-testid="dashboard-trend">
      <h2 id="week-title" className="text-base font-semibold">{t('analytics.trend.week')}</h2>
      {trend.isPending && <PageState kind="loading" />}
      {trend.isError && <PageState kind="error" description={t('analytics.failed')}
        action={<Button variant="outline" onClick={() => void trend.refetch()}>{tQb('retry')}</Button>} />}
      {trend.isSuccess && <TrendCharts points={trend.data.points} range="DAYS_7" zone={zone} testId="week" />}
    </section>
  )
}

/**
 * The approved sponsor's dashboard (M-10, M-11; plan Q9e2): the key figures, the last seven days and the top five
 * publications, read again every minute. Days are the deployment's (the zone the job recorded).
 */
export function DashboardCharts() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const dashboard = useDashboard()
  return (
    <div className="flex flex-col gap-6" data-testid="dashboard-analytics">
      <UpdatedNote dashboard={dashboard.data} />
      {dashboard.isPending && <PageState kind="loading" />}
      {dashboard.isError && <PageState kind="error" description={t('analytics.failed')}
        action={<Button variant="outline" onClick={() => void dashboard.refetch()}>{tQb('retry')}</Button>} />}
      {dashboard.isSuccess && <>
        <Metrics d={dashboard.data} />
        <WeekTrend zone={dashboard.data.zone} />
      </>}
      <TopPublications />
    </div>
  )
}
