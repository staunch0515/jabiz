import { describe, expect, it } from 'vitest'
import { messages } from './messages'

/** Every key of a message tree, with its text. */
function entries(tree: Record<string, unknown>, prefix = ''): [string, unknown][] {
  return Object.entries(tree).flatMap(([key, value]) =>
    typeof value === 'object' && value !== null
      ? entries(value as Record<string, unknown>, `${prefix}${key}.`)
      : [[`${prefix}${key}`, value] as [string, unknown]])
}

describe('messages', () => {
  const english = entries(messages.en).map(([key]) => key).sort()

  it.each(['zh', 'ja'] as const)('%s has exactly the keys of en', (language) => {
    expect(entries(messages[language]).map(([key]) => key).sort()).toEqual(english)
  })

  it.each(['en', 'zh', 'ja'] as const)('every text of %s is filled in, with the same placeholders as en', (language) => {
    const en = new Map(entries(messages.en))
    for (const [key, text] of entries(messages[language])) {
      expect(typeof text === 'string' && text.trim() !== '', key).toBe(true)
      const placeholders = (value: unknown) => [...String(value).matchAll(/\{\{(\w+)\}\}/g)].map((m) => m[1]).sort()
      expect(placeholders(text), key).toEqual(placeholders(en.get(key)))
    }
  })
})
