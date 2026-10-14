import { describe, expect, it, vi } from 'vitest'
import { checkoutReturn, parseKudos, payableUrl, topUpIdOf, topUpPath } from './api'
import { MAX_WAIT_MS, pollUntil, waitAfter } from './poll'
import { stripePageUrl } from './stripePage'

describe('the address of a payment page', () => {
  const remote = { hostname: 'quizbuks.example.com' }
  const local = { hostname: 'localhost' }

  it('follows https only', () => {
    expect(stripePageUrl('https://checkout.stripe.com/c/pay/cs_test_1#frag', remote))
      .toBe('https://checkout.stripe.com/c/pay/cs_test_1#frag')
    expect(stripePageUrl('http://checkout.stripe.com/c/pay/cs_test_1', remote)).toBeNull()
    expect(stripePageUrl('javascript:alert(1)', remote)).toBeNull()
    expect(stripePageUrl('data:text/html,<script>1</script>', remote)).toBeNull()
    expect(stripePageUrl('//evil.example.com/pay', remote)).toBeNull()
    expect(stripePageUrl('/sponsor/wallet', remote)).toBeNull()
    expect(stripePageUrl('https://user:pass@checkout.stripe.com/', remote)).toBeNull()
    expect(stripePageUrl('', remote)).toBeNull()
    expect(stripePageUrl(null, remote)).toBeNull()
    expect(stripePageUrl(42, remote)).toBeNull()
    expect(stripePageUrl(`https://checkout.stripe.com/${'a'.repeat(2048)}`, remote)).toBeNull()
  })

  it('follows a local stub only from a page on this machine', () => {
    expect(stripePageUrl('http://localhost:8093/pay/cs_test_1', local)).toBe('http://localhost:8093/pay/cs_test_1')
    expect(stripePageUrl('http://127.0.0.1:8093/pay/cs_test_1', { hostname: '127.0.0.1' }))
      .toBe('http://127.0.0.1:8093/pay/cs_test_1')
    expect(stripePageUrl('http://localhost:8093/pay/cs_test_1', remote)).toBeNull()
    expect(stripePageUrl('http://evil.example.com/pay', local)).toBeNull()
    expect(stripePageUrl('http://localhost.evil.example.com/pay', local)).toBeNull()
  })
})

describe('the top-up helpers', () => {
  it('reads whole Kudos as typed', () => {
    expect(parseKudos('1500')).toBe(1500)
    expect(parseKudos(' 12,000 ')).toBe(12000)
    expect(parseKudos('0')).toBeNull()
    expect(parseKudos('-5')).toBeNull()
    expect(parseKudos('1.5')).toBeNull()
    expect(parseKudos('1e3')).toBeNull()
    expect(parseKudos('')).toBeNull()
    expect(parseKudos('abc')).toBeNull()
    expect(parseKudos('12345678901234')).toBeNull()
  })

  it('knows how Stripe sent the sponsor back', () => {
    expect(checkoutReturn('?checkout=paid')).toBe('paid')
    expect(checkoutReturn('?checkout=cancelled')).toBe('cancelled')
    expect(checkoutReturn('?checkout=other')).toBeNull()
    expect(checkoutReturn('')).toBeNull()
  })

  it('finds the top-up of a path', () => {
    expect(topUpIdOf('/wallet/topups/t1')).toBe('t1')
    expect(topUpIdOf(topUpPath('a b'))).toBe('a b')
    expect(topUpIdOf('/wallet/topups/%E0%A4%A')).toBeNull()
    expect(topUpIdOf('/wallet')).toBeNull()
    expect(topUpIdOf('/wallet/topups/t1/x')).toBeNull()
  })

  it('offers the checkout only while it can be paid', () => {
    const now = Date.parse('2026-10-14T10:00:00Z')
    const open = { status: 'OPEN' as const, checkoutUrl: 'https://checkout.stripe.com/x',
      expiresAt: '2026-10-14T11:00:00Z' }
    expect(payableUrl(open, now)).toBe('https://checkout.stripe.com/x')
    expect(payableUrl({ ...open, expiresAt: '2026-10-14T09:00:00Z' }, now)).toBeNull()
    expect(payableUrl({ ...open, status: 'PAID' }, now)).toBeNull()
    expect(payableUrl({ ...open, checkoutUrl: null }, now)).toBeNull()
  })
})

describe('polling', () => {
  /** A clock that moves only when the poll waits. */
  function clock() {
    let time = 0
    const waits: number[] = []
    return {
      now: () => time,
      sleep: async (ms: number) => {
        waits.push(ms)
        time += ms
      },
      waits,
    }
  }

  it('waits longer and longer, up to a cap', () => {
    expect([1, 2, 3, 4, 5, 6, 20].map(waitAfter)).toEqual([1000, 1000, 2000, 2000, 3000, MAX_WAIT_MS, MAX_WAIT_MS])
  })

  it('stops once the value is final, telling every value read', async () => {
    const c = clock()
    const values = ['OPENING', 'OPENING', 'OPEN']
    const seen: string[] = []
    const outcome = await pollUntil({ read: async () => values.shift()!, done: (v) => v === 'OPEN',
      onValue: (v) => seen.push(v), timeoutMs: 60_000, now: c.now, sleep: c.sleep })
    expect(outcome).toEqual({ kind: 'done', value: 'OPEN' })
    expect(seen).toEqual(['OPENING', 'OPENING', 'OPEN'])
    expect(c.waits).toEqual([1000, 1000])
  })

  it('gives up after the time, with the last value read', async () => {
    const c = clock()
    const read = vi.fn(async () => 'OPEN')
    const outcome = await pollUntil({ read, done: () => false, timeoutMs: 30_000, now: c.now, sleep: c.sleep })
    expect(outcome).toEqual({ kind: 'timeout', value: 'OPEN' })
    expect(c.now()).toBeLessThanOrEqual(30_000)
    // 1 + 1 + 2 + 2 + 3 + 5 + 5 + 5 + 5 = 29 seconds of waiting: ten reads in all.
    expect(read).toHaveBeenCalledTimes(10)
  })

  it('reads again after a failed read', async () => {
    const c = clock()
    const read = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue('PAID')
    const outcome = await pollUntil({ read, done: (v) => v === 'PAID', timeoutMs: 10_000, now: c.now,
      sleep: c.sleep })
    expect(outcome).toEqual({ kind: 'done', value: 'PAID' })
    expect(read).toHaveBeenCalledTimes(2)
  })

  it('stops when the page goes away', async () => {
    const c = clock()
    const controller = new AbortController()
    const read = vi.fn(async () => {
      controller.abort()
      return 'OPEN'
    })
    const onValue = vi.fn()
    const outcome = await pollUntil({ read, done: () => false, onValue, timeoutMs: 10_000, now: c.now,
      sleep: c.sleep, signal: controller.signal })
    expect(outcome).toEqual({ kind: 'aborted' })
    expect(onValue).not.toHaveBeenCalled()
  })

  it('does not wait for a read that hangs past the deadline', async () => {
    vi.useFakeTimers()
    try {
      const read = vi.fn(() => new Promise<string>(() => {}))
      const outcome = pollUntil({ read, done: () => true, timeoutMs: 5_000 })
      await vi.advanceTimersByTimeAsync(5_000)
      await expect(outcome).resolves.toEqual({ kind: 'timeout', value: undefined })
      expect(read).toHaveBeenCalledTimes(1)
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('stops at once when the page goes away during a read that hangs', async () => {
    vi.useFakeTimers()
    try {
      const controller = new AbortController()
      const outcome = pollUntil({ read: () => new Promise<string>(() => {}), done: () => true, timeoutMs: 60_000,
        signal: controller.signal })
      await vi.advanceTimersByTimeAsync(100)
      controller.abort()
      await expect(outcome).resolves.toEqual({ kind: 'aborted' })
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('leaves no timer behind once its wait is over', async () => {
    vi.useFakeTimers()
    try {
      const values = ['OPENING', 'OPEN']
      const outcome = pollUntil({ read: async () => values.shift()!, done: (v) => v === 'OPEN', timeoutMs: 60_000,
        signal: new AbortController().signal })
      await vi.advanceTimersByTimeAsync(1_000)
      await expect(outcome).resolves.toEqual({ kind: 'done', value: 'OPEN' })
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })
})
