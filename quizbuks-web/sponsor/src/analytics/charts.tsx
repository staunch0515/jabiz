import {
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  type ChartConfig,
} from '@jabiz/ui'
import { useId, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Bar, BarChart, CartesianGrid, Line, LineChart, XAxis, YAxis } from 'recharts'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { bucketLabel, type TrendPoint, type TrendRange } from './api'
import './messages'

/*
 * The trend as two small charts one above the other (completions as a line, Kudos spent as bars): two measures of
 * different scale never share a chart with two axes. Each chart is named and summed up in text (`aria-label` and a
 * caption), and its numbers are in a table below it, opened by a native disclosure (keyboard and screen readers).
 * Colours are the theme's chart tokens only (`--chart-1`, `--chart-2`), defined for light and dark.
 */

/**
 * A wide table that scrolls sideways on a narrow screen: the scrolling container is a named region that takes the
 * focus (keyboard users scroll it with the arrow keys), as the platform's `DataTable` does; the table's own container
 * then does not scroll.
 */
export function ScrollRegion({ label, children, className }: { label: string; children: ReactNode;
  className?: string }) {
  return (
    <div role="region" aria-label={label} tabIndex={0}
      className={`focus-visible:ring-ring/50 overflow-x-auto rounded-md outline-none focus-visible:ring-[3px] [&>[data-slot=table-container]]:overflow-visible ${className ?? ''}`}>
      {children}
    </div>
  )
}

/** A table behind a native disclosure: the data of a chart for those who cannot read the chart. */
export function DataDisclosure({ label, children, testId }: { label: string; children: ReactNode; testId?: string }) {
  return (
    <details className="group text-sm" data-testid={testId}>
      <summary className="text-primary w-fit cursor-pointer underline-offset-4 hover:underline">{label}</summary>
      <ScrollRegion label={label} className="mt-2">{children}</ScrollRegion>
    </details>
  )
}

const COMPLETIONS: ChartConfig = { completions: { label: 'completions', color: 'var(--chart-1)' } }
const SPENT: ChartConfig = { spent: { label: 'spent', color: 'var(--chart-2)' } }

interface Plotted {
  label: string
  completions: number
  spent: number
  gap: boolean
}

/** Whole Kudos as a number for the chart's scale only; the texts keep the server's decimal. */
const kudosNumber = (value: string) => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

/**
 * The trend's charts and their table. `range` picks the buckets' labels: the hour or the day in the deployment's
 * `zone` (D-Q9e-3), never the browser's.
 */
export function TrendCharts({ points, range, zone, testId = 'trend' }: {
  points: TrendPoint[]
  range: TrendRange
  zone: string
  testId?: string
}) {
  const { t, i18n } = useTranslation(SPONSOR)
  const captionId = useId()
  const locale = i18n.language
  const data: Plotted[] = points.map((p) => ({
    label: bucketLabel(p, range, zone, locale),
    completions: p.completions,
    spent: kudosNumber(p.spent),
    gap: p.gap,
  }))
  const unit = range === 'HOURS_24' ? t('analytics.trend.unitHour') : t('analytics.trend.unitDay')
  const totalCompletions = points.reduce((sum, p) => sum + p.completions, 0)
  const totalSpent = points.reduce((sum, p) => sum + kudosNumber(p.spent), 0)
  const completionsName = t('analytics.trend.completionsChart', { unit, total: totalCompletions.toLocaleString(locale) })
  const spentName = t('analytics.trend.spentChart', { unit, total: formatKudos(totalSpent) })
  const anyGap = points.some((p) => p.gap)
  const empty = points.every((p) => p.completions === 0 && p.views === 0 && p.starts === 0 && Number(p.spent) === 0)
  const interval = range === 'HOURS_24' ? 2 : 0

  return (
    <div className="flex flex-col gap-3" data-testid={testId} data-range={range} data-buckets={points.length}>
      <p id={captionId} className="text-muted-foreground text-xs">
        {t('analytics.trend.zoneNote', { zone })}
        {empty && <> {t('analytics.trend.empty')}</>}
      </p>
      <figure className="flex flex-col gap-1" aria-describedby={captionId}>
        <figcaption className="text-sm font-medium" data-testid={`${testId}-completions-caption`}>
          {completionsName}
        </figcaption>
        <ChartContainer config={COMPLETIONS} className="aspect-auto h-36 w-full" role="img"
          aria-label={completionsName}>
          <LineChart data={data} margin={{ left: 4, right: 12, top: 8, bottom: 0 }} accessibilityLayer={false}>
            <CartesianGrid vertical={false} />
            <XAxis dataKey="label" tickLine={false} axisLine={false} interval={interval} minTickGap={8} />
            <YAxis allowDecimals={false} tickLine={false} axisLine={false} width={36} />
            <ChartTooltip content={<ChartTooltipContent />} />
            <Line dataKey="completions" name={t('analytics.trend.completions')} type="monotone"
              stroke="var(--color-completions)" strokeWidth={2} dot={{ r: 3 }} activeDot={{ r: 5 }}
              isAnimationActive={false} />
          </LineChart>
        </ChartContainer>
      </figure>
      <figure className="flex flex-col gap-1" aria-describedby={captionId}>
        <figcaption className="text-sm font-medium" data-testid={`${testId}-spent-caption`}>{spentName}</figcaption>
        <ChartContainer config={SPENT} className="aspect-auto h-28 w-full" role="img" aria-label={spentName}>
          <BarChart data={data} margin={{ left: 4, right: 12, top: 8, bottom: 0 }} accessibilityLayer={false}>
            <CartesianGrid vertical={false} />
            <XAxis dataKey="label" tickLine={false} axisLine={false} interval={interval} minTickGap={8} />
            <YAxis allowDecimals={false} tickLine={false} axisLine={false} width={48} />
            <ChartTooltip content={<ChartTooltipContent formatter={(value) => formatKudos(value)} />} />
            <Bar dataKey="spent" name={t('analytics.trend.spent')} fill="var(--color-spent)" radius={[4, 4, 0, 0]}
              isAnimationActive={false} />
          </BarChart>
        </ChartContainer>
      </figure>
      {anyGap && <p className="text-muted-foreground text-xs" data-testid={`${testId}-gap-note`}>
        {t('analytics.trend.gapNote')}</p>}
      <DataDisclosure label={t('analytics.trend.table')} testId={`${testId}-table`}>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead scope="col">{t('analytics.trend.time')}</TableHead>
              <TableHead scope="col" className="text-right">{t('analytics.trend.views')}</TableHead>
              <TableHead scope="col" className="text-right">{t('analytics.trend.starts')}</TableHead>
              <TableHead scope="col" className="text-right">{t('analytics.trend.completions')}</TableHead>
              <TableHead scope="col" className="text-right">{t('analytics.trend.passes')}</TableHead>
              <TableHead scope="col" className="text-right">{t('analytics.trend.winners')}</TableHead>
              <TableHead scope="col" className="text-right">{t('analytics.trend.spent')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {points.map((p, i) => (
              <TableRow key={p.bucketStart} data-testid={`${testId}-row`}>
                <TableHead scope="row" className="font-normal">
                  {data[i].label}
                  {p.gap && <span className="text-muted-foreground"> ({t('analytics.trend.gap')})</span>}
                </TableHead>
                <TableCell className="text-right">{p.views.toLocaleString(locale)}</TableCell>
                <TableCell className="text-right">{p.starts.toLocaleString(locale)}</TableCell>
                <TableCell className="text-right" data-testid={`${testId}-row-completions`}>
                  {p.completions.toLocaleString(locale)}
                </TableCell>
                <TableCell className="text-right">{p.passes.toLocaleString(locale)}</TableCell>
                <TableCell className="text-right">{p.winners.toLocaleString(locale)}</TableCell>
                <TableCell className="text-right">{formatKudos(p.spent)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </DataDisclosure>
    </div>
  )
}
