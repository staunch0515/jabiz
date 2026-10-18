import { useQuery } from '@tanstack/react-query'
import { minuteNow } from '../qb/legal'
import {
  ANALYTICS_QUERY,
  readDashboard,
  readTop,
  readTrend,
  readTrendTotals,
  REFRESH_MS,
  trendParams,
  type TrendRange,
} from './api'

/**
 * Reading the analytics again every minute and when the page comes back to the front (some counts are live, the
 * rest changes every ten minutes); "now" is the server's, to the minute, taken anew on each read.
 */
const live = { refetchInterval: REFRESH_MS, refetchOnWindowFocus: true } as const

/** The dashboard's row: the key figures, the deployment's zone and the job's last run. */
export function useDashboard(enabled = true) {
  return useQuery({
    queryKey: [...ANALYTICS_QUERY, 'dashboard'],
    queryFn: () => readDashboard(minuteNow()),
    enabled,
    ...live,
  })
}

/** The five publications with the most completions in the last seven days. */
export function useTop(enabled = true) {
  return useQuery({ queryKey: [...ANALYTICS_QUERY, 'top'], queryFn: () => readTop(minuteNow()), enabled, ...live })
}

/**
 * The trend of one publication, or of all the sponsor's (`publicationId` null), for a period ending now: its
 * buckets and their totals (both the server's, for the same parameters), the zone they are of, and the parameters
 * (the export asks for the same period).
 */
export function useTrend(range: TrendRange, publicationId: string | null, enabled = true) {
  return useQuery({
    queryKey: [...ANALYTICS_QUERY, 'trend', publicationId, range],
    queryFn: async () => {
      const params = trendParams(range, minuteNow(), publicationId)
      const [points, totals] = await Promise.all([readTrend(params), readTrendTotals(params)])
      return { params, points, totals, zone: totals.zone }
    },
    enabled,
    ...live,
  })
}
