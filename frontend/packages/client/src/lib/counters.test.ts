import { afterEach, describe, expect, it, vi } from 'vitest'
import { countPublic } from './counters'

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('countPublic (decision D65)', () => {
  it('sends a beacon to the counter, the key encoded', () => {
    const beacon = vi.fn(() => true)
    vi.stubGlobal('navigator', { ...navigator, sendBeacon: beacon })
    const fetcher = vi.spyOn(globalThis, 'fetch')
    countPublic('shop.product-view', 'a b/c')
    expect(beacon).toHaveBeenCalledWith(`${window.location.origin}/api/public/counters/shop.product-view/a%20b%2Fc`)
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('falls back to a keepalive fetch, and ignores its failure', async () => {
    vi.stubGlobal('navigator', { ...navigator, sendBeacon: vi.fn(() => false) })
    const fetcher = vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('offline'))
    countPublic('shop.product-view', 'p1')
    expect(fetcher).toHaveBeenCalledWith(`${window.location.origin}/api/public/counters/shop.product-view/p1`,
      { method: 'POST', keepalive: true, credentials: 'omit' })
    await Promise.resolve()
  })

  it('sends nothing without a counter or key', () => {
    const beacon = vi.fn(() => true)
    vi.stubGlobal('navigator', { ...navigator, sendBeacon: beacon })
    countPublic('shop.product-view', '')
    countPublic('', 'p1')
    expect(beacon).not.toHaveBeenCalled()
  })
})
