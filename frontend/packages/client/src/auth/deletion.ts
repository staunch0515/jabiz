import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import type { components } from '../api/schema'
import { session } from '../api/session'

/**
 * Asking for one's own account to be deleted (docs/design/10-security.md section 17; decision D62): signed in from the
 * account page, or without a session by a link mailed to the account's address. The account is deleted at the end of a
 * cooling-off period and works until then; a request in the account's own session can be withdrawn. Each function
 * throws an `ApiError` when refused.
 */

/** Whether the caller's entry lets them ask, what is pending, and the notes to confirm first (already localized). */
export type AccountDeletionStatus = components['schemas']['DeletionStatus']
/** A pending request: when it was made, when the account is deleted (`dueTime`) and how it was asked. */
export type AccountDeletionPending = components['schemas']['DeletionPending']
/** A note the person confirms before asking (`erasure.notice.<code>`): its code and its text. */
export type AccountDeletionNotice = components['schemas']['NoticeView']
/** A request made: its number and when the account is deleted. */
export type AccountDeletionRequested = components['schemas']['DeletionRequested']
/** A request withdrawn. */
export type AccountDeletionCancelled = components['schemas']['DeletionCancelled']

export interface AccountDeletionRequest {
  /** Asked for only when the session passed no second factor recently (422 `REAUTHENTICATION_REQUIRED`). */
  currentPassword?: string
  /** The codes of the notes confirmed. */
  acknowledgedNotices: readonly string[]
}

/** The signed-in user's deletion: whether it can be asked for here, a pending request, the notes to confirm. */
export async function getAccountDeletion(): Promise<AccountDeletionStatus> {
  return unwrap(api.GET('/api/auth/account/deletion')) as Promise<AccountDeletionStatus>
}

/**
 * Asks for the signed-in user's account to be deleted. Refusals are 422: `REAUTHENTICATION_REQUIRED` (ask for the
 * current password), `REAUTHENTICATION_UNAVAILABLE`, `PASSWORD_INCORRECT`, `ACCOUNT_DELETION_NOTICES` (with the notes
 * not confirmed, see {@link unacknowledgedNotices}), `ACCOUNT_DELETION_PENDING`, `ACCOUNT_DELETION_PROTECTED` and
 * `ACCOUNT_DELETION_UNAVAILABLE`.
 */
export async function requestAccountDeletion(request: AccountDeletionRequest): Promise<AccountDeletionRequested> {
  return unwrap(api.POST('/api/auth/account/deletion', {
    body: { currentPassword: request.currentPassword, acknowledgedNotices: [...request.acknowledgedNotices] },
  })) as Promise<AccountDeletionRequested>
}

/** Withdraws the signed-in user's pending request; 422 `ACCOUNT_DELETION_NONE` when there is none. */
export async function cancelAccountDeletion(): Promise<AccountDeletionCancelled> {
  return unwrap(api.POST('/api/auth/account/deletion/cancel')) as Promise<AccountDeletionCancelled>
}

/**
 * Asks, without a session, for a link to delete the account of an address (an application nobody has installed any
 * more): answered 202 alike for every address, so that nobody learns who has an account. Refused (422
 * `ACCOUNT_DELETION_UNAVAILABLE`) where the entry does not let accounts be deleted.
 */
export async function requestAccountDeletionLink(
  { email, entry = session.entry() }: { email: string; entry?: string },
): Promise<void> {
  await unwrap(api.POST('/api/auth/account-deletion', { body: { email: email.trim(), entry } }))
}

/**
 * Asks for the deletion with the token of the mailed link. Refusals are 422: `ACCOUNT_DELETION_NOTICES` (the token
 * stays good: confirm the notes and send again), `TOKEN_INVALID`, `ACCOUNT_DELETION_PROTECTED` and
 * `ACCOUNT_DELETION_PENDING`.
 */
export async function confirmAccountDeletion(
  { token, acknowledgedNotices }: { token: string; acknowledgedNotices: readonly string[] },
): Promise<AccountDeletionRequested> {
  return unwrap(api.POST('/api/auth/account-deletion/confirm', {
    body: { token, acknowledgedNotices: [...acknowledgedNotices] },
  })) as Promise<AccountDeletionRequested>
}

const NOTICE_RULE = 'erasure.notice.'

/**
 * The notes a refusal `ACCOUNT_DELETION_NOTICES` names as not confirmed (one violation each, `erasure.notice.<code>`,
 * its text the message); empty for any other failure.
 */
export function unacknowledgedNotices(error: unknown): AccountDeletionNotice[] {
  if (!(error instanceof ApiError) || !error.violations.some((v) => v.ruleCode === 'ACCOUNT_DELETION_NOTICES')) {
    return []
  }
  return error.violations
    .filter((v) => v.ruleCode.startsWith(NOTICE_RULE))
    .map((v) => ({ code: v.ruleCode.slice(NOTICE_RULE.length), message: v.message }))
}
