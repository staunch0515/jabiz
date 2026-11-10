import { useShellState } from '../shell/context'
import { FEE_ACCOUNT_PATH, WALLET_PATH } from '../shell/paths'

/**
 * Where a top-up's page leads back to (F-F3): the Kudos wallet's tab, or the platform fee account's for a Sponsor
 * without Kudos (who tops up to cash there and finds the top-ups listed there).
 */
export function useTopUpsHome(): string {
  return useShellState().kudos ? WALLET_PATH : FEE_ACCOUNT_PATH
}
