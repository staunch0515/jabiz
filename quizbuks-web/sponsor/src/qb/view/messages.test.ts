import { describe, expect, it } from 'vitest'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../messageKeys'
import { QBVIEW } from './messages'

describe('the texts of what takers see (Q34b)', () => {
  it('have the same keys in every language, none of them empty', () => {
    const english = keyPaths(bundle(QBVIEW, 'en'))
    expect(english.length).toBeGreaterThan(10)
    for (const lng of LANGUAGES) {
      expect(keyPaths(bundle(QBVIEW, lng)), lng).toEqual(english)
      expect(emptyTexts(QBVIEW, lng), lng).toEqual([])
    }
  })
})
