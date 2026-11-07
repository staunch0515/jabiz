import { describe, expect, it } from 'vitest'
import { MATERIAL_KINDS } from '../content/api'
import { ASSET_KINDS, TEXT_USAGES, type UseTarget } from './api'
import { materialKindOf, suits } from './suits'

/** The cases of the server's `AssetRulesTest` (suits): the page lists what the server would take. */
describe('which assets suit a place (AssetRules.suits)', () => {
  it('fills a material with the asset of its kind (an images material with an image), never a text or a prize', () => {
    for (const material of MATERIAL_KINDS) {
      for (const kind of ASSET_KINDS) {
        const expected = kind === (material === 'IMAGES' ? 'IMAGE' : material)
        expect(suits('MATERIAL', kind, null, material), `${material} <- ${kind}`).toBe(expected)
      }
    }
    expect(suits('MATERIAL', 'IMAGE', null, null)).toBe(false)
    expect(suits('MATERIAL', 'TEXT', 'QUIZ_INTRO', null)).toBe(false)
  })

  it('fills the images with an image only', () => {
    for (const place of ['QUESTION_IMAGE', 'OPTION_IMAGE', 'QUIZ_COVER', 'PUBLICATION_COVER'] as UseTarget[]) {
      for (const kind of ASSET_KINDS) expect(suits(place, kind, null), `${place} <- ${kind}`).toBe(kind === 'IMAGE')
    }
  })

  it('fills an introduction or a description with a text of the matching use', () => {
    const uses: [UseTarget, string][] = [['QUIZ_INTRO', 'QUIZ_INTRO'], ['PUBLICATION_REDEEM', 'REDEEM_DESCRIPTION'],
      ['PUBLICATION_DIGITAL', 'DIGITAL_DESCRIPTION'], ['PUBLICATION_REFERRAL', 'REFERRAL_DESCRIPTION']]
    for (const [place, usage] of uses) {
      for (const other of TEXT_USAGES) expect(suits(place, 'TEXT', other), `${place} <- ${other}`).toBe(other === usage)
      expect(suits(place, 'TEXT', null)).toBe(false)
      expect(suits(place, 'ARTICLE', null)).toBe(false)
    }
  })

  it('fills a publication prize with a prize card only, and nothing else anywhere', () => {
    for (const kind of ASSET_KINDS) expect(suits('PUBLICATION_PRIZE', kind, null)).toBe(kind === 'PRIZE')
    expect(suits('ELSEWHERE' as UseTarget, 'IMAGE', null)).toBe(false)
  })

  it('makes the material of an asset chosen for a new one', () => {
    expect(materialKindOf('IMAGE')).toBe('IMAGES')
    expect(materialKindOf('VIDEO_LINK')).toBe('VIDEO_LINK')
    expect(materialKindOf('TEXT')).toBeNull()
    expect(materialKindOf('PRIZE')).toBeNull()
  })
})
