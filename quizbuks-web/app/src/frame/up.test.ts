import { describe, expect, it } from 'vitest'
import { upOf } from './up'

describe('upOf: the way back from the pages under "Me"', () => {
  it('goes up one level in the menu', () => {
    for (const path of ['/account/profile', '/account/security', '/account/certificates', '/account/delete', '/wallet',
      '/maps', '/referrals']) {
      expect(upOf(path)).toEqual({ path: '/account', name: 'frame.areas.me' })
    }
    for (const path of ['/wallet/balance', '/wallet/history', '/wallet/payout', '/wallet/bank']) {
      expect(upOf(path)?.path).toBe('/wallet')
    }
    expect(upOf('/wallet/kudos/SPONSOR_CASH')?.path).toBe('/wallet/rewards')
    expect(upOf('/maps/t1')?.path).toBe('/maps')
  })

  it('has none on "Me" and on the areas\' own pages', () => {
    for (const path of ['/account', '/', '/history', '/history/prizes', '/inbox', '/p/x']) {
      expect(upOf(path)).toBeNull()
    }
  })
})
