/**
 * The redeem code a taker scanned before signing in (Q22b), kept on the device for a few minutes so that signing in
 * leads back to it however it is done: a password in this tab returns by its `next`, but a mailed sign-in link or an
 * identity provider may finish in another tab, and the welcome step comes first for a taker without a profile.
 * Not a secret worth more than the link itself: a rotating code lasts two minutes and a printed one hangs in the shop.
 */
const KEY = 'qb.redeem.pending'

/** How long a scanned code is resumed after signing in: a mailed link takes a while, a rotating code says if it expired. */
export const RESUME_FOR_MS = 10 * 60_000

export function rememberRedeem(scannedKey: string, now: number = Date.now()) {
  try {
    window.localStorage.setItem(KEY, JSON.stringify({ key: scannedKey, at: now }))
  } catch {
    // A convenience only: signing in then leads to its own return path.
  }
}

/** The code to go back to, if one was scanned within {@link RESUME_FOR_MS}; read without forgetting it. */
export function pendingRedeem(now: number = Date.now()): string | null {
  try {
    const kept = JSON.parse(window.localStorage.getItem(KEY) ?? 'null') as { key?: unknown; at?: unknown } | null
    if (!kept || typeof kept.key !== 'string' || !kept.key || typeof kept.at !== 'number') return null
    return now - kept.at >= 0 && now - kept.at < RESUME_FOR_MS ? kept.key : null
  } catch {
    return null
  }
}

/** Forgets the scanned code: its page was reached (or the person left it on purpose). */
export function forgetRedeem() {
  try {
    window.localStorage.removeItem(KEY)
  } catch {
    // Nothing kept.
  }
}

/** The page of a scanned code. */
export const redeemPath = (scannedKey: string) => `/redeem/${encodeURIComponent(scannedKey)}`
