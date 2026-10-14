import { describe, expect, it } from 'vitest'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { walletMessages } from './messages'

describe('the wallet texts', () => {
  it('have the same keys in every language, none of them empty, and name no currency but Kudos', () => {
    const english = keyPaths(walletMessages.en)
    expect(english.length).toBeGreaterThan(10)
    for (const lng of LANGUAGES) {
      expect(keyPaths(walletMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
      expect(JSON.stringify(walletMessages[lng]), lng).not.toMatch(/JPY|yen|円|日元|USD|\$/i)
    }
  })
})
