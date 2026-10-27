/**
 * The names the app's staff pages call on the server (backend/quizbuks, package `reward`; docs/quizbuks/plans/
 * Q39-sponsor-staff.md). Only the app has a sponsor's staff at the counter, so they live here rather than in the
 * shared `src/qb/ids.ts` (backend/quizbuks/CLAUDE.md section 5).
 */

/** Processes (`reward.StaffProcesses`, `reward.RedemptionProcesses`'s outputs). */
export const STAFF_PROCESS = {
  /** The sponsor's rotating code, issued in the staff member's name. */
  issue: 'QB_STAFF_REDEEM_CODE_ISSUE',
  confirm: 'QB_STAFF_REDEMPTION_CONFIRM',
  decline: 'QB_STAFF_REDEMPTION_DECLINE',
  leave: 'QB_STAFF_LEAVE',
} as const

/** SQL templates (`queries/qb/app`). */
export const STAFF_TEMPLATE = {
  /** The sponsor whose staff the caller is on: none or one row. */
  staffOf: 'qb.app.staff-of',
  /** That sponsor's prizes claimed or collected. */
  redemptions: 'qb.app.staff-redemptions',
} as const

/** Error codes the pages react to. */
export const STAFF_CODE = {
  /** 422: the caller is on no sponsor's staff (removed meanwhile). */
  notStaff: 'QB_NOT_STAFF',
} as const
