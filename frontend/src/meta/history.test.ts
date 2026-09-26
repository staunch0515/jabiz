import { describe, expect, it } from 'vitest'
import { baseOf, changesOf, differingFields, isScheduled, newestFirst } from './history'
import { version } from '../test/fixtures'

const v1 = version(1, { name: 'A', limit: 1 })
const v2 = version(2, { name: 'B', limit: 1 }, { changedFields: ['name'] })
const v3 = version(3, { name: 'B', limit: 2 }, { changedFields: ['limit'], baseVersionNo: null, effectStartTime: '2026-03-01T00:00:00Z' })

describe('history helpers', () => {
  it('orders newest first and finds the base version', () => {
    expect(newestFirst([v1, v3, v2]).map((v) => v.versionNo)).toEqual([3, 2, 1])
    expect(baseOf([v1, v2, v3], v2)).toBe(v1)
    expect(baseOf([v1, v2, v3], v3)).toBe(v2)
    expect(baseOf([v1], v1)).toBeUndefined()
  })

  it('lists what a version changed, before and after', () => {
    expect(changesOf([v1, v2, v3], v2)).toEqual([{ field: 'name', before: 'A', after: 'B' }])
    expect(changesOf([v1, v2, v3], v1)).toEqual([
      { field: 'name', before: undefined, after: 'A' },
      { field: 'limit', before: undefined, after: 1 },
    ])
  })

  it('marks scheduled versions and differences from now', () => {
    const now = new Date('2026-02-01T00:00:00Z')
    expect(isScheduled(v2, now)).toBe(false)
    expect(isScheduled(v3, now)).toBe(true)
    expect([...differingFields({ a: 1, b: 2 }, { a: 1, b: 3, c: 4 })].sort()).toEqual(['b', 'c'])
    expect(differingFields(null, null).size).toBe(0)
  })
})
