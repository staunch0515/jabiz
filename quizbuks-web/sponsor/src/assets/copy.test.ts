import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { copiedAssetId, sameValue, useAssetCopy } from './copy'

describe('a place copied from an asset (F-C1b)', () => {
  it('names the asset only while the place still has what was copied', () => {
    expect(copiedAssetId(null, 'x')).toBeNull()
    expect(copiedAssetId({ assetId: 'a1', value: 'Welcome' }, 'Welcome')).toBe('a1')
    expect(copiedAssetId({ assetId: 'a1', value: 'Welcome' }, 'Welcome!')).toBeNull()
    const prize = { method: 'DIGITAL_PRIZE', description: 'A card', delivery: 'POOL', days: '' }
    expect(copiedAssetId({ assetId: 'p1', value: prize }, { ...prize })).toBe('p1')
    expect(copiedAssetId({ assetId: 'p1', value: prize }, { ...prize, days: '3' })).toBeNull()
    expect(sameValue([1, { a: null }], [1, { a: null }])).toBe(true)
    expect(sameValue([1], { 0: 1 })).toBe(false)
  })

  it('forgets the copy once saved, so that a later save does not record it anew', () => {
    const { result } = renderHook(() => useAssetCopy<string | null>())
    expect(result.current.idFor('f1')).toBeNull()
    act(() => result.current.chose('a1', 'f1'))
    expect(result.current.idFor('f1')).toBe('a1')
    expect(result.current.idFor('f2')).toBeNull()
    act(() => result.current.saved())
    expect(result.current.idFor('f1')).toBeNull()
  })
})
