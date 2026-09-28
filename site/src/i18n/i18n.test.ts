import { describe, expect, it } from 'vitest'
import en from './en.json'
import ja from './ja.json'
import zh from './zh.json'

function keys(value: unknown, prefix = ''): string[] {
  if (typeof value !== 'object' || value === null) return [prefix]
  return Object.entries(value).flatMap(([k, v]) => keys(v, prefix ? `${prefix}.${k}` : k))
}

/** Chinese and Japanese have one plural form: English's `_one` / `_other` pair is `_other` there. */
function normalized(value: unknown): string[] {
  return [...new Set(keys(value).map((k) => k.replace(/_(one|other)$/, '')))].sort()
}

describe('interface texts', () => {
  it('exist in all three languages', () => {
    expect(normalized(zh)).toEqual(normalized(en))
    expect(normalized(ja)).toEqual(normalized(en))
  })

  it('are never empty', () => {
    for (const bundle of [en, zh, ja]) {
      for (const key of keys(bundle)) {
        const value = key.split('.').reduce<unknown>((o, k) => (o as Record<string, unknown>)[k], bundle)
        expect(String(value).trim(), key).not.toBe('')
      }
    }
  })
})
