import { ACCOUNT_PATH, isAccountPath } from '../pages/accountPaths'
import { mapOf, MAPS_PATH } from '../maps/paths'
import { REFERRALS_PATH } from '../referral/api'
import { kudosMethodOf } from '../wallet/kudos'
import { BANK_PATH, isWalletPath, PAYOUT_PATH, WALLET_PATH, walletPath } from '../wallet/api'

/** The page one level up from a page under "Me", and the key (APP) of its name. */
export interface Up {
  path: string
  name: string
}

const ME: Up = { path: ACCOUNT_PATH, name: 'frame.areas.me' }
const WALLET: Up = { path: WALLET_PATH, name: 'wallet.title' }

/**
 * Where the header's back button goes from a page reached from "Me" (its rows and numbers): the page above it in the
 * menu's order, whatever page came before (a link from a mail, a notification). Every page under "Me" has one, so the
 * way back is always the same button in the same place; "Me" itself and the areas' own pages (their tabs) have none.
 */
export function upOf(path: string): Up | null {
  if (path === ACCOUNT_PATH) return null
  if (isAccountPath(path)) return ME
  if (path === WALLET_PATH || path === MAPS_PATH || path === REFERRALS_PATH) return ME
  if (kudosMethodOf(path)) return { path: walletPath('rewards'), name: 'kudos.title' }
  if (path === PAYOUT_PATH || path === BANK_PATH || isWalletPath(path)) return WALLET
  if (mapOf(path)) return { path: MAPS_PATH, name: 'maps.title' }
  return null
}
