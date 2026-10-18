import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  DescriptionList,
  PageHeader,
  PageState,
  SegmentedControl,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { publicationPath } from '../publishing/paths'
import { StatusBadge } from '../publishing/shared'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { Link, navigate } from '../qb/router'
import { PUBLICATIONS_PATH } from '../publishing/paths'
import {
  ANALYTICS_QUERY,
  DEFAULT_ZONE,
  percent,
  readOverview,
  readQuestions,
  REFRESH_MS,
  toDashboard,
  TREND_RANGES,
  zoneTime,
  type Difficulty,
  type Overview,
  type QuestionFigures,
  type TrendRange,
} from './api'
import { ScrollRegion, TrendCharts } from './charts'
import { UpdatedNote } from './DashboardCharts'
import { ExportButtons } from './ExportButtons'
import { Funnel } from './Funnel'
import { useDashboard, useTrend } from './hooks'
import { Metric } from './Metric'
import './messages'

/** Only the zone and the last run of the dashboard's row are shown here (before it is read: UTC, as the server). */
const EMPTY_DASHBOARD = toDashboard(undefined)

const live = { refetchInterval: REFRESH_MS, refetchOnWindowFocus: true } as const

const DIFFICULTY_BADGE: Record<Difficulty, 'success' | 'secondary' | 'warning'> = {
  EASY: 'success',
  MEDIUM: 'secondary',
  HARD: 'warning',
}

/** The publication's figures (M-40): counts, the completion rate, and the average time and timeouts over k. */
function OverviewSection({ o, zone }: { o: Overview; zone: string }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const locale = i18n.language
  const n = (value: number) => value.toLocaleString(locale)
  // Every figure here is the job's snapshot (Q9e1 review M1), labelled once with the run it holds for.
  const asOf = o.refreshedAt ? t('analytics.asOf', { time: zoneTime(o.refreshedAt, zone, locale) }) : null
  const hidden = <span className="text-muted-foreground text-base font-normal">{t('analytics.publication.hidden')}</span>
  return (
    <section className="flex flex-col gap-3" aria-labelledby="overview-title" data-testid="analytics-overview">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h2 id="overview-title" className="text-base font-semibold">{t('analytics.publication.overview')}</h2>
        {asOf && <span className="text-muted-foreground text-sm" data-testid="overview-as-of">{asOf}</span>}
      </div>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Metric testId="overview-views" label={t('analytics.publication.views')} value={n(o.views)}
          hint={t('analytics.publication.viewsHint')} />
        <Metric testId="overview-starts" label={t('analytics.publication.starts')} value={n(o.starts)} />
        <Metric testId="overview-completions" label={t('analytics.publication.completions')} value={n(o.completions)}
          hint={t('analytics.publication.completionsHint')} />
        <Metric testId="overview-in-progress" label={t('analytics.publication.inProgress')} value={n(o.inProgress)} />
        <Metric testId="overview-passes" label={t('analytics.publication.passes')} value={n(o.passes)} />
        <Metric testId="overview-winners" label={t('analytics.publication.winners')} value={n(o.winners)} />
        <Metric testId="overview-spent" label={t('analytics.publication.spent')} value={formatKudos(o.spent)}
          hint={t('analytics.publication.spentHint')} />
        <Metric testId="overview-completion-rate" label={t('analytics.publication.completionRate')}
          value={percent(o.completionRate, locale)} />
        <Metric testId="overview-avg-duration" label={t('analytics.publication.avgDuration')}
          value={o.avgDurationSec === null ? hidden
            : t('analytics.publication.seconds', { value: o.avgDurationSec.toLocaleString(locale) })}
          hint={t('analytics.publication.avgDurationHint')} />
        <Metric testId="overview-timeouts" label={t('analytics.publication.timeouts')}
          value={o.timeouts === null ? hidden : n(o.timeouts)} />
      </div>
      <p className="text-muted-foreground text-sm" data-testid="overview-k" data-suppressed={o.suppressed}>
        {o.suppressed
          ? t('analytics.publication.suppressed', { k: o.minGroup, count: n(o.basedOn) })
          : t('analytics.publication.basedOn', { count: n(o.basedOn) })}
      </p>
    </section>
  )
}

/** The App's own rolling counts (U-22), shown as they are beside the trend (M-43). */
function AppWindows({ o, zone }: { o: Overview; zone: string }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const locale = i18n.language
  const n = (value: number | null) => (value === null ? '—' : value.toLocaleString(locale))
  return (
    <div className="flex flex-col gap-2" data-testid="app-windows">
      <h3 className="text-sm font-medium">{t('analytics.app.title')}</h3>
      <DescriptionList columns={4} items={(['last1h', 'last24h', 'last7d', 'last30d'] as const).map((key) => ({
        key,
        label: t(`analytics.app.${key}`),
        value: <span data-testid={`app-${key}`}>{n(o[key])}</span>,
      }))} />
      <p className="text-muted-foreground text-xs">
        {t('analytics.app.hint')}
        {o.appCountedAt && <> {t('analytics.app.countedAt', { time: zoneTime(o.appCountedAt, zone, locale) })}</>}
      </p>
    </div>
  )
}

/** The trend of the last 24 hours by the hour or the last 7 days by the day, with the App's windows beside it. */
function TrendSection({ o, zone, range, onRange, trend }: {
  o: Overview
  zone: string
  range: TrendRange
  onRange: (range: TrendRange) => void
  trend: ReturnType<typeof useTrend>
}) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  return (
    <section className="flex flex-col gap-3" aria-labelledby="trend-title" data-testid="analytics-trend">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="trend-title" className="text-base font-semibold">{t('analytics.trend.title')}</h2>
        <SegmentedControl<TrendRange> aria-label={t('analytics.trend.range')} value={range} onChange={onRange}
          data-testid="trend-range"
          options={TREND_RANGES.map((value) => ({ value, label: t(`analytics.trend.${value}`) }))} />
      </div>
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[2fr_1fr]">
        <div>
          {trend.isPending && <PageState kind="loading" />}
          {trend.isError && <PageState kind="error" description={t('analytics.failed')}
            action={<Button variant="outline" onClick={() => void trend.refetch()}>{tQb('retry')}</Button>} />}
          {trend.isSuccess && <TrendCharts points={trend.data.points} range={range} zone={zone} />}
        </div>
        <AppWindows o={o} zone={zone} />
      </div>
    </section>
  )
}

/** A question's right rate as a bar beside its number (the number is the value; the bar only draws it). */
function RateBar({ rate }: { rate: number }) {
  return (
    <span className="bg-muted relative inline-block h-2 w-20 overflow-hidden rounded-sm align-middle" aria-hidden="true">
      <span className="bg-chart-1 absolute inset-y-0 left-0 rounded-sm"
        style={{ width: `${Math.max(0, Math.min(100, rate * 100))}%` }} />
    </span>
  )
}

/** Each question's right rate and difficulty (M-42), or that it is shown once at least k people have answered. */
function QuestionsSection({ questions, o }: { questions: QuestionFigures[]; o: Overview }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const locale = i18n.language
  const k = questions[0]?.minGroup ?? o.minGroup
  const basedOn = questions[0]?.basedOn ?? 0
  const allSuppressed = questions.length > 0 && questions.every((q) => q.suppressed)
  return (
    <section className="flex flex-col gap-3" aria-labelledby="questions-title" data-testid="analytics-questions">
      <h2 id="questions-title" className="text-base font-semibold">{t('analytics.questions.title')}</h2>
      {questions.length === 0 && (
        <p className="text-muted-foreground text-sm" data-testid="questions-empty">{t('analytics.questions.empty')}</p>
      )}
      {allSuppressed && (
        <Alert data-testid="questions-suppressed">
          <AlertDescription>
            {t('analytics.questions.suppressedAll', { k, count: basedOn.toLocaleString(locale) })}
          </AlertDescription>
        </Alert>
      )}
      {questions.length > 0 && !allSuppressed && (
        <p className="text-muted-foreground text-sm" data-testid="questions-based-on">
          {t('analytics.questions.basedOn', { count: basedOn.toLocaleString(locale), k })}
        </p>
      )}
      {questions.length > 0 && (
        <ScrollRegion label={t('analytics.questions.label')}>
          <Table aria-label={t('analytics.questions.label')}>
            <TableHeader>
              <TableRow>
                <TableHead scope="col" className="w-12">{t('analytics.questions.no')}</TableHead>
                <TableHead scope="col">{t('analytics.questions.stem')}</TableHead>
                <TableHead scope="col" className="text-right">{t('analytics.questions.points')}</TableHead>
                <TableHead scope="col" className="text-right">{t('analytics.questions.answered')}</TableHead>
                <TableHead scope="col">{t('analytics.questions.correctRate')}</TableHead>
                <TableHead scope="col">{t('analytics.questions.difficulty')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {questions.map((q) => (
                <TableRow key={q.questionNo} data-testid="question-row" data-suppressed={q.suppressed}>
                  <TableCell>{q.questionNo}</TableCell>
                  <TableCell className="max-w-md whitespace-normal">
                    <span className="line-clamp-2">{q.stem}</span>
                  </TableCell>
                  <TableCell className="text-right">{q.points}</TableCell>
                  {q.suppressed ? (
                    <TableCell colSpan={3} className="text-muted-foreground whitespace-normal"
                      data-testid="question-suppressed">
                      {t('analytics.questions.suppressed', { k })}
                    </TableCell>
                  ) : (
                    <>
                      <TableCell className="text-right">{q.completions?.toLocaleString(locale) ?? '—'}</TableCell>
                      <TableCell>
                        <span className="flex items-center gap-2">
                          {q.correctRate !== null && <RateBar rate={q.correctRate} />}
                          <span data-testid="question-rate">{percent(q.correctRate, locale)}</span>
                        </span>
                      </TableCell>
                      <TableCell>
                        {q.difficulty && <Badge variant={DIFFICULTY_BADGE[q.difficulty]} data-testid="question-difficulty"
                          data-difficulty={q.difficulty}>{t(`analytics.questions.${q.difficulty}`)}</Badge>}
                      </TableCell>
                    </>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </ScrollRegion>
      )}
      <p className="text-muted-foreground text-xs">{t('analytics.questions.difficultyHint')}</p>
    </section>
  )
}

/**
 * A publication's analytics (`/publications/<id>/analytics`; M-40 – M-43): the overview, the funnel with its largest
 * drop, the trend by the hour or by the day beside the App's own counts, each question's right rate over the minimum
 * group, and the export. Only the sponsor's own publications are found; anything else is "does not exist".
 */
export function PublicationAnalyticsPage({ publicationId }: { publicationId: string }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const [range, setRange] = useState<TrendRange>('HOURS_24')
  const dashboard = useDashboard()
  const zone = dashboard.data?.zone ?? DEFAULT_ZONE
  const overview = useQuery({
    queryKey: [...ANALYTICS_QUERY, 'overview', publicationId],
    queryFn: () => readOverview(publicationId),
    ...live,
  })
  const found = overview.isSuccess && overview.data !== null
  const questions = useQuery({
    queryKey: [...ANALYTICS_QUERY, 'questions', publicationId],
    queryFn: () => readQuestions(publicationId),
    enabled: found,
    ...live,
  })
  const trend = useTrend(range, publicationId, found)

  if (overview.isPending) return <PageState kind="loading" />
  if (overview.isError) {
    return <PageState kind="error" titleAs="h1" description={t('analytics.failed')}
      action={<Button variant="outline" onClick={() => void overview.refetch()}>{tQb('retry')}</Button>} />
  }
  const o = overview.data
  if (!o) {
    return <PageState kind="notFound" titleAs="h1" description={t('analytics.publication.notFound')}
      action={<Button variant="outline" onClick={() => navigate(PUBLICATIONS_PATH)}>
        {t('publishing.detail.backToList')}
      </Button>} />
  }
  return (
    <div className="flex flex-col gap-8" data-testid="publication-analytics" data-publication={publicationId}>
      <PageHeader
        breadcrumb={
          <nav aria-label={t('analytics.publication.breadcrumb')} className="text-sm">
            <Link to={publicationPath(publicationId)}
              className="text-muted-foreground underline-offset-4 hover:underline">{o.name}</Link>
          </nav>
        }
        title={<span data-testid="analytics-title">{t('analytics.publication.title', { name: o.name })}</span>}
        description={<StatusBadge status={o.status} startAt={null} endAt={null} />}
        className="pb-0" />
      <UpdatedNote dashboard={{ ...(dashboard.data ?? EMPTY_DASHBOARD), zone, refreshedAt: o.refreshedAt }} />
      <OverviewSection o={o} zone={zone} />
      <section className="flex flex-col gap-3" aria-labelledby="funnel-title">
        <h2 id="funnel-title" className="text-base font-semibold">{t('analytics.funnel.title')}</h2>
        <Funnel overview={o} />
      </section>
      <TrendSection o={o} zone={zone} range={range} onRange={setRange} trend={trend} />
      {questions.isPending && <PageState kind="loading" />}
      {questions.isError && <PageState kind="error" description={t('analytics.failed')}
        action={<Button variant="outline" onClick={() => void questions.refetch()}>{tQb('retry')}</Button>} />}
      {questions.isSuccess && <QuestionsSection questions={questions.data} o={o} />}
      <ExportButtons params={{
        trend: trend.data?.params ?? null,
        overview: { publicationId },
        questions: { publicationId },
      }} />
    </div>
  )
}
