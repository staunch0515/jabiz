/**
 * The colour of a status badge (Q53c; docs/quizbuks/plans/Q53-admin-polish.md section 4), by what the status means
 * for whoever reads the list, whatever list it is in: done and in force (green), waiting for someone (amber), refused
 * or failed (red), over or set aside (no colour). The platform's badge variants carry the theme's tokens in light and
 * dark. A code not listed is shown without colour.
 */
export type StatusTone = 'success' | 'warning' | 'destructive' | 'outline'

const DONE = new Set(['APPROVED', 'ACCEPTED', 'ACTIVE', 'LIVE', 'PUBLISHED', 'LISTED', 'PAID', 'CREDITED', 'CONFIRMED',
  'DELIVERED', 'CLAIMED', 'ISSUED', 'ENABLED', 'AVAILABLE', 'SENT', 'COMPLETED', 'SUBMITTED', 'SUCCEEDED', 'RESOLVED',
  'MATCHED', 'SETTLED', 'REDEEMED', 'PAID_OUTSIDE', 'PASSED', 'WON'])
const WAITING = new Set(['PENDING', 'PENDING_REVIEW', 'IN_REVIEW', 'AWAITING', 'IN_PROGRESS', 'PAYING', 'OPEN', 'DUE',
  'PAUSED', 'HELD', 'COOLDOWN', 'SCHEDULED', 'INVITED', 'ONBOARDING', 'FUNDS_SHORT', 'MARKED_PAID', 'REQUESTED',
  'PROCESSING', 'DRAFTED', 'QUEUED', 'SENDING', 'RUNNING'])
const REFUSED = new Set(['REJECTED', 'DECLINED', 'FAILED', 'TAKEN_DOWN', 'BANNED', 'DISPUTED', 'OVERDUE', 'BOUNCED',
  'COMPLAINED', 'LOST', 'PAYOUT_FAILED', 'PROBLEM', 'SUSPENDED', 'REFUSED', 'RETURNED', 'BANK_RETURNED'])

export function statusTone(code: string | null | undefined): StatusTone {
  if (!code) return 'outline'
  const upper = code.toUpperCase()
  if (DONE.has(upper) || /_(CONFIRMED|DELIVERED|RESOLVED(_SPONSOR)?)$/.test(upper)) return 'success'
  if (WAITING.has(upper) || /_DUE$/.test(upper)) return 'warning'
  if (REFUSED.has(upper) || /_(DISPUTED|OVERDUE|FAILED)$/.test(upper)) return 'destructive'
  return 'outline'
}
