import { act, fireEvent, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useIdleLock } from './useIdleLock'

function Probe({ seconds, onIdle }: { seconds: number | null; onIdle: () => void }) {
  useIdleLock(seconds, onIdle)
  return null
}

describe('useIdleLock', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('locks after the idle time, counted from the last activity', () => {
    const onIdle = vi.fn()
    render(<Probe seconds={60} onIdle={onIdle} />)
    act(() => vi.advanceTimersByTime(50_000))
    fireEvent.keyDown(window, { key: 'a' })
    act(() => vi.advanceTimersByTime(50_000))
    expect(onIdle).not.toHaveBeenCalled()
    act(() => vi.advanceTimersByTime(10_000))
    expect(onIdle).toHaveBeenCalledOnce()
  })

  it('does nothing until the timeout is known', () => {
    const onIdle = vi.fn()
    render(<Probe seconds={null} onIdle={onIdle} />)
    act(() => vi.advanceTimersByTime(3_600_000))
    expect(onIdle).not.toHaveBeenCalled()
  })
})
