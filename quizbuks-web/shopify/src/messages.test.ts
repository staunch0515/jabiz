import { describe, expect, it } from 'vitest'
import { messages } from './messages'

function keys(value: unknown, prefix = ''): string[] {
  if (typeof value !== 'object' || value === null) return [prefix]
  return Object.entries(value).flatMap(([key, inner]) => keys(inner, prefix ? `${prefix}.${key}` : key))
}

describe('the embedded pages\' texts', () => {
  it('has every text in every language, none empty', () => {
    const en = keys(messages.en).sort()
    for (const language of ['zh', 'ja'] as const) {
      expect(keys(messages[language]).sort()).toEqual(en)
    }
    for (const language of ['en', 'zh', 'ja'] as const) {
      for (const key of keys(messages[language])) {
        const text = key.split('.').reduce<unknown>((at, part) => (at as Record<string, unknown>)[part],
          messages[language])
        expect(String(text).trim(), `${language}:${key}`).not.toBe('')
      }
    }
  })
})
