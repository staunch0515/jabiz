import { describe, expect, it } from 'vitest'
import { buildTime } from './build-time.ts'

/** Q7b review L5: the build time is that of the sources, so building the same sources again shows the same time. */
describe('the build time', () => {
  it('is SOURCE_DATE_EPOCH when set', () => {
    expect(buildTime({ SOURCE_DATE_EPOCH: '1792000000' }, () => '2020-01-01T00:00:00+09:00'))
      .toBe('2026-10-14T17:46:40.000Z')
  })

  it('is else the time of the commit built', () => {
    expect(buildTime({}, () => '2026-10-18T14:00:00+09:00')).toBe('2026-10-18T05:00:00.000Z')
    expect(buildTime({ SOURCE_DATE_EPOCH: 'soon' }, () => '2026-10-18T14:00:00+09:00'))
      .toBe('2026-10-18T05:00:00.000Z')
  })

  it('is unknown without either, never the time of building', () => {
    expect(buildTime({}, () => null)).toBeNull()
    expect(buildTime({}, () => 'not a time')).toBeNull()
  })

  it('reads the commit of this checkout', () => {
    expect(buildTime({})).toMatch(/^\d{4}-\d{2}-\d{2}T/)
  })
})
