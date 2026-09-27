import { describe, expect, it } from 'vitest'
import type { FieldMeta } from './types'
import { i18nParams, InvalidTexts, isI18nText, normalizeTexts, pickText, textsToWire, textsViolation } from './i18nText'
import { controlOf, formatValue, toFormValue, toWireValue } from './kinds'
import i18n from '../i18n'

const base = { immutable: false, required: false, generated: false, systemManaged: false, sensitive: false, processOnly: false, operators: [], rules: [] }
const title = {
  ...base,
  name: 'title',
  type: 'custom',
  kindId: 'jabiz.i18n-text',
  format: 'markdown',
  multiline: true,
  maxLength: 5,
  requiredLanguages: ['en'],
  locales: ['zh', 'ja', 'en'],
} as unknown as FieldMeta

describe('multilingual texts', () => {
  it('are recognized with their parameters', () => {
    expect(isI18nText(title)).toBe(true)
    expect(controlOf(title)).toBe('i18n')
    expect(i18nParams(title)).toEqual({ format: 'markdown', multiline: true, maxLength: 5, required: ['en'], locales: ['zh', 'ja', 'en'] })
    expect(isI18nText({ ...base, name: 'cell', type: 'custom', kindId: 'geo.h3' } as FieldMeta)).toBe(false)
  })

  it('normalize like the server: platform order, no blank texts, nothing is null', () => {
    expect(normalizeTexts({ en: 'Hi', ja: ' ', zh: '你好' }, ['zh', 'ja', 'en'])).toEqual({ zh: '你好', en: 'Hi' })
    expect(Object.keys(normalizeTexts({ en: 'Hi', zh: '你好' }, ['zh', 'ja', 'en'])!)).toEqual(['zh', 'en'])
    expect(normalizeTexts({ en: '  ' }, ['en'])).toBeNull()
    expect(() => normalizeTexts({ fr: 'Salut' }, ['zh', 'en'])).toThrow(InvalidTexts)
    expect(() => normalizeTexts({ en: 1 }, ['en'])).toThrow(InvalidTexts)
    expect(() => normalizeTexts('Hi', ['en'])).toThrow(InvalidTexts)
    expect(() => normalizeTexts(['Hi'], ['en'])).toThrow(InvalidTexts)
  })

  it('report too long texts before missing languages', () => {
    const params = i18nParams(title)
    expect(textsViolation(params, { ja: '123456', en: '1234567' })).toEqual({ ruleCode: 'TOO_LONG', params: { lang: 'ja', max: 5 } })
    expect(textsViolation(params, { zh: '一二' })).toEqual({ ruleCode: 'TRANSLATION_REQUIRED', params: { lang: 'en' } })
    expect(textsViolation(params, { en: '😀😀😀😀😀' })).toBeNull()
  })

  it('show the interface language, then the default, then any, marking a fallback', () => {
    expect(pickText({ zh: '你好', en: 'Hi' }, 'zh', 'en')).toEqual({ text: '你好', lang: 'zh', fallback: false })
    expect(pickText({ zh: '你好', en: 'Hi' }, 'ja', 'en')).toEqual({ text: 'Hi', lang: 'en', fallback: true })
    expect(pickText({ ja: 'こんにちは' }, 'zh', 'en')).toEqual({ text: 'こんにちは', lang: 'ja', fallback: true })
    expect(pickText({ en: 'Hi' }, 'en-US', undefined)).toEqual({ text: 'Hi', lang: 'en', fallback: false })
    expect(pickText({}, 'en', 'en')).toBeNull()
    expect(pickText(null, 'en', 'en')).toBeNull()
  })

  it('go to the server without blank texts and come back as a copy', async () => {
    expect(textsToWire({ zh: '', en: 'Hi' })).toEqual({ en: 'Hi' })
    expect(textsToWire({ zh: ' ' })).toBeNull()
    expect(textsToWire(undefined)).toBeUndefined()
    expect(toWireValue(title, { en: 'Hi', ja: '' })).toEqual({ en: 'Hi' })
    const stored = { en: 'Hi' }
    const form = toFormValue(title, stored)
    expect(form).toEqual(stored)
    expect(form).not.toBe(stored)
    await i18n.changeLanguage('en')
    expect(formatValue(title, { zh: '你好', en: 'Hi' }, {}, i18n.t, 'en')).toBe('Hi')
  })
})
