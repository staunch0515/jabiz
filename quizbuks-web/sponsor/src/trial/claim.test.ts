import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CLAIM_PROCESS, claimSource, rememberSource } from './claim'

vi.mock('@jabiz/client', () => ({ runProcess: vi.fn() }))
const { runProcess } = await import('@jabiz/client')

const KEY = 'cd'.repeat(24)

beforeEach(() => {
  window.localStorage.clear()
  vi.mocked(runProcess).mockReset()
})

afterEach(() => window.localStorage.clear())

describe('the trial a sponsor came by', () => {
  it('keeps only a trial key of the sign-up address', () => {
    rememberSource('?src=not-a-key')
    expect(window.localStorage.getItem('qb.trial-src')).toBeNull()
    rememberSource(`?src=${KEY}`)
    expect(window.localStorage.getItem('qb.trial-src')).toBe(KEY)
  })

  it('names it once the sponsor applied, and forgets it once the server answered', async () => {
    rememberSource(`?src=${KEY}`)
    vi.mocked(runProcess).mockResolvedValue({ outcome: 'claimed', operatorId: 'op-1' })
    await claimSource()
    expect(runProcess).toHaveBeenCalledWith(CLAIM_PROCESS, { trialKey: KEY })
    expect(window.localStorage.getItem('qb.trial-src')).toBeNull()
    await claimSource()
    expect(runProcess).toHaveBeenCalledTimes(1)
  })

  it('keeps it for the next time when the call fails', async () => {
    rememberSource(`?src=${KEY}`)
    vi.mocked(runProcess).mockRejectedValue(new Error('offline'))
    await claimSource()
    expect(window.localStorage.getItem('qb.trial-src')).toBe(KEY)
  })
})
