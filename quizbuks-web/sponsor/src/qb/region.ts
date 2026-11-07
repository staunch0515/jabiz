import { clientRegion } from '@jabiz/client'
import { useQuery } from '@tanstack/react-query'

/**
 * F-S11: whether this browser's address is in mainland China, as the platform says (`GET /api/client/region`,
 * decision D74; never cached by the server): the App passes it to the templates of the home, a publication's page and
 * a sponsor's page, which then leave out (or explain) what has a video mainland China cannot play. Only for showing:
 * the start checks the address itself. An answer the platform cannot give (no address database, a failure) is "not
 * mainland China": the page shows the publication and the start explains if it is refused.
 */
export const REGION_QUERY = ['qb', 'client-region'] as const

export function useMainlandChina(): { mainlandChina: boolean; pending: boolean } {
  const region = useQuery({ queryKey: REGION_QUERY, queryFn: clientRegion, staleTime: 5 * 60_000, retry: false })
  return { mainlandChina: region.data?.mainlandChina === true, pending: region.isPending }
}
