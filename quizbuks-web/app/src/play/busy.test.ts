import { ApiError } from '@jabiz/client'
import { describe, expect, it, vi } from 'vitest'
import '../messages'
import { BUSY_ATTEMPTS, busyDelay, isBusy, playProblemText, retryAfterMs, retryWhenBusy } from './busy'

/** The server's answer when a hot quiz's queue is full (platform decision D45). */
const busy = (retryAfter: number | null = 1) => new ApiError(503, { status: 503, title: 'Busy',
  reason: 'QUEUE_FULL', retryAfter, violations: [{ field: null, ruleCode: 'PROCESS_BUSY', message: 'Busy' }] })

describe('a busy answer', () => {
  it('is a 503 PROCESS_BUSY only, not any 503 or another refusal', () => {
    expect(isBusy(busy())).toBe(true)
    expect(isBusy(new ApiError(503, { title: 'Service unavailable' }))).toBe(false)
    expect(isBusy(new ApiError(503, { violations: [{ ruleCode: 'QUERY_TIMEOUT', message: 'slow' }] }))).toBe(false)
    expect(isBusy(new ApiError(429, { violations: [{ ruleCode: 'RATE_LIMITED', message: 'slow down' }] })))
      .toBe(false)
    expect(isBusy(new Error('offline'))).toBe(false)
  })

  it('carries the server\'s Retry-After in seconds', () => {
    expect(retryAfterMs(busy(3))).toBe(3_000)
    expect(retryAfterMs(busy(null))).toBe(0)
  })

  it('waits the doubling back-off, never less than Retry-After, with up to a quarter more at random', () => {
    expect([1, 2, 3, 4, 5, 9].map((n) => busyDelay(busy(0), n, () => 0))).toEqual([1_000, 2_000, 4_000, 8_000,
      8_000, 8_000])
    expect(busyDelay(busy(5), 1, () => 0)).toBe(5_000)
    expect(busyDelay(busy(20), 4, () => 0)).toBe(20_000)
    expect(busyDelay(busy(1), 1, () => 1)).toBe(1_250)
  })

  it('reads as the busy notice once the retries are spent', () => {
    expect(playProblemText(busy())).toMatch(/Many people are answering/)
    expect(playProblemText(new ApiError(422, { violations: [{ ruleCode: 'QB_X', message: 'No.' }] }))).toBe('No.')
  })
})

describe('retrying while busy', () => {
  it('tries again after each busy answer and says it is busy meanwhile', async () => {
    const call = vi.fn<() => Promise<string>>()
      .mockRejectedValueOnce(busy(2))
      .mockRejectedValueOnce(busy(1))
      .mockResolvedValue('started')
    const sleep = vi.fn(async () => {})
    const onBusy = vi.fn()
    await expect(retryWhenBusy(call, { sleep, onBusy, random: () => 0 })).resolves.toBe('started')
    expect(call).toHaveBeenCalledTimes(3)
    expect(sleep.mock.calls).toEqual([[2_000], [2_000]])
    expect(onBusy.mock.calls).toEqual([[true], [true], [false]])
  })

  it('gives any other failure back at once', async () => {
    const refusal = new ApiError(422, { violations: [{ ruleCode: 'QB_ATTEMPT_EXPIRED', message: 'Over' }] })
    const call = vi.fn<() => Promise<string>>().mockRejectedValue(refusal)
    const sleep = vi.fn(async () => {})
    await expect(retryWhenBusy(call, { sleep })).rejects.toBe(refusal)
    expect(call).toHaveBeenCalledTimes(1)
    expect(sleep).not.toHaveBeenCalled()
  })

  it('gives up after its tries with the last busy answer', async () => {
    const last = busy()
    const call = vi.fn<() => Promise<string>>().mockRejectedValue(last)
    const sleep = vi.fn(async () => {})
    const onBusy = vi.fn()
    await expect(retryWhenBusy(call, { sleep, onBusy })).rejects.toBe(last)
    expect(call).toHaveBeenCalledTimes(BUSY_ATTEMPTS)
    expect(sleep).toHaveBeenCalledTimes(BUSY_ATTEMPTS - 1)
    expect(onBusy).toHaveBeenLastCalledWith(false)
  })
})
