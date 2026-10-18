import { Badge, Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@jabiz/ui'
import { TrendingDown } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { funnelOf, percent, type Overview } from './api'
import { DataDisclosure } from './charts'
import './messages'

/**
 * The funnel (M-41): views, starts, completions and passes as horizontal bars against the first stage, each with its
 * count and its rate against the stage before. The largest drop (the template's `largestDrop`) is marked three ways,
 * never by colour alone: a badge with an icon on its stage, the stage's bar drawn hatched, and a sentence saying from
 * where to where and how many do not go on. The numbers are also in a table below.
 */
export function Funnel({ overview }: { overview: Overview }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const locale = i18n.language
  const stages = funnelOf(overview)
  const top = Math.max(stages[0].count, 1)
  const dropAt = stages.findIndex((s) => s.largestDrop)
  const drop = dropAt > 0 ? stages[dropAt] : null
  const name = (key: string) => t(`analytics.funnel.${key}`)
  return (
    <div className="flex flex-col gap-3" data-testid="funnel">
      <ol className="flex flex-col gap-2" aria-label={t('analytics.funnel.label')}>
        {stages.map((stage) => {
          const width = `${Math.max(0, Math.min(100, (stage.count / top) * 100))}%`
          return (
            <li key={stage.key} className="grid grid-cols-[7rem_1fr] items-center gap-3 sm:grid-cols-[9rem_1fr]"
              data-testid={`funnel-${stage.key}`} data-largest-drop={stage.largestDrop || undefined}>
              <span className="flex flex-col text-sm">
                <span className="font-medium">{name(stage.key)}</span>
                <span className="text-muted-foreground text-xs">
                  {stage.count.toLocaleString(locale)}
                  {stage.rate !== null && <> · {percent(stage.rate, locale)}</>}
                </span>
              </span>
              <span className="flex flex-wrap items-center gap-2">
                <span className="bg-muted relative h-6 min-w-24 flex-1 overflow-hidden rounded-sm" aria-hidden="true">
                  <span
                    className={stage.largestDrop
                      ? 'border-chart-1 absolute inset-y-0 left-0 rounded-sm border-2 bg-[repeating-linear-gradient(135deg,var(--chart-1)_0_4px,transparent_4px_8px)]'
                      : 'bg-chart-1 absolute inset-y-0 left-0 rounded-sm'}
                    style={{ width }} />
                </span>
                {stage.largestDrop && (
                  <Badge variant="outline" data-testid="funnel-largest-drop">
                    <TrendingDown aria-hidden="true" />{t('analytics.funnel.largestDrop')}
                  </Badge>
                )}
              </span>
            </li>
          )
        })}
      </ol>
      <p className="text-sm" data-testid="funnel-summary">
        {drop
          ? t('analytics.funnel.largestDropText', {
            from: name(stages[dropAt - 1].key),
            to: name(drop.key),
            lost: percent(drop.rate === null ? null : 1 - drop.rate, locale),
          })
          : t('analytics.funnel.noDrop')}
      </p>
      {overview.viewsBelowStarts && (
        <p className="text-muted-foreground text-xs" data-testid="funnel-views-below">
          {t('analytics.funnel.viewsBelowStarts')}
        </p>
      )}
      <DataDisclosure label={t('analytics.trend.table')} testId="funnel-table">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead scope="col">{t('analytics.funnel.stage')}</TableHead>
              <TableHead scope="col" className="text-right">{t('analytics.funnel.count')}</TableHead>
              <TableHead scope="col" className="text-right">{t('analytics.funnel.rate')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {stages.map((stage) => (
              <TableRow key={stage.key}>
                <TableHead scope="row" className="font-normal">
                  {name(stage.key)}
                  {stage.largestDrop && <> ({t('analytics.funnel.largestDrop')})</>}
                </TableHead>
                <TableCell className="text-right">{stage.count.toLocaleString(locale)}</TableCell>
                <TableCell className="text-right">{percent(stage.rate, locale)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </DataDisclosure>
    </div>
  )
}
