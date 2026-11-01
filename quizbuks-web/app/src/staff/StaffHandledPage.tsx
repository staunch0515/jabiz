import { formatDateTime } from '@jabiz/client'
import { Button, Card, CardContent, PageState, SegmentedControl } from '@jabiz/ui'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { QrCode } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import {
  HANDLED_PERIODS,
  HANDLED_QUERY,
  readHandled,
  readHandledTotals,
  readStaffOf,
  STAFF_PATH,
  STAFF_QUERY,
  type HandledPeriod,
} from './api'
import './messages'

const PAGE = 20

/** One figure: how many prizes in a period and their value. */
function Figure({ label, count, value, testId }: { label: string; count: number; value: unknown; testId: string }) {
  const { t } = useTranslation(APP)
  return (
    <Card className="py-3" data-testid={testId}>
      <CardContent className="flex flex-col gap-1 px-3">
        <span className="text-muted-foreground text-xs">{label}</span>
        <span className="text-xl font-semibold tabular-nums">{t('staff.handled.count', { count })}</span>
        <span className="text-muted-foreground text-xs tabular-nums">{formatKudos(value)}</span>
      </CardContent>
    </Card>
  )
}

/**
 * What a staff member handed over at their sponsor's counter (Q74): today's, the last 7 days' and this month's totals,
 * and the prizes of a period, newest first, by the deployment's days. Only their own, and only while on the staff;
 * the code to scan is a tap away.
 */
export function StaffHandledPage() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const [period, setPeriod] = useState<HandledPeriod>('TODAY')
  const [shown, setShown] = useState(PAGE)
  const place = useQuery({ queryKey: STAFF_QUERY, queryFn: readStaffOf })
  const totals = useQuery({ queryKey: [...HANDLED_QUERY, 'totals'], queryFn: readHandledTotals,
    enabled: !!place.data })
  const list = useQuery({ queryKey: [...HANDLED_QUERY, 'list', period, shown],
    queryFn: () => readHandled(period, 0, shown), enabled: !!place.data, placeholderData: keepPreviousData })
  if (place.isPending) return <PageState kind="loading" />
  if (place.isError) {
    return <PageState kind="error" description={problemText(place.error)}
      action={<Button variant="outline" onClick={() => void place.refetch()}>{tQb('retry')}</Button>} />
  }
  if (!place.data) {
    return <PageState kind="empty" titleAs="h1" title={t('staff.notStaffTitle')} description={t('staff.notStaff')} />
  }
  const sponsor = place.data.sponsorCompany ?? t('staff.theSponsor')
  return (
    <section className="flex flex-col gap-4" data-testid="staff-handled">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold">{t('staff.handled.title')}</h1>
        <p className="text-muted-foreground text-sm">{t('staff.handled.intro', { sponsor })}</p>
      </div>
      <Button asChild className="w-fit">
        <Link to={STAFF_PATH} data-testid="staff-handled-code"><QrCode aria-hidden />{t('staff.handled.showCode')}</Link>
      </Button>
      {totals.data && (
        <div className="flex flex-col gap-1">
          <div className="grid grid-cols-3 gap-2" data-testid="staff-handled-totals">
            <Figure label={t('staff.handled.periods.TODAY')} count={totals.data.todayCount}
              value={totals.data.todayValue} testId="staff-handled-today" />
            <Figure label={t('staff.handled.periods.WEEK')} count={totals.data.weekCount}
              value={totals.data.weekValue} testId="staff-handled-week" />
            <Figure label={t('staff.handled.periods.MONTH')} count={totals.data.monthCount}
              value={totals.data.monthValue} testId="staff-handled-month" />
          </div>
          <p className="text-muted-foreground text-xs">{t('staff.handled.zone', { zone: totals.data.zone })}</p>
        </div>
      )}
      <SegmentedControl<HandledPeriod> value={period} aria-label={t('staff.handled.period')}
        data-testid="staff-handled-period" onChange={(next) => {
          setPeriod(next)
          setShown(PAGE)
        }} options={HANDLED_PERIODS.map((p) => ({ value: p, label: t(`staff.handled.periods.${p}`) }))} />
      {list.isError ? (
        <p className="text-destructive text-sm" role="alert">{problemText(list.error)}</p>
      ) : list.data && list.data.items.length === 0 ? (
        <p className="text-muted-foreground text-sm" data-testid="staff-handled-none">{t('staff.handled.none')}</p>
      ) : (
        <ul className="flex flex-col gap-2" aria-label={t('staff.handled.listLabel')}>
          {(list.data?.items ?? []).map((row) => (
            <li key={row.redemptionId} className="flex flex-col gap-1 rounded-lg border p-3 text-sm"
              data-testid="staff-handled-row">
              <span className="flex flex-wrap items-baseline justify-between gap-2">
                <span className="font-medium">{row.description}</span>
                <span className="tabular-nums">{formatKudos(row.amount)}</span>
              </span>
              <span className="text-muted-foreground text-xs">
                {[formatDateTime(row.redeemedAt), row.publicationName, row.takerName, row.code]
                  .filter(Boolean).join(' · ')}
              </span>
            </li>
          ))}
        </ul>
      )}
      {list.data && list.data.items.length < list.data.total && (
        <Button variant="outline" className="w-fit" disabled={list.isFetching} data-testid="staff-handled-more"
          onClick={() => setShown((n) => n + PAGE)}>
          {t('staff.handled.more', { count: list.data.total - list.data.items.length })}
        </Button>
      )}
    </section>
  )
}
