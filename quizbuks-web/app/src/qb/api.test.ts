import { afterEach, describe, expect, it, vi } from 'vitest'
import { readCountries, readMyProfile } from './api'

function json(body: unknown) {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

afterEach(() => vi.restoreAllMocks())

describe('the typed calls', () => {
  it('read every country, page by page (a dataset answers at most 100 rows at once)', async () => {
    const offsets: number[] = []
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
      const request = input as Request
      const body = await request.json() as { offset: number; limit: number }
      offsets.push(body.offset)
      const count = body.offset === 0 ? 100 : 49
      return json({ items: Array.from({ length: count }, (_, i) => ({
        attributes: { code: `C${body.offset + i}`, name: { en: `Country ${body.offset + i}` } } })) })
    })
    const countries = await readCountries()
    expect(offsets).toEqual([0, 100])
    expect(countries).toHaveLength(149)
    expect(countries[120]).toEqual({ code: 'C120', name: { en: 'Country 120' } })
  })

  it('read the caller\'s own profile, or none before the first save', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(json({ items: [] }))
      .mockResolvedValueOnce(json({ items: [{ attributes: { displayName: 'Mei', countryCode: 'JP', city: null,
        preferredCurrency: 'JPY', revision: '4' } }] }))
    expect(await readMyProfile()).toBeNull()
    expect(await readMyProfile()).toEqual({ displayName: 'Mei', countryCode: 'JP', city: null,
      preferredCurrency: 'JPY', revision: 4 })
  })
})
