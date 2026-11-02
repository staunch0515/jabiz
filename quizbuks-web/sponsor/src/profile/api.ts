import { useQuery } from '@tanstack/react-query'
import { readMySponsor } from '../qb/api'

/** The query of the sponsor's onboarding, which the routes, the dashboard, the preview and the hub read. */
export const SPONSOR_QUERY = ['qb', 'sponsor'] as const

/** The sponsor's onboarding, or null before applying. */
export function useMySponsor() {
  return useQuery({ queryKey: SPONSOR_QUERY, queryFn: readMySponsor })
}
