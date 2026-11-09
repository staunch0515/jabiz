import { runQuery } from '@jabiz/client'
import { useQuery } from '@tanstack/react-query'
import { Ban } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Metric } from '../analytics/Metric'
import { SPONSOR } from '../messages'
import './messages'

/** The template of the count (`qb.sponsor.blockers`). */
export const BLOCKERS_TEMPLATE = 'qb.sponsor.blockers'

/** The count as the server gives it: rounded down to tens, none below the minimum group. */
export interface Blockers {
  blockers: number | null
  minGroup: number
}

export async function readBlockers(): Promise<Blockers> {
  const page = await runQuery<Record<string, unknown>>(BLOCKERS_TEMPLATE, {})
  const row = page.items[0] ?? {}
  return {
    blockers: row.blockers == null ? null : Number(row.blockers),
    minGroup: row.minGroup == null ? 20 : Number(row.minGroup),
  }
}

/**
 * F-S8: how many takers block the Sponsor (it, its quizzes, it as a video provider), on the dashboard: only the
 * server's rounded count, or "fewer than N" below the minimum group. Never who or what.
 */
export function BlockersMetric() {
  const { t, i18n } = useTranslation(SPONSOR)
  const blockers = useQuery({ queryKey: ['qb', 'sponsor', 'blockers'], queryFn: readBlockers })
  if (!blockers.isSuccess) return null
  const { blockers: count, minGroup } = blockers.data
  const n = (value: number) => value.toLocaleString(i18n.language)
  return (
    <Metric testId="metric-blockers" label={t('blockers.label')} icon={Ban}
      value={count == null ? t('blockers.fewer', { n: n(minGroup) }) : t('blockers.value', { n: n(count) })}
      hint={t('blockers.hint')} />
  )
}
