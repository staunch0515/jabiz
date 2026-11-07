import { session } from '../api/session'
import type { SignInStep } from './AuthContext'
import { sitePath } from './mailLink'

/**
 * The second step of a sign-in that started elsewhere (an identity provider's round trip), kept for the sign-in page
 * of this tab (phase 17zk, gap 2): the step and the page to go to afterwards survive the move to the sign-in page and
 * a reload, in every frontend, router or not. Kept in this tab's `sessionStorage` per entry; the challenge alone signs
 * nobody in (the code is still needed) and the server refuses it once expired.
 */
export interface PendingSignInStep {
  step: Extract<SignInStep, { challenge: string }>
  /** Where to go once signed in: only a path of this site. */
  returnTo: string
}

const stepKey = () => `jabiz.session.${session.entry()}.pendingStep`
/** Longer than the server keeps a challenge (`jabiz.security.mfa.challenge-ttl`): a stale one is never offered. */
const KEPT_MS = 15 * 60_000

/** Keeps the second step and the page to return to for the sign-in page of this tab. */
export function keepSignInStep(step: SignInStep, returnTo: string, now: number = Date.now()): void {
  if (step.status === 'SIGNED_IN') return
  try {
    window.sessionStorage.setItem(stepKey(),
      JSON.stringify({ status: step.status, challenge: step.challenge, returnTo: sitePath(returnTo), at: now }))
  } catch {
    // Without storage the sign-in page continues only from what it is handed directly.
  }
}

/** The second step kept in this tab, while fresh; null when none (or storage is unavailable). Read, not taken. */
export function pendingSignInStep(now: number = Date.now()): PendingSignInStep | null {
  try {
    const kept = JSON.parse(window.sessionStorage.getItem(stepKey()) ?? 'null') as
      { status?: unknown; challenge?: unknown; returnTo?: unknown; at?: unknown } | null
    if (!kept || typeof kept.challenge !== 'string' || typeof kept.at !== 'number' || now - kept.at > KEPT_MS
      || (kept.status !== 'MFA_REQUIRED' && kept.status !== 'MFA_ENROLLMENT_REQUIRED')) {
      return null
    }
    return {
      step: { status: kept.status, challenge: kept.challenge },
      returnTo: sitePath(typeof kept.returnTo === 'string' ? kept.returnTo : null),
    }
  } catch {
    return null
  }
}

/** Forgets the kept second step: signed in, or back to the password. */
export function forgetSignInStep(): void {
  try {
    window.sessionStorage.removeItem(stepKey())
  } catch {
    // Nothing kept without storage.
  }
}
