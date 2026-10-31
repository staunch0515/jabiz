import {
  Badge,
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
  Label,
  PageState,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  type ChartConfig,
} from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useId, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Bar, BarChart, CartesianGrid, XAxis, YAxis } from 'recharts'
import { DataDisclosure, ScrollRegion } from '../analytics/charts'
import { SPONSOR } from '../messages'
import { QB } from '../qb/messages'
import {
  periodOf,
  readProductClicks,
  readStatsDays,
  STATS_PERIODS,
  type StatsDay,
  type StatsPeriod,
} from './api'

export const STATS_PATH = '/stats'

/** A day as the reader writes a short date (`10/31`), from its `YYYY-MM-DD`. */
function dayLabel(day: string, locale: string): string {
  const [y, m, d] = day.split('-').map(Number)
  return new Date(y, m - 1, d).toLocaleDateString(locale, { month: 'numeric', day: 'numeric' })
}

/** One measure by day as a small bar chart, named and summed up in text (as the analytics' charts). */
function DayChart({ days, measure, name, testId }: { days: StatsDay[]; measure: 'views' | 'clicks'; name: string;
  testId: string }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const label = t(`stats.${measure}`)
  const color = measure === 'views' ? 'var(--chart-1)' : 'var(--chart-2)'
  const config = useMemo<ChartConfig>(() => ({ [measure]: { label, color } }), [measure, label, color])
  const data = days.map((d) => ({ label: dayLabel(d.day, i18n.language), [measure]: d[measure] }))
  return (
    <figure className="flex flex-col gap-1">
      <figcaption className="text-sm font-medium" data-testid={`${testId}-caption`}>{name}</figcaption>
      <ChartContainer config={config} className="aspect-auto h-32 w-full" role="img" aria-label={name}>
        <BarChart data={data} margin={{ left: 4, right: 12, top: 8, bottom: 0 }} accessibilityLayer={false}>
          <CartesianGrid vertical={false} />
          <XAxis dataKey="label" tickLine={false} axisLine={false} minTickGap={8} />
          <YAxis allowDecimals={false} tickLine={false} axisLine={false} width={36} />
          <ChartTooltip content={<ChartTooltipContent />} />
          <Bar dataKey={measure} name={label} fill={`var(--color-${measure})`} radius={[4, 4, 0, 0]}
            isAnimationActive={false} />
        </BarChart>
      </ChartContainer>
    </figure>
  )
}

/**
 * The views of the sponsor's page and the clicks on their products' shop links (Q60c;
 * docs/quizbuks/plans/Q60-sponsor-page.md section 8): by day over a period ending today, and by product. The numbers
 * are the platform's public counters, approximate, which the page says.
 */
export function StatsPage() {
  const { t, i18n } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const periodId = useId()
  const [days, setDays] = useState<StatsPeriod>(30)
  const period = periodOf(days)
  const daily = useQuery({ queryKey: ['qb', 'page-stats', period.from, period.to],
    queryFn: () => readStatsDays(period) })
  const products = useQuery({ queryKey: ['qb', 'product-clicks', period.from, period.to],
    queryFn: () => readProductClicks(period) })
  const locale = i18n.language
  const sum = (measure: 'views' | 'clicks') => (daily.data ?? []).reduce((total, d) => total + d[measure], 0)

  return (
    <div className="flex flex-col gap-4" data-testid="stats-page">
      <div className="flex flex-col gap-1">
        <h1 className="text-xl font-semibold">{t('stats.title')}</h1>
        <p className="text-muted-foreground text-sm">{t('stats.intro')}</p>
        <p className="text-muted-foreground text-xs" data-testid="stats-approximate">{t('stats.approximate')}</p>
      </div>
      <div className="flex items-center gap-2">
        <Label htmlFor={periodId}>{t('stats.period')}</Label>
        <Select value={String(days)} onValueChange={(value) => setDays(Number(value) as StatsPeriod)}>
          <SelectTrigger id={periodId} className="w-44" data-testid="stats-period"><SelectValue /></SelectTrigger>
          <SelectContent>
            {STATS_PERIODS.map((n) => <SelectItem key={n} value={String(n)}>{t('stats.days', { count: n })}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>
      {daily.isPending ? <PageState kind="loading" />
        : daily.isError ? <PageState kind="error" description={tQb('failed')} />
          : (
            <section className="flex flex-col gap-3" aria-label={t('stats.title')}>
              <DayChart days={daily.data} measure="views" testId="stats-views"
                name={t('stats.viewsChart', { total: sum('views').toLocaleString(locale) })} />
              <DayChart days={daily.data} measure="clicks" testId="stats-clicks"
                name={t('stats.clicksChart', { total: sum('clicks').toLocaleString(locale) })} />
              <DataDisclosure label={t('stats.table')} testId="stats-table">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead scope="col">{t('stats.day')}</TableHead>
                      <TableHead scope="col" className="text-right">{t('stats.views')}</TableHead>
                      <TableHead scope="col" className="text-right">{t('stats.clicks')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {daily.data.map((d) => (
                      <TableRow key={d.day} data-testid="stats-day">
                        <TableHead scope="row" className="font-normal">{dayLabel(d.day, locale)}</TableHead>
                        <TableCell className="text-right">{d.views.toLocaleString(locale)}</TableCell>
                        <TableCell className="text-right">{d.clicks.toLocaleString(locale)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </DataDisclosure>
            </section>
          )}
      <section className="flex flex-col gap-2" aria-labelledby={`${periodId}-products`}>
        <h2 id={`${periodId}-products`} className="text-base font-semibold">{t('stats.products')}</h2>
        {products.isPending ? <PageState kind="loading" />
          : products.isError ? <PageState kind="error" description={tQb('failed')} />
            : products.data.length === 0
              ? <p className="text-muted-foreground text-sm" data-testid="stats-no-products">{t('stats.noProducts')}</p>
              : (
                <ScrollRegion label={t('stats.products')}>
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead scope="col">{t('stats.product')}</TableHead>
                        <TableHead scope="col" className="text-right">{t('stats.clicks')}</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {products.data.map((p) => (
                        <TableRow key={p.productId} data-testid="stats-product">
                          <TableHead scope="row" className="font-normal">
                            <span className="flex flex-wrap items-center gap-2">
                              {p.name}
                              {p.status !== 'LISTED' && (
                                <Badge variant="outline">{t(`stats.status.${p.status}`, p.status)}</Badge>
                              )}
                            </span>
                          </TableHead>
                          <TableCell className="text-right tabular-nums" data-testid="stats-product-clicks">
                            {p.clicks.toLocaleString(locale)}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </ScrollRegion>
              )}
      </section>
    </div>
  )
}
