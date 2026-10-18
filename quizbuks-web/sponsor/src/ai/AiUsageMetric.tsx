import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Metric } from '../analytics/Metric'
import { zoneTime } from '../analytics/api'
import { SPONSOR } from '../messages'
import { readAiUsage } from './api'
import { AI_USAGE_QUERY } from './AiQuestionsPanel'
import './messages'

/**
 * The dashboard's AI usage (M-10, Q8a): this month's tokens (in and out), requests, and what is left of the month's
 * allowance and until when (the deployment's month, its time shown in that zone). Only what `qb.sponsor.ai-usage`
 * gives: the page adds nothing up beyond in + out.
 */
export function AiUsageMetric({ zone }: { zone: string }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const locale = i18n.language
  const usage = useQuery({ queryKey: AI_USAGE_QUERY, queryFn: () => readAiUsage() })
  if (!usage.isSuccess) return null
  const u = usage.data
  const n = (value: number) => value.toLocaleString(locale)
  const hint = !u.enabled ? t('ai.usage.off')
    : u.remainingTokens != null && u.monthEnds
      ? t('ai.usage.remaining', { tokens: n(u.remainingTokens), time: zoneTime(u.monthEnds, zone, locale) })
      : undefined
  return (
    <Metric testId="metric-ai" label={t('ai.usage.label')}
      value={<span className="flex flex-col gap-1">
        <span>{t('ai.usage.tokens', { tokens: n(u.inputTokens + u.outputTokens) })}</span>
        <span className="text-muted-foreground text-sm font-normal" data-testid="metric-ai-detail">
          {t('ai.usage.detail', { input: n(u.inputTokens), output: n(u.outputTokens), jobs: n(u.jobs) })}
        </span>
      </span>}
      hint={hint} />
  )
}
