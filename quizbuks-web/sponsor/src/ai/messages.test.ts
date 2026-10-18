import { describe, expect, it } from 'vitest'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { aiMessages } from './messages'

describe('the AI texts', () => {
  it('have the same keys and placeholders in every language, none empty, and name no currency', () => {
    const english = keyPaths(aiMessages.en)
    expect(english.length).toBeGreaterThan(50)
    const placeholders = (lng: 'en' | 'zh' | 'ja') => english.map((key) => {
      const text = key.split('.').reduce<unknown>((at, part) => (at as Record<string, unknown>)[part],
        aiMessages[lng]) as string
      return [key, [...text.matchAll(/\{\{(\w+)\}\}/g)].map((m) => m[1]).sort()]
    })
    for (const lng of LANGUAGES) {
      expect(keyPaths(aiMessages[lng]), lng).toEqual(english)
      expect(placeholders(lng), lng).toEqual(placeholders('en'))
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
      expect(JSON.stringify(aiMessages[lng]), lng).not.toMatch(/JPY|yen|円|日元|USD|\$/i)
    }
  })
})
