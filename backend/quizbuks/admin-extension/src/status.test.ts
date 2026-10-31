import { describe, expect, it } from 'vitest'
import { statusTone } from './status'

describe('status colours (Q53c)', () => {
  it('colour a status by what it means, whatever list it is in', () => {
    expect(['APPROVED', 'LIVE', 'PUBLISHED', 'LISTED', 'PAID', 'CASH_CONFIRMED', 'DIGITAL_RESOLVED_SPONSOR']
      .map(statusTone)).toEqual(Array(7).fill('success'))
    expect(['PENDING', 'PENDING_REVIEW', 'PAUSED', 'CASH_DUE', 'in_progress'].map(statusTone))
      .toEqual(Array(5).fill('warning'))
    expect(['REJECTED', 'FAILED', 'TAKEN_DOWN', 'BANNED', 'CASH_OVERDUE', 'PAYOUT_FAILED'].map(statusTone))
      .toEqual(Array(6).fill('destructive'))
    expect(['ENDED', 'EXPIRED', 'CANCELLED', 'DRAFT', 'UNLISTED', 'SOMETHING_NEW', '', null, undefined]
      .map(statusTone)).toEqual(Array(9).fill('outline'))
  })
})
