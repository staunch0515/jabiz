import { useQuery } from '@tanstack/react-query'
import { minuteNow } from '../qb/legal'
import {
  ANALYTICS_QUERY,
  readDashboard,
  readTop,
  readTrend,
  REFRESH_MS,
  trendParams,
  type TrendRange,
} from './api'

/**
 * Reading the analytics again every minute and when the page comes back to the front (some counts are live, the
 * rest changes every ten minutes); "now" is the server's, to the minute, taken anew on each read.
 */
const live = { refetchInterval: REFRESH_MS, refetchOnWindowFocus: true } as const

/** The dashboard's row: the key figures, and the deployment's zone and the job's last run for every page. */
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
 * The trend of one publication, or of all the sponsor's (`publicationId` null), for a period ending now, with the
 * parameters it was read with (the export asks for the same period).
 */
export function useTrend(range: TrendRange, publicationId: string | null, enabled = true) {
  return useQuery({
    queryKey: [...ANALYTICS_QUERY, 'trend', publicationId, range],
    queryFn: async () => {
      const params = trendParams(range, minuteNow(), publicationId)
      return { params, points: await readTrend(params) }
    },
    enabled,
    ...live,
  })
}
