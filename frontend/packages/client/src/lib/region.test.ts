import { afterEach, describe, expect, it, vi } from 'vitest'
import { clientRegion } from './region'

describe('clientRegion', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('asks without a cache or credentials and answers what the server says', async () => {
    const fetch = vi.fn(async () => new Response(JSON.stringify({ country: 'CN', mainlandChina: true, known: true }),
      { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetch)
    expect(await clientRegion()).toEqual({ country: 'CN', mainlandChina: true, known: true })
    expect(fetch).toHaveBeenCalledWith('/api/client/region', { cache: 'no-store', credentials: 'omit' })
  })

  it('is unknown when the server cannot be asked', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 503 })))
    expect(await clientRegion()).toMatchObject({ known: false, mainlandChina: false })
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('offline') }))
    expect(await clientRegion()).toMatchObject({ known: false, mainlandChina: false })
  })
})
