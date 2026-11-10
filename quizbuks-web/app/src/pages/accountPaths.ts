/*
 * The addresses of "Me" (Q56, F-F4). A module of its own with no imports, so that `frame/areas.ts` and the pages it
 * names can both read them without importing each other.
 */

/** "Me" (the account's menu, Q56); each part of the account is a page of its own under it. */
export const ACCOUNT_PATH = '/account'

/**
 * The parts of the account, each at `/account/<part>` (docs/quizbuks/plans/Q56-account-menu.md); F-F4 added `blocks`
 * ("Blocks and topics": the blocks of F-S8 and the liked topics of F-S9).
 */
export const ACCOUNT_PARTS = ['profile', 'details', 'certificates', 'security', 'mail', 'preferences', 'blocks', 'id'] as const
export type AccountPart = (typeof ACCOUNT_PARTS)[number]

/** Deleting the account (Q71): its own page, in the menu only where the entry allows it. */
export const ACCOUNT_DELETION_PATH = `${ACCOUNT_PATH}/delete`

/** A part's page. */
export function accountPath(part: AccountPart): string {
  return `${ACCOUNT_PATH}/${part}`
}

/** The details for matching (Q47), from a quiz the caller's profile does not match. */
export const ACCOUNT_DETAILS_PATH = accountPath('details')

/** Where a round trip through an identity provider to link an account comes back to, and the step-up's setup. */
export const ACCOUNT_SECURITY_PATH = accountPath('security')

/** Blocks and topics (F-F4; F-S8's blocks and F-S9's liked topics). */
export const ACCOUNT_BLOCKS_PATH = accountPath('blocks')

/** Whether a path is the account's menu or one of its parts. */
export function isAccountPath(path: string): boolean {
  return path === ACCOUNT_PATH || path.startsWith(`${ACCOUNT_PATH}/`)
}
