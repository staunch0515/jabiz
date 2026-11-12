import { beforeEach, describe, expect, it, vi } from 'vitest'

const runQuery = vi.hoisted(() => vi.fn())
vi.mock('@jabiz/client', async (importOriginal) => ({ ...(await importOriginal<object>()), runQuery }))

import { readRewardSettings } from './api'
import { isFirstLaunch } from './useRewardSettings'

describe('the first launch system (F-F10a)', () => {
  it('is production and staging, whatever the case; a test system and an unanswered one are not', () => {
    expect(isFirstLaunch({ name: 'production', production: true })).toBe(true)
    expect(isFirstLaunch({ name: 'Staging', production: false })).toBe(true)
    expect(isFirstLaunch({ name: ' staging ', production: false })).toBe(true)
    expect(isFirstLaunch({ name: 'test', production: false })).toBe(false)
    expect(isFirstLaunch(undefined)).toBe(false)
  })
})

describe('the reward methods read', () => {
  beforeEach(() => {
    runQuery.mockReset()
    runQuery.mockResolvedValue({ items: [{ methods: 'SPONSOR_CASH', own: false }], offset: 0, limit: 1 })
  })

  it('tells the template whether this is a first launch system, as a parameter', async () => {
    await readRewardSettings(true)
    expect(runQuery).toHaveBeenLastCalledWith('qb.sponsor.reward-methods', { limit: 1, params: { firstLaunch: true } })
    const settings = await readRewardSettings()
    expect(runQuery).toHaveBeenLastCalledWith('qb.sponsor.reward-methods', { limit: 1, params: { firstLaunch: false } })
    expect(settings.methods).toEqual(['SPONSOR_CASH'])
  })
})
