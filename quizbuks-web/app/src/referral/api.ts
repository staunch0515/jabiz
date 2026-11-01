import { runProcess, runQuery } from '@jabiz/client'
import { referralPolicy, type CashView, type DigitalView, type RedeemView, type ReferralMethod, type ReferralPolicy }
  from '../qb/play'
import { hrefOf } from '../qb/router'
import { sharePath } from '../share/share'
import { REFERRAL_PROCESS, REFERRAL_TEMPLATE } from './ids'

/*
 * The app's referrals (Q70): typed by hand after `ReferralProcesses` (LinkOutput, ClaimOutput, ReferralView) and the
 * templates `qb.app.referrals` / `qb.app.referral-links`. The server decides everything (who may refer, who counts,
 * when the reward is paid); the pages only show it. A referrer never learns who their friend is.
 */

export const REFERRALS_PATH = '/referrals'
export const REFERRALS_QUERY = ['qb', 'referrals'] as const

export type ReferralStatus = 'REFERRED' | 'REWARDED' | 'EXPIRED' | 'VOID'

export interface ReferralLink {
  publicationId: string
  linkKey: string
  quotaLeft: number
}

/** The caller's link to a publication: made now, or the one made before. */
export const referralLink = (publicationId: string) =>
  runProcess<ReferralLink>(REFERRAL_PROCESS.link, { publicationId })

/** The address a link opens: the publication's page with the referral's key. */
export function referralUrl(publicationId: string, linkKey: string, origin: string = window.location.origin): string {
  return `${origin}${hrefOf(sharePath(publicationId))}?ref=${encodeURIComponent(linkKey)}`
}

export interface ClaimResult {
  publicationId: string
  counted: boolean
  expiresAt: string | null
}

/** Claims the referral of an opened link: counted or not, the page opens as ever (nobody is told why not). */
export const claimReferral = (linkKey: string) => runProcess<ClaimResult>(REFERRAL_PROCESS.claim, { linkKey })

export interface MyReferral {
  referralId: string
  publicationId: string
  publicationName: string
  status: ReferralStatus
  referredAt: string
  expiresAt: string
  rewardMethod: ReferralMethod
  amount: unknown
  rewardedAt: string | null
  endedAt: string | null
}

const text = (value: unknown) => (value === null || value === undefined ? null : String(value))

export async function readMyReferrals(): Promise<MyReferral[]> {
  const page = await runQuery<Record<string, unknown>>(REFERRAL_TEMPLATE.referrals, { limit: 100 })
  return page.items.map((row) => ({
    referralId: String(row.referralId),
    publicationId: String(row.publicationId),
    publicationName: String(row.publicationName ?? ''),
    status: String(row.status) as ReferralStatus,
    referredAt: String(row.referredAt ?? ''),
    expiresAt: String(row.expiresAt ?? ''),
    rewardMethod: String(row.rewardMethod) as ReferralMethod,
    amount: row.amount,
    rewardedAt: text(row.rewardedAt),
    endedAt: text(row.endedAt),
  }))
}

export interface MyLink {
  linkKey: string
  publicationId: string
  publicationName: string
  referral: ReferralPolicy | null
  status: string | null
  endAt: string | null
  placesLeft: number
  broughtByMe: number
}

export async function readMyLinks(): Promise<MyLink[]> {
  const page = await runQuery<Record<string, unknown>>(REFERRAL_TEMPLATE.links, { limit: 100 })
  return page.items.map((row) => ({
    linkKey: String(row.linkKey),
    publicationId: String(row.publicationId),
    publicationName: String(row.publicationName ?? ''),
    referral: referralPolicy(row.referral),
    status: text(row.status),
    endAt: text(row.endAt),
    placesLeft: Number(row.placesLeft ?? 0),
    broughtByMe: Number(row.broughtByMe ?? 0),
  }))
}

/** `ReferralProcesses.ReferralView`: one of the caller's referrals with its reward's record. */
export interface ReferralView {
  referralId: string
  publicationId: string
  publicationName: string | null
  status: ReferralStatus
  referredAt: string
  expiresAt: string
  rewardMethod: ReferralMethod
  amount: unknown
  rewardedAt: string | null
  endedAt: string | null
  cash: CashView | null
  redeem: RedeemView | null
  digital: DigitalView | null
}

export const readReferral = (referralId: string) =>
  runProcess<ReferralView>(REFERRAL_PROCESS.view, { referralId })

/**
 * The query key of a referral's page: the result page's, so that its reward parts (confirm a cash reward, open a
 * prize) refresh it as they refresh a result.
 */
export const referralQueryKey = (referralId: string) => ['qb', 'attempt-result', referralId] as const

const PENDING_KEY = 'qb.referral.pending'

/**
 * Keeps an opened link's key while the friend signs in or registers (this tab only), with the publication it is for;
 * claimed once they can, then forgotten.
 */
export function rememberReferral(publicationId: string, linkKey: string) {
  try {
    window.sessionStorage.setItem(PENDING_KEY, JSON.stringify({ publicationId, linkKey }))
  } catch {
    // A convenience only: opening the link again claims it.
  }
}

export function pendingReferral(publicationId: string): string | null {
  try {
    const raw = window.sessionStorage.getItem(PENDING_KEY)
    if (!raw) return null
    const pending = JSON.parse(raw) as { publicationId?: unknown; linkKey?: unknown }
    return pending.publicationId === publicationId && typeof pending.linkKey === 'string' ? pending.linkKey : null
  } catch {
    return null
  }
}

export function forgetReferral() {
  try {
    window.sessionStorage.removeItem(PENDING_KEY)
  } catch {
    // Nothing kept.
  }
}

/** The key of a link the address carries (`?ref=`), if any; a key is 32 hexadecimal characters. */
export function referralKeyOf(search: string): string | null {
  const key = new URLSearchParams(search).get('ref')
  return key && /^[0-9a-f]{32}$/.test(key) ? key : null
}

/**
 * At boot: a publication's link with a referral's key (`/p/<id>?ref=<key>`) is noted for the tab, before signing in
 * or registering, and the parameter goes from the address (as the link language's does); the publication's page
 * claims it once the friend is signed in and verified.
 */
export function takeReferralFromAddress(win: Window = window) {
  const url = new URL(win.location.href)
  const key = referralKeyOf(url.search)
  if (!key) return
  const match = /\/p\/([^/?#]+)$/.exec(url.pathname)
  if (match) rememberReferral(decodeURIComponent(match[1]), key)
  url.searchParams.delete('ref')
  win.history.replaceState(win.history.state, '', url.pathname + url.search + url.hash)
}
