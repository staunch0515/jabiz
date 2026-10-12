import { useSyncExternalStore } from 'react'
import { api, sessionFetch, unwrap } from '../api/client'
import { ApiError, toApiError } from '../api/problem'
import type { components } from '../api/schema'
import { session } from '../api/session'

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

/**
 * Changes the signed-in user's own password (`POST /api/auth/account/password`, phase 16m; decision D43 item 3): needs
 * the current one, a wrong one counting towards the lock-out. The other sessions end; this one is kept, as its
 * refresh token is sent along.
 */
export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  // Not api.POST: the body is made for each attempt, since a refresh between them rotates the token, and the session
  // kept is the one of the token current when sent (an earlier one would be taken for a reused token).
  const response = await sessionFetch('/api/auth/account/password', () => {
    const body: PasswordChange = { currentPassword, newPassword, refreshToken: session.refreshToken() ?? undefined }
    return { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
  })
  if (!response.ok) throw await toApiError(response)
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
