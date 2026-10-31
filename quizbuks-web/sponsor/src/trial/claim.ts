import { runProcess } from '@jabiz/client'

/*
 * Which prospecting trial brought a sponsor (Q48d): the trial page's sign-up link carries its key (`?src=`); the console
 * keeps it in this browser, sends it with the application (Q48e-1: a first application, its review included, goes to
 * the operator that prospected the business), then names it once more (`QB_PROSPECT_CLAIM`, for a sponsor who applied
 * before they came by the link). Only a convenience: without the key nothing changes.
 */

const STORAGE_KEY = 'qb.trial-src'
export const CLAIM_PROCESS = 'QB_PROSPECT_CLAIM'

/** Keeps the key of a sign-up address's `?src=`, when it is a trial's key. */
export function rememberSource(search: string) {
  const key = new URLSearchParams(search).get('src')
  if (!key || !/^[0-9a-f]{48}$/.test(key)) return
  try {
    window.localStorage.setItem(STORAGE_KEY, key)
  } catch {
    // Storage blocked: the sign-up is not attributed.
  }
}

/** The kept key, sent with the first application (Q48e-1); null without one. */
export function remembered(): string | null {
  try {
    return window.localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

function forget() {
  try {
    window.localStorage.removeItem(STORAGE_KEY)
  } catch {
    // Nothing kept.
  }
}

/** Names the kept trial once the sponsor has applied; forgotten once the server answered. */
export async function claimSource(): Promise<void> {
  const trialKey = remembered()
  if (!trialKey) return
  try {
    await runProcess<{ outcome: string; operatorId: string | null }>(CLAIM_PROCESS, { trialKey })
    forget()
  } catch {
    // Kept for the next application or save: attribution is never worth a refusal to the sponsor.
  }
}
