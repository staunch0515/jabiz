import { Alert, AlertDescription, Badge, Button, Card, CardContent, CardDescription, CardHeader, CardTitle } from '@jabiz/ui'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { getPlan } from './api'
import { reasonOf } from './errors'
import { SHOPIFY } from './messages'
import { keepConnection, openPricing, percentUsed, planKey, useDates, usePlan } from './usage'

/** The plan tab (S3b); a chunk of its own, loaded when the page is first shown (S4b). */
export default function PlanPage({ userId, onShowOverview }: { userId: string | null; onShowOverview: () => void }) {
  const { t } = useTranslation(SHOPIFY)
  const dates = useDates()
  const queryClient = useQueryClient()
  const plan = usePlan(userId)
  const refresh = useMutation({
    mutationFn: () => getPlan(true),
    onSuccess: (state) => queryClient.setQueryData(planKey(userId), keepConnection(queryClient, userId, state)),
  })
  if (plan.isPending) return <p role="status">{t('plan.loading')}</p>
  if (plan.error) {
    return (
      <Alert variant="destructive">
        <AlertDescription>{t('failed', { reason: reasonOf(plan.error) })}</AlertDescription>
      </Alert>
    )
  }
  const state = plan.data
  const percent = percentUsed(state)
  const noLimit = state.limit <= 0
  return (
    <div className="flex flex-col gap-4">
      {state.unknownPlan && (
        <Alert variant="destructive">
          <AlertDescription>{t('plan.unknown', { name: state.shopifyPlan })}</AlertDescription>
        </Alert>
      )}
      {state.connection === 'RECONNECT' && (
        <Alert>
          <AlertDescription className="flex flex-col gap-2">
            <span>{t('plan.reconnect')}</span>
            <Button variant="outline" className="self-start" onClick={onShowOverview}>{t('plan.toOverview')}</Button>
          </AlertDescription>
        </Alert>
      )}
      <Card>
        <CardHeader>
          <CardTitle className="flex flex-wrap items-center gap-2">
            {t('plan.current', { plan: t(`plan.names.${state.plan}`) })}
            {state.test && <Badge variant="outline">{t('plan.test')}</Badge>}
          </CardTitle>
          <CardDescription className="flex flex-col gap-1">
            {state.trialEndsAt && new Date(state.trialEndsAt) > new Date() && (
              <span>{t('plan.trial', { date: dates.lastDay(state.trialEndsAt) })}</span>
            )}
            {state.nextPlan && state.planUntil && (
              <span>{t('plan.next', { date: dates.day(state.planUntil), plan: t(`plan.names.${state.nextPlan}`) })}</span>
            )}
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <p>{t('plan.period', { start: dates.day(state.periodStart), end: dates.lastDay(state.periodEnd) })}</p>
          {/* A native progress bar (S3b-ui.md Q-S3b-1: @jabiz/ui has none), coloured with the theme's tokens. */}
          <label className="flex flex-col gap-1">
            <span>{t('plan.used', { used: state.used, limit: state.limit, percent })}</span>
            <progress value={noLimit ? 1 : Math.min(state.used, state.limit)} max={noLimit ? 1 : state.limit}
              className="h-2 w-full overflow-hidden rounded-full [&::-moz-progress-bar]:bg-primary [&::-webkit-progress-bar]:bg-muted [&::-webkit-progress-value]:bg-primary" />
          </label>
          <p className="text-sm text-muted-foreground">{t('plan.explain')}</p>
          <div className="flex flex-wrap gap-2">
            <Button onClick={() => openPricing(state)}>{t('plan.choose')}</Button>
            <Button variant="outline" disabled={refresh.isPending} onClick={() => refresh.mutate()}>
              {t('plan.refresh')}
            </Button>
          </div>
          {refresh.error && (
            <Alert variant="destructive">
              <AlertDescription>{t('plan.refreshFailed', { reason: reasonOf(refresh.error) })}</AlertDescription>
            </Alert>
          )}
          {state.checkedAt && (
            <p className="text-sm text-muted-foreground">{t('plan.checked', { time: dates.time(state.checkedAt) })}</p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
