import { useEnvironment } from '@jabiz/client'
import { useQuery } from '@tanstack/react-query'
import { readRewardSettings, REWARD_SETTINGS_QUERY } from './api'

/**
 * Whether the system proposes the first launch's values (F-F10a): production and staging. The Kudos reward switch and
 * the default methods not being published then count as off and none, so the console must not offer what the server
 * would refuse. Only what the console offers: the server decides from its own environment when it saves and submits.
 * Unknown (not answered yet) is not first launch, the answer arrives and asks again.
 */
export function isFirstLaunch(environment: { name: string; production: boolean } | undefined): boolean {
  return !!environment && (environment.production || environment.name.trim().toLowerCase() === 'staging')
}

/** The reward methods the sponsor may publish with now (`qb.sponsor.reward-methods`), shared by the wizard and the frame. */
export function useRewardSettings() {
  const firstLaunch = isFirstLaunch(useEnvironment())
  return useQuery({
    queryKey: [...REWARD_SETTINGS_QUERY, firstLaunch],
    queryFn: () => readRewardSettings(firstLaunch),
    staleTime: 60_000,
  })
}
