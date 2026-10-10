import { act, fireEvent, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useIdleLock } from './useIdleLock'

function Probe({ seconds, onIdle, onActivity }: { seconds: number | null; onIdle: () => void; onActivity?: () => void }) {
  useIdleLock(seconds, onIdle, onActivity)
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

  it('keeps the server session alive while the user is active, at most once per third of the idle time', () => {
    const onActivity = vi.fn()
    render(<Probe seconds={60} onIdle={vi.fn()} onActivity={onActivity} />)
    act(() => vi.advanceTimersByTime(10_000))
    fireEvent.keyDown(window, { key: 'a' })
    expect(onActivity).not.toHaveBeenCalled()
    act(() => vi.advanceTimersByTime(15_000))
    fireEvent.pointerDown(window)
    fireEvent.keyDown(window, { key: 'b' })
    expect(onActivity).toHaveBeenCalledOnce()
  })

  it('does nothing until the timeout is known', () => {
    const onIdle = vi.fn()
    render(<Probe seconds={null} onIdle={onIdle} />)
    act(() => vi.advanceTimersByTime(3_600_000))
    expect(onIdle).not.toHaveBeenCalled()
  })
})
