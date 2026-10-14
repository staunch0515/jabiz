import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clockOffset, formatRemaining, remainingMs, threshold, useCountdown } from './countdown'

describe('the countdown', () => {
  it('counts by the server\'s clock: the device may be minutes off', () => {
    // The server said 12:00:00 when the device read 11:55:00: the server is five minutes ahead.
    const received = Date.parse('2026-10-14T11:55:00Z')
    const offset = clockOffset('2026-10-14T12:00:00Z', received)
    expect(offset).toBe(5 * 60_000)
    // A deadline of 12:05 by the server is five minutes away when the device reads 11:55.
    expect(remainingMs('2026-10-14T12:05:00Z', offset, received)).toBe(5 * 60_000)
    // Never below zero, and zero without a deadline.
    expect(remainingMs('2026-10-14T12:05:00Z', offset, received + 6 * 60_000)).toBe(0)
    expect(remainingMs('not a time', 0, received)).toBe(0)
    expect(clockOffset('garbage', received)).toBe(0)
  })

  it('shows minutes and seconds, rounding up, with hours for long quizzes', () => {
    expect(formatRemaining(0)).toBe('0:00')
    expect(formatRemaining(1)).toBe('0:01')
    expect(formatRemaining(59_001)).toBe('1:00')
    expect(formatRemaining(5 * 60_000)).toBe('5:00')
    expect(formatRemaining(2 * 3600_000 + 61_000)).toBe('2:01:01')
  })

  it('reaches its announced thresholds at a minute and at ten seconds', () => {
    expect(threshold(120_000)).toBeNull()
    expect(threshold(60_000)).toBe(60_000)
    expect(threshold(11_000)).toBe(60_000)
    expect(threshold(10_000)).toBe(10_000)
    expect(threshold(0)).toBe(10_000)
  })
})

function Clock({ deadline, offset, onExpired }: { deadline: string; offset: number; onExpired: () => void }) {
  const left = useCountdown(deadline, offset, onExpired)
  return <span data-testid="left">{formatRemaining(left)}</span>
}

describe('useCountdown', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(Date.parse('2026-10-14T12:00:00Z'))
  })
  afterEach(() => vi.useRealTimers())

  it('ticks down and says once that the time is over', () => {
    const expired = vi.fn()
    // The server is 30 seconds behind the device: the deadline 12:00:20 by the server is 50 seconds away.
    render(<Clock deadline="2026-10-14T12:00:20Z" offset={-30_000} onExpired={expired} />)
    expect(screen.getByTestId('left')).toHaveTextContent('0:50')
    act(() => vi.advanceTimersByTime(20_000))
    expect(screen.getByTestId('left')).toHaveTextContent('0:30')
    expect(expired).not.toHaveBeenCalled()
    act(() => vi.advanceTimersByTime(31_000))
    expect(screen.getByTestId('left')).toHaveTextContent('0:00')
    act(() => vi.advanceTimersByTime(5_000))
    expect(expired).toHaveBeenCalledTimes(1)
  })

  it('expires at once when the deadline has passed already', () => {
    const expired = vi.fn()
    render(<Clock deadline="2026-10-14T11:59:00Z" offset={0} onExpired={expired} />)
    expect(screen.getByTestId('left')).toHaveTextContent('0:00')
    expect(expired).toHaveBeenCalledTimes(1)
  })
})
