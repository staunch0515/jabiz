import { formatDateTime, i18n } from '@jabiz/client'
import { Button, PageState, SegmentedControl, Spinner } from '@jabiz/ui'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { ArrowLeft, Banknote, ChevronRight, Coins, Gift, Ticket, type LucideIcon } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { RewardLine } from '../pages/HistoryPage'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { readHistory, type RewardMethod } from '../qb/play'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import { WALLET_PATH } from './api'
import { kudosPath, PERIODS, readKudos, totalOf, type KudosRow, type KudosTotals, type Period } from './kudos'
import './messages'

/** The wallet's queries of the Kudos by method. */
export const KUDOS_QUERY = ['qb', 'wallet', 'kudos'] as const

const ICONS: Record<RewardMethod, LucideIcon> = {
  PLATFORM: Coins,
  SPONSOR_CASH: Banknote,
  OFFLINE_REDEEM: Gift,
  DIGITAL_PRIZE: Ticket,
}

/** The period chosen on the wallet, shared with a method's page while the app is open. */
let chosen: Period = 'all'

function usePeriod(): [Period, (period: Period) => void] {
  const [period, setPeriod] = useState<Period>(chosen)
  return [period, (next) => {
    chosen = next
    setPeriod(next)
  }]
}

function PeriodSwitch({ period, onChange }: { period: Period; onChange: (period: Period) => void }) {
  const { t } = useTranslation(APP)
  return (
    <SegmentedControl value={period} onChange={onChange} aria-label={t('kudos.period')} data-testid="kudos-period"
      options={PERIODS.map((value) => ({ value, label: t(`kudos.periods.${value}`) }))} />
  )
}

/** Earned, settled, lapsed (when any) and still owed, as a list of figures. */
function Figures({ totals, method, testId }: { totals: KudosTotals; method?: RewardMethod; testId: string }) {
  const { t } = useTranslation(APP)
  const figure = (key: string, label: string, value: number) => (
    <div className="flex flex-col" key={key}>
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd className="font-medium tabular-nums" data-testid={`${testId}-${key}`}>{formatKudos(value)}</dd>
    </div>
  )
  return (
    <dl className="grid grid-cols-3 gap-2">
      {figure('earned', t('kudos.earned'), totals.earned)}
      {figure('settled', method ? t(`kudos.settledBy.${method}`) : t('kudos.settled'), totals.settled)}
      {figure('balance', method ? t(`kudos.balanceBy.${method}`) : t('kudos.balance'), totals.balance)}
      {totals.lapsed > 0 && figure('lapsed', t('kudos.lapsed'), totals.lapsed)}
    </dl>
  )
}

/**
 * The taker's Kudos by reward method (Q58), at the top of the wallet: the total of all rewards, then one card per
 * method (Kudos to the wallet, cash, a prize at the shop, a digital prize), each opening its rewards. In all, this year
 * or this month; what is still owed is always now's.
 */
export function KudosOverview() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const [period, setPeriod] = usePeriod()
  const kudos = useQuery({ queryKey: [...KUDOS_QUERY, period], queryFn: () => readKudos(period) })
  return (
    <section aria-labelledby="kudos-title" className="flex flex-col gap-3" data-testid="kudos">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="kudos-title" className="text-lg font-semibold">{t('kudos.title')}</h2>
        <PeriodSwitch period={period} onChange={setPeriod} />
      </div>
      {kudos.isPending ? (
        <PageState kind="loading" />
      ) : kudos.isError ? (
        <PageState kind="error" description={problemText(kudos.error)}
          action={<Button variant="outline" onClick={() => void kudos.refetch()}>{tQb('retry')}</Button>} />
      ) : (
        <>
          <div className="bg-accent/40 flex flex-col gap-2 rounded-lg border p-4" data-testid="kudos-total">
            <p className="flex items-baseline justify-between gap-2">
              <span className="font-medium">{t('kudos.total')}</span>
              <span className="text-muted-foreground text-xs">
                {t('kudos.rewards', { count: totalOf(kudos.data).rewards })}
              </span>
            </p>
            <Figures totals={totalOf(kudos.data)} testId="kudos-total" />
          </div>
          <ul className="flex flex-col gap-2">
            {kudos.data.map((row) => <MethodCard key={row.method} row={row} />)}
          </ul>
          <p className="text-muted-foreground text-xs">{t('kudos.hint')}</p>
        </>
      )}
    </section>
  )
}

function MethodCard({ row }: { row: KudosRow }) {
  const { t } = useTranslation(APP)
  const Icon = ICONS[row.method]
  const name = t(`methods.${row.method}`)
  return (
    <li>
      <Link to={kudosPath(row.method)} data-testid={`kudos-${row.method}`} aria-label={t('kudos.open', { method: name })}
        className="hover:bg-accent flex items-center gap-3 rounded-lg border p-3">
        <Icon aria-hidden className="text-primary size-5 shrink-0" />
        <span className="flex min-w-0 flex-1 flex-col gap-1">
          <span className="text-sm font-medium">{name}</span>
          <Figures totals={row} method={row.method} testId={`kudos-${row.method}`} />
        </span>
        <ChevronRight aria-hidden className="text-muted-foreground size-4 shrink-0" />
      </Link>
    </li>
  )
}

const PAGE = 20

/** One method's figures and its rewards, newest first, each opening its result (where it is confirmed or shown). */
export function KudosMethodPage({ method }: { method: RewardMethod }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const [period, setPeriod] = usePeriod()
  const kudos = useQuery({ queryKey: [...KUDOS_QUERY, period], queryFn: () => readKudos(period) })
  const row = kudos.data?.find((item) => item.method === method)
  const rewards = useInfiniteQuery({
    queryKey: [...KUDOS_QUERY, 'rewards', method, i18n.language],
    queryFn: ({ pageParam }) => readHistory({ at: new Date().toISOString(), method, offset: pageParam, limit: PAGE }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.items.length < PAGE ? undefined : last.offset + PAGE),
  })
  const items = rewards.data?.pages.flatMap((page) => page.items) ?? []
  const Icon = ICONS[method]
  return (
    <section className="flex flex-col gap-4" aria-labelledby="kudos-method-title" data-testid="kudos-method"
      data-method={method}>
      <Link to={WALLET_PATH} data-testid="kudos-back"
        className="text-muted-foreground hover:text-foreground inline-flex w-fit items-center gap-1 text-sm">
        <ArrowLeft aria-hidden className="size-4" />
        {t('kudos.back')}
      </Link>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 id="kudos-method-title" className="flex items-center gap-2 text-xl font-semibold">
          <Icon aria-hidden className="text-primary size-5" />
          {t(`methods.${method}`)}
        </h1>
        <PeriodSwitch period={period} onChange={setPeriod} />
      </div>
      {kudos.isPending ? (
        <PageState kind="loading" />
      ) : kudos.isError || !row ? (
        <PageState kind="error" description={problemText(kudos.error)}
          action={<Button variant="outline" onClick={() => void kudos.refetch()}>{tQb('retry')}</Button>} />
      ) : (
        <div className="flex flex-col gap-2 rounded-lg border p-4" data-testid="kudos-method-figures">
          <Figures totals={row} method={method} testId="kudos-method" />
          {method === 'PLATFORM' && row.pendingCount > 0 && (
            <p className="text-warning text-sm" data-testid="kudos-pending">
              {t('kudos.pending', { amount: formatKudos(row.pendingAmount) })}
            </p>
          )}
        </div>
      )}
      <section aria-labelledby="kudos-list-title" className="flex flex-col gap-2">
        <h2 id="kudos-list-title" className="text-lg font-semibold">{t('kudos.list')}</h2>
        {method === 'PLATFORM' && <p className="text-muted-foreground text-sm">{t('kudos.platformNote')}</p>}
        {rewards.isPending ? (
          <PageState kind="loading" />
        ) : rewards.isError ? (
          <PageState kind="error" description={problemText(rewards.error)} />
        ) : items.length === 0 ? (
          <p className="text-muted-foreground text-sm" data-testid="kudos-empty">{t('kudos.empty')}</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {items.map((item) => (
              <li key={item.attemptId} className="flex flex-col gap-1 rounded-lg border p-3" data-testid="kudos-reward">
                <p className="font-medium">{item.name}</p>
                <p className="text-muted-foreground text-xs">{formatDateTime(item.submittedAt ?? item.startedAt)}</p>
                <p className="flex flex-wrap items-center gap-x-3 text-sm"><RewardLine row={item} /></p>
                <Link to={`/result/${item.attemptId}`} className="text-primary w-fit text-sm underline">
                  {t('kudos.seeResult')}<span className="sr-only">: {item.name}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
        {rewards.hasNextPage && (
          <Button variant="outline" disabled={rewards.isFetchingNextPage} onClick={() => void rewards.fetchNextPage()}>
            {rewards.isFetchingNextPage && <Spinner aria-hidden />}
            {t('wallet.ledger.more')}
          </Button>
        )}
      </section>
    </section>
  )
}
