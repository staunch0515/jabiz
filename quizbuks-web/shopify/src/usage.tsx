import { Alert, AlertDescription, Button } from '@jabiz/ui'
import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getPlan, type PlanState, type Shop } from './api'
import { SHOPIFY } from './messages'

/** The parameter Shopify adds when it sends the merchant back from its plan page. */
const CHARGE = 'charge_id'

/** Whether the page was opened back from Shopify's plan page; the parameter is taken off, so it counts once. */
function backFromPricing(): boolean {
  const url = new URL(window.location.href)
  if (!url.searchParams.has(CHARGE)) return false
  url.searchParams.delete(CHARGE)
  window.history.replaceState(window.history.state, '', url)
  return true
}

export function planKey(userId: string | null) {
  return ['qbs.plan', userId]
}

/** The store's connection as the plan's reading found it: a refused token shows on the overview too. */
export function keepConnection(queryClient: QueryClient, userId: string | null, state: PlanState) {
  queryClient.setQueryData<Shop>(['qbs.shop', userId], (shop) =>
    shop && shop.connection !== state.connection ? { ...shop, connection: state.connection } : shop)
  return state
}

/**
 * The store's plan and its use (docs/quizbuks/shopify/S3b-ui.md section 1): read once for the plan tab and the
 * overview's notice, per signed-in staff member (a tab may switch to another store's). Back from Shopify's plan page,
 * the first read asks Shopify at once.
 */
export function usePlan(userId: string | null) {
  const queryClient = useQueryClient()
  const [fromPricing] = useState(backFromPricing)
  return useQuery({
    queryKey: planKey(userId),
    queryFn: async () => keepConnection(queryClient, userId, await getPlan(fromPricing)),
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  })
}

/** The share of the period's attempts used, in whole percent (100 at or over the limit). */
export function percentUsed(plan: PlanState): number {
  if (plan.limit <= 0 || plan.used >= plan.limit) return 100
  return Math.floor((plan.used * 100) / plan.limit)
}

/** Opens Shopify's plan page at the top of the admin: it cannot be shown in the app's frame. */
export function openPricing(plan: PlanState) {
  window.open(plan.pricingUrl, '_top')
}

/**
 * Dates in the page's language. The periods run in UTC from one instant up to (not including) another, so their days
 * are UTC days and a period's last day is the one before its end.
 */
export function useDates() {
  const { i18n } = useTranslation(SHOPIFY)
  const day = (value: string | number) => new Date(value).toLocaleDateString(i18n.language, { timeZone: 'UTC' })
  return {
    day,
    lastDay: (end: string) => day(new Date(end).getTime() - 1),
    time: (value: string) => new Date(value).toLocaleString(i18n.language),
  }
}

/** The overview's notice from 80 % of the period's attempts; nothing below, or while the plan is not read. */
export function UsageNotice({ userId, onShowPlan }: { userId: string | null; onShowPlan: () => void }) {
  const { t } = useTranslation(SHOPIFY)
  const dates = useDates()
  const plan = usePlan(userId)
  if (!plan.data) return null
  const state = plan.data
  const percent = percentUsed(state)
  if (percent < 80) return null
  if (percent >= 100) {
    return (
      <Alert variant="destructive">
        <AlertDescription className="flex flex-col gap-2">
          <span>{t('plan.full', { date: dates.day(state.periodEnd) })}</span>
          <Button className="self-start" onClick={() => openPricing(state)}>{t('plan.choose')}</Button>
        </AlertDescription>
      </Alert>
    )
  }
  return (
    <Alert>
      <AlertDescription className="flex flex-col gap-2">
        <span>{t('plan.nearly', { percent })}</span>
        <Button variant="outline" className="self-start" onClick={onShowPlan}>{t('plan.show')}</Button>
      </AlertDescription>
    </Alert>
  )
}
