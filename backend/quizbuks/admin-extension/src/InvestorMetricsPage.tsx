import { useQuery } from '@tanstack/react-query'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import {
  EXTENSION_NAMESPACE,
  Input,
  Label,
  PageHeader,
  PageState,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  useAuth,
} from '@jabiz/admin'
import { monthRange, previousMonth, readInvestorMetrics, readInvestorSponsors, type InvestorMonth,
  type Period } from './finance-api'
import { LoadFailed, Section } from './components'
import { money } from './hooks'
import { PERMISSION, TEMPLATE } from './ids'
import { Issued, ReportActions } from './ReportsPage'

const right = 'text-right tabular-nums'

/** The count columns of the months table, in order; the rates are derived on the page. */
const COUNTS = ['newSponsors', 'activeSponsors', 'retainedSponsors', 'publicationsWentOn', 'newTakers', 'activeTakers',
  'attempts', 'completions', 'passes', 'prizesWon', 'prizesClaimed', 'prizesRedeemed', 'invitationsSent',
  'invitationsAnswered', 'topUps'] as const
const MONEY = ['topUpAmount', 'rewardsPaid', 'commission'] as const
/** Counts of distinct people in a month: a merchant active in three months is one merchant, so they have no total. */
const STOCK: ReadonlySet<string> = new Set(['activeSponsors', 'retainedSponsors', 'activeTakers'])

/** A share as a percentage with one decimal; empty when there is nothing to divide by. */
function rate(part: number, whole: number): string {
  return whole > 0 ? `${((part / whole) * 100).toFixed(1)}%` : ''
}

/** The period's sum of each count column, and of the money columns as decimal strings. */
function totals(rows: InvestorMonth[]): InvestorMonth {
  const sum = (key: (typeof COUNTS)[number]) => rows.reduce((acc, row) => acc + row[key], 0)
  const sumMoney = (key: (typeof MONEY)[number]) => rows.reduce((acc, row) => acc + Number(row[key]), 0).toString()
  return {
    month: '',
    ...Object.fromEntries(COUNTS.map((key) => [key, sum(key)])) as Record<(typeof COUNTS)[number], number>,
    ...Object.fromEntries(MONEY.map((key) => [key, sumMoney(key)])) as Record<(typeof MONEY)[number], string>,
  }
}

function Months({ period }: { period: Period }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const rows = useQuery({ queryKey: ['qb-ext', 'finance', 'investor', period.from, period.to],
    queryFn: () => readInvestorMetrics(period) })
  let body
  if (rows.isError) body = <LoadFailed error={rows.error} onRetry={() => void rows.refetch()} />
  else if (rows.isPending) body = <PageState kind="loading" />
  else {
    const all = [...rows.data, totals(rows.data)]
    body = (
      <div className="overflow-x-auto">
        <Table aria-label={t('finance.investor.monthsTable')}>
          <TableHeader>
            <TableRow>
              <TableHead>{t('finance.investor.month')}</TableHead>
              {COUNTS.slice(0, 9).map((key) => <TableHead key={key} className="text-right">{t(`finance.investor.${key}`)}</TableHead>)}
              <TableHead className="text-right">{t('finance.investor.passRate')}</TableHead>
              {COUNTS.slice(9, 12).map((key) => <TableHead key={key} className="text-right">{t(`finance.investor.${key}`)}</TableHead>)}
              <TableHead className="text-right">{t('finance.investor.redemptionRate')}</TableHead>
              {COUNTS.slice(12).map((key) => <TableHead key={key} className="text-right">{t(`finance.investor.${key}`)}</TableHead>)}
              {MONEY.map((key) => <TableHead key={key} className="text-right">{t(`finance.investor.${key}`)}</TableHead>)}
            </TableRow>
          </TableHeader>
          <TableBody>
            {all.map((row, index) => {
              const total = index === all.length - 1
              const count = (key: (typeof COUNTS)[number]) => (total && STOCK.has(key) ? '' : row[key].toLocaleString())
              return (
                <TableRow key={total ? 'total' : row.month} data-testid={total ? 'investor-total' : 'investor-month'}
                  data-month={row.month} className={total ? 'font-semibold' : undefined}>
                  <TableCell>{total ? t('finance.investor.total') : row.month}</TableCell>
                  {COUNTS.slice(0, 9).map((key) => <TableCell key={key} className={right}>{count(key)}</TableCell>)}
                  <TableCell className={right}>{rate(row.passes, row.completions)}</TableCell>
                  {COUNTS.slice(9, 12).map((key) => <TableCell key={key} className={right}>{count(key)}</TableCell>)}
                  <TableCell className={right}>{rate(row.prizesRedeemed, row.prizesWon)}</TableCell>
                  {COUNTS.slice(12).map((key) => <TableCell key={key} className={right}>{count(key)}</TableCell>)}
                  {MONEY.map((key) => <TableCell key={key} className={right}>{money(row[key])}</TableCell>)}
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      </div>
    )
  }
  return (
    <Section title={t('finance.investor.months')} testId="investor-months"
      actions={<ReportActions id={TEMPLATE.investorMetrics} period={period} />}>
      <p className="text-muted-foreground text-sm">{t('finance.investor.monthsHint')}</p>
      {body}
      {can(PERMISSION.reportArchiveRead) && <Issued id={TEMPLATE.investorMetrics} period={period} />}
    </Section>
  )
}

function Sponsors({ period }: { period: Period }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const rows = useQuery({ queryKey: ['qb-ext', 'finance', 'investor-sponsors', period.from, period.to],
    queryFn: () => readInvestorSponsors(period) })
  let body
  if (rows.isError) body = <LoadFailed error={rows.error} onRetry={() => void rows.refetch()} />
  else if (rows.isPending) body = <PageState kind="loading" />
  else if (rows.data.length === 0) body = <p className="text-muted-foreground text-sm">{t('finance.investor.noSponsors')}</p>
  else {
    body = (
      <div className="overflow-x-auto">
        <Table aria-label={t('finance.investor.sponsorsTable')}>
          <TableHeader>
            <TableRow>
              <TableHead>{t('finance.investor.company')}</TableHead>
              {(['takers', 'attempts', 'completions', 'passes'] as const).map((key) => (
                <TableHead key={key} className="text-right">{t(`finance.investor.${key}`)}</TableHead>
              ))}
              <TableHead className="text-right">{t('finance.investor.passRate')}</TableHead>
              {(['prizesWon', 'prizesRedeemed'] as const).map((key) => (
                <TableHead key={key} className="text-right">{t(`finance.investor.${key}`)}</TableHead>
              ))}
              <TableHead className="text-right">{t('finance.investor.redemptionRate')}</TableHead>
              {(['invitationsSent', 'invitationsAnswered'] as const).map((key) => (
                <TableHead key={key} className="text-right">{t(`finance.investor.${key}`)}</TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.data.map((row) => (
              <TableRow key={row.publisherId} data-testid="investor-sponsor" data-sponsor={row.publisherId}>
                <TableCell>{row.company}</TableCell>
                <TableCell className={right}>{row.takers.toLocaleString()}</TableCell>
                <TableCell className={right}>{row.attempts.toLocaleString()}</TableCell>
                <TableCell className={right}>{row.completions.toLocaleString()}</TableCell>
                <TableCell className={right}>{row.passes.toLocaleString()}</TableCell>
                <TableCell className={right}>{rate(row.passes, row.completions)}</TableCell>
                <TableCell className={right}>{row.prizesWon.toLocaleString()}</TableCell>
                <TableCell className={right}>{row.prizesRedeemed.toLocaleString()}</TableCell>
                <TableCell className={right}>{rate(row.prizesRedeemed, row.prizesWon)}</TableCell>
                <TableCell className={right}>{row.invitationsSent.toLocaleString()}</TableCell>
                <TableCell className={right}>{row.invitationsAnswered.toLocaleString()}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    )
  }
  return (
    <Section title={t('finance.investor.sponsors')} testId="investor-sponsors"
      actions={<ReportActions id={TEMPLATE.investorSponsors} period={period} />}>
      <p className="text-muted-foreground text-sm">{t('finance.investor.sponsorsHint')}</p>
      {body}
    </Section>
  )
}

/**
 * The investors' figures (Q27): one row per UTC month of the period in the address (`?from=YYYY-MM&to=YYYY-MM`, the
 * last six months when there is none), with a total, and the merchants behind them; exported, issued and archived
 * through the platform as the monthly reports are.
 */
export default function InvestorMetricsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const fromId = useId()
  const toId = useId()
  const [search, setSearch] = useSearchParams()
  const to = search.get('to') ?? previousMonth()
  const from = search.get('from') ?? monthsBefore(to, 5)
  // A month input reports '' while being typed or when cleared: the period shown stays until a month is given.
  const set = (change: Record<string, string>) => {
    if (Object.values(change).some((value) => value === '')) return
    setSearch({ from, to, ...change }, { replace: true })
  }
  const period = monthRange(from, to)
  return (
    <div className="flex flex-col gap-4" data-testid="investor-metrics">
      <PageHeader title={t('finance.investor.title')} description={t('finance.investor.description')} className="pb-0" />
      <div className="flex flex-wrap items-end gap-4">
        <div className="flex flex-col gap-1">
          <Label htmlFor={fromId}>{t('finance.investor.from')}</Label>
          <Input id={fromId} type="month" className="w-48" value={from} data-testid="investor-from"
            onChange={(event) => set({ from: event.target.value })} />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor={toId}>{t('finance.investor.to')}</Label>
          <Input id={toId} type="month" className="w-48" value={to} data-testid="investor-to"
            onChange={(event) => set({ to: event.target.value })} />
        </div>
        {period && (
          <p className="text-muted-foreground pb-2 text-sm" data-testid="investor-period">
            {t('finance.investor.periodValue', { from: period.from.slice(0, 10), to: period.to.slice(0, 10) })}
          </p>
        )}
      </div>
      {period
        ? (
          <>
            <Months period={period} />
            <Sponsors period={period} />
          </>
        )
        : <PageState kind="warning" description={t('finance.investor.badPeriod')} />}
    </div>
  )
}

/** The month `n` months before a `YYYY-MM` month (the month itself when it is not one). */
function monthsBefore(month: string, n: number): string {
  const match = /^(\d{4})-(\d{2})$/.exec(month)
  if (!match) return month
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1 - n, 1))
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}`
}
