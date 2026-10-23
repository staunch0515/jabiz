import { useSyncExternalStore } from 'react'
import { api, sessionFetch, unwrap } from '../api/client'
import { ApiError, toApiError } from '../api/problem'
import type { components } from '../api/schema'
import { session, withRefreshLock } from '../api/session'

/**
 * The account functions without a page (decision D43; docs/design/10-security.md section 15): what the sign-up,
 * mailed-link and password pages of `@jabiz/ui` call, for an application that draws its own pages instead. They all
 * act for the frontend's sign-in entry unless another is named. Each throws an {@link ApiError} when refused.
 */

/** What a sign-in entry offers (`GET /api/auth/entries/{name}`), with its session's persistence (phase 16m). */
export type EntryInfo = components['schemas']['EntryInfo']

const entries = new Map<string, Promise<EntryInfo>>()

/** The entry's public settings, asked once per page (a failure is asked again next time). */
export function entryInfo(name: string = session.entry()): Promise<EntryInfo> {
  let info = entries.get(name)
  if (!info) {
    info = unwrap(api.GET('/api/auth/entries/{name}', { params: { path: { name } } })) as Promise<EntryInfo>
    info.catch(() => entries.delete(name))
    entries.set(name, info)
  }
  return info
}

/** Forgets what {@link entryInfo} read (tests, or after the configured entry changed). */
export function forgetEntryInfo() {
  entries.clear()
}

/**
 * Reads where the entry keeps its session and keeps the refresh token there (decision D43 item 2). The administration
 * is always `tab` and is not asked. A failure leaves things as they are.
 */
export async function loadSessionSettings(): Promise<void> {
  if (session.entry() === 'admin') return
  try {
    const info = await entryInfo()
    await session.applyPersistence(info.sessionPersistence === 'device' ? 'device' : 'tab')
  } catch {
    // Offline or refused: the token stays where it was found.
  }
}

/** The body of `POST /api/auth/account/password`. */
type PasswordChange = components['schemas']['PasswordRequest']

export interface Registration {
  email: string
  password: string
  displayName?: string
  /** The language of the mails (a platform language); the interface language when absent. */
  locale?: string
}

/**
 * Registers an account at the frontend's entry (decision D36): answered 202 alike whether the address is new, taken or
 * refused, so that nobody learns who has an account; the mail says what happened.
 */
export async function register(registration: Registration, entry: string = session.entry()): Promise<void> {
  await unwrap(api.POST('/api/auth/register', { body: { ...registration, entry } }))
}

/** Asks for a link to choose a new password; answered alike whatever the address. */
export async function requestPasswordReset(email: string, entry: string = session.entry()): Promise<void> {
  await unwrap(api.POST('/api/auth/password-reset/request', { body: { email: email.trim(), entry } }))
}

/** Sets a new password with the token of the mailed link; ends the account's sessions (no session is opened). */
export async function confirmPasswordReset(token: string, password: string): Promise<void> {
  await unwrap(api.POST('/api/auth/password-reset/confirm', { body: { token, password } }))
}

/** Asks for a new verification mail; answered alike whatever the address. */
export async function resendVerification(email: string, entry: string = session.entry()): Promise<void> {
  await unwrap(api.POST('/api/auth/verify-email/resend', { body: { email: email.trim(), entry } }))
}

export interface EmailLinkRequest {
  /** The path of this frontend the link leads back to once signed in (`/p/123?ref=share`); the entry's home when absent. */
  to?: string
  /** The language of the mail (a platform language); the interface language when absent. */
  locale?: string
}

/**
 * Asks for a link to sign in with by mail (decision D54): answered 202 alike for every address, so that nobody learns
 * who has an account. A trusted address of an account gets a link that signs its user in; an address nobody uses gets
 * one that opens an account where the entry opens accounts; any other address gets nothing.
 */
export async function requestEmailLink(email: string, request: EmailLinkRequest = {},
  entry: string = session.entry()): Promise<void> {
  await unwrap(api.POST('/api/auth/email-link', { body: { email: email.trim(), entry, to: request.to,
    locale: request.locale } }))
}

/**
 * Changes the signed-in user's own password (`POST /api/auth/account/password`, phase 16m; decision D43 item 3): needs
 * the current one, a wrong one counting towards the lock-out. The other sessions end; this one is kept, as its
 * refresh token is sent along.
 */
export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  // Not api.POST: the body is made for each attempt, since a refresh between them rotates the token, and the session
  // kept is the one of the token current when sent. Read as a refresh reads it (under the lock, from the copy every tab
  // sees alike): this tab's view of localStorage may lag another tab's refresh, and an unknown token keeps no session.
  const response = await sessionFetch('/api/auth/account/password', async () => {
    const refreshToken = await withRefreshLock(() => session.lockedRefreshToken())
    const body: PasswordChange = { currentPassword, newPassword, refreshToken: refreshToken ?? undefined }
    return { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
  })
  if (!response.ok) throw await toApiError(response)
}

/** The signed-in user's live sessions at this frontend's entry (`GET /api/auth/account/sessions`, phase 16g). */
export type SessionList = components['schemas']['SessionList']
/** One live session: where and with what it was last used, whether it is this one (`current`). */
export type SessionInfo = components['schemas']['SessionView']
/** How many sessions ended, and how long their access tokens may still work. */
export type SessionsRevoked = components['schemas']['Revoked']

/**
 * The signed-in user's live sessions at the entry of this one, the most recently used first (at most 50, `truncated`
 * when there are more). Sessions at other entries (another frontend) are not listed (decision D36).
 */
export async function listSessions(): Promise<SessionList> {
  return unwrap(api.GET('/api/auth/account/sessions')) as Promise<SessionList>
}

/**
 * Ends one of the signed-in user's sessions (`POST /api/auth/account/sessions/{id}/revoke`): it can no longer be
 * refreshed, its access token works until it expires (`effectiveWithinSeconds` of the list). Ending this frontend's
 * own session (`current`) signs it out here as well.
 */
export async function revokeSession(id: string, current = false): Promise<void> {
  await unwrap(api.POST('/api/auth/account/sessions/{id}/revoke', { params: { path: { id } } }))
  if (current) session.clear()
}

/** Ends every other session of the signed-in user at this entry; this one stays. */
export async function revokeOtherSessions(): Promise<SessionsRevoked> {
  return unwrap(api.POST('/api/auth/account/sessions/revoke-others')) as Promise<SessionsRevoked>
}

/** One provider account linked to the signed-in user (`GET /api/auth/account/identities`, phase 16f). */
export type LinkedIdentity = components['schemas']['IdentityView']
/** The account linked by {@link finishIdentityLink}: `LINKED`, or `ALREADY_LINKED` when it was linked before. */
export type IdentityLinked = components['schemas']['LinkResult']

/** The provider accounts linked to the signed-in user, by provider. */
export async function listIdentities(): Promise<LinkedIdentity[]> {
  return unwrap(api.GET('/api/auth/account/identities')) as Promise<LinkedIdentity[]>
}

/**
 * Hands the provider's state and code back to the server to link the account the round trip of `startIdentityLink`
 * proved (decision D39 item 2); the session stays as it is. Refusals are 422 (`IDENTITY_IN_USE`,
 * `IDENTITY_PROVIDER_LINKED`, `IDENTITY_LINK_UNAVAILABLE`, `IDENTITY_LINK_FAILED`).
 */
export async function finishIdentityLink(state: string, code: string, binder: string): Promise<IdentityLinked> {
  return unwrap(api.POST('/api/auth/account/identities/callback', { body: { state, code, binder } })) as
    Promise<IdentityLinked>
}

/**
 * Removes a linked provider account; refused (422 `LAST_SIGN_IN_METHOD`) when it is the only way left to sign in.
 * Sessions signed in through it end at their next refresh.
 */
export async function unlinkIdentity(id: string): Promise<void> {
  await unwrap(api.DELETE('/api/auth/account/identities/{id}', { params: { path: { id } } }))
}

/** Whether a failure is the server's refusal with this rule code (and, when given, this status). */
export function isProblem(error: unknown, code: string, status?: number): error is ApiError {
  return error instanceof ApiError && (status === undefined || error.status === status)
    && error.violations.some((v) => v.ruleCode === code)
}

function subscribeOnline(onChange: () => void) {
  window.addEventListener('online', onChange)
  window.addEventListener('offline', onChange)
  return () => {
    window.removeEventListener('online', onChange)
    window.removeEventListener('offline', onChange)
  }
}

/** Whether the browser believes it is online; follows the `online` / `offline` events. */
export function useOnline(): boolean {
  return useSyncExternalStore(subscribeOnline, () => navigator.onLine, () => true)
}
