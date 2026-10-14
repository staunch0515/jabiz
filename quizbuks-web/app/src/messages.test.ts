import { describe, expect, it } from 'vitest'
import { APP } from './messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from './qb/messageKeys'

describe('the app texts', () => {
  it('have the same keys in every language, none of them empty', () => {
    const english = keyPaths(bundle(APP, 'en'))
    expect(english.length).toBeGreaterThan(10)
    for (const lng of LANGUAGES) {
      expect(keyPaths(bundle(APP, lng)), lng).toEqual(english)
      expect(emptyTexts(APP, lng), lng).toEqual([])
    }
  })
})
