import { describe, expect, it } from 'vitest'
import { formatDuration, parseDuration } from './duration'

describe('the duration the sponsor types (Q75)', () => {
  it('reads minutes:seconds, hours:minutes:seconds and plain seconds within two hours', () => {
    expect(parseDuration('2:30')).toBe(150)
    expect(parseDuration(' 0:05 ')).toBe(5)
    expect(parseDuration('1:00:00')).toBe(3600)
    expect(parseDuration('150')).toBe(150)
    expect(parseDuration('2:00:00')).toBe(7200)
    for (const bad of ['', '0', '2:60', '2:5x', '2:00:01', '7201', '-3', 'abc', '1:2:3:4']) {
      expect(parseDuration(bad), bad).toBeNull()
    }
  })

  it('shows seconds as minutes:seconds, hours when needed', () => {
    expect(formatDuration(150)).toBe('2:30')
    expect(formatDuration(5)).toBe('0:05')
    expect(formatDuration(3661)).toBe('1:01:01')
  })
})
