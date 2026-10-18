import { describe, expect, it } from 'vitest'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { analyticsMessages } from './messages'

describe('the analytics texts', () => {
  it('have the same keys and placeholders in every language, none empty, and name no currency but Kudos', () => {
    const english = keyPaths(analyticsMessages.en)
    expect(english.length).toBeGreaterThan(50)
    const placeholders = (lng: 'en' | 'zh' | 'ja') => english.map((key) => {
      const text = key.split('.').reduce<unknown>((at, part) => (at as Record<string, unknown>)[part],
        analyticsMessages[lng]) as string
      return [key, [...text.matchAll(/\{\{(\w+)\}\}/g)].map((m) => m[1]).sort()]
    })
    for (const lng of LANGUAGES) {
      expect(keyPaths(analyticsMessages[lng]), lng).toEqual(english)
      expect(placeholders(lng), lng).toEqual(placeholders('en'))
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
      expect(JSON.stringify(analyticsMessages[lng]), lng).not.toMatch(/JPY|yen|円|日元|USD|\$/i)
    }
  })
})
