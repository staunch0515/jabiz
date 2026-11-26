import { ACCOUNT_PATH, accountPath, isAccountPath, SETTINGS_PATH } from '../pages/accountPaths'
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
/** Q113d-2: the parts of the account are rows of the settings. */
const SETTINGS: Up = { path: SETTINGS_PATH, name: 'me.settings' }

/**
 * Where the header's back button goes from a page reached from "Me" (its rows and numbers): the page above it in the
 * menu's order, whatever page came before (a link from a mail, a notification). Every page under "Me" has one, so the
 * way back is always the same button in the same place; "Me" itself and the areas' own pages (their tabs) have none.
 */
export function upOf(path: string): Up | null {
  if (path === ACCOUNT_PATH || path === SETTINGS_PATH) return null
  // The certificates are a tab of My page; the other parts, rows of the settings.
  if (path === accountPath('certificates')) return ME
  if (isAccountPath(path)) return SETTINGS
  if (path === WALLET_PATH || path === MAPS_PATH || path === REFERRALS_PATH) return ME
  if (kudosMethodOf(path)) return { path: walletPath('rewards'), name: 'kudos.title' }
  if (path === PAYOUT_PATH || path === BANK_PATH || isWalletPath(path)) return WALLET
  if (mapOf(path)) return { path: MAPS_PATH, name: 'maps.title' }
  return null
}
