import { describe, expect, it } from 'vitest'
import { SPONSOR } from './messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from './qb/messageKeys'

describe('the sponsor console texts', () => {
  it('have the same keys in every language, none of them empty', () => {
    const english = keyPaths(bundle(SPONSOR, 'en'))
    expect(english.length).toBeGreaterThan(10)
    for (const lng of LANGUAGES) {
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(english)
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
    }
  })
})
