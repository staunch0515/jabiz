import type { FieldMeta } from './types'

/**
 * The multilingual text kind jabiz.i18n-text (docs/design/16-content-authoring.md section 1): a value is
 * {language: text}. Normalization and constraints mirror the core's I18nTextSupport, so the client reports the
 * server's codes (the shared cases in spec/validation-cases.json run through both).
 */
export const I18N_TEXT = 'jabiz.i18n-text'

/** The platform's languages in their order, when the export does not list them. */
export const PLATFORM_LANGUAGES = ['zh', 'ja', 'en']

export type Texts = Record<string, string>

export interface I18nTextParams {
  format: 'plain' | 'markdown'
  multiline: boolean
  maxLength?: number
  required: string[]
  locales: string[]
}

export function isI18nText(field: FieldMeta): boolean {
  return field.type === 'custom' && field.kindId === I18N_TEXT
}

/** The kind's parameters from the exported field. */
export function i18nParams(field: FieldMeta): I18nTextParams {
  const f = field as unknown as Record<string, unknown>
  return {
    format: f.format === 'markdown' ? 'markdown' : 'plain',
    multiline: f.multiline === true || f.format === 'markdown',
    maxLength: typeof f.maxLength === 'number' ? f.maxLength : undefined,
    required: Array.isArray(f.requiredLanguages) ? (f.requiredLanguages as string[]) : [],
    locales: Array.isArray(f.locales) ? (f.locales as string[]) : PLATFORM_LANGUAGES,
  }
}

export class InvalidTexts extends Error {}

/**
 * Input as the server converts it: an object of texts in supported languages; blank texts are dropped and no text at
 * all is null. Throws InvalidTexts where the server reports INVALID_VALUE.
 */
export function normalizeTexts(raw: unknown, locales: string[]): Texts | null {
  if (raw === null || raw === undefined) return null
  if (typeof raw !== 'object' || Array.isArray(raw)) throw new InvalidTexts()
  const texts: Texts = {}
  for (const [language, value] of Object.entries(raw as Record<string, unknown>)) {
    if (!locales.includes(language)) throw new InvalidTexts()
    if (value === null || value === undefined) continue
    if (typeof value !== 'string') throw new InvalidTexts()
    if (value.trim() !== '') texts[language] = value
  }
  const ordered: Texts = {}
  for (const language of locales) if (language in texts) ordered[language] = texts[language]
  return Object.keys(ordered).length > 0 ? ordered : null
}

function codePoints(s: string): number {
  return [...s].length
}

/** The first constraint the texts break, as the server's validate(): too long (in language order), then missing. */
export function textsViolation(
  params: I18nTextParams,
  texts: Texts,
): { ruleCode: 'TOO_LONG' | 'TRANSLATION_REQUIRED'; params: Record<string, unknown> } | null {
  if (params.maxLength !== undefined) {
    for (const language of params.locales) {
      const text = texts[language]
      if (text !== undefined && codePoints(text) > params.maxLength) {
        return { ruleCode: 'TOO_LONG', params: { lang: language, max: params.maxLength } }
      }
    }
  }
  for (const language of params.required) {
    if (!(language in texts)) return { ruleCode: 'TRANSLATION_REQUIRED', params: { lang: language } }
  }
  return null
}

export interface PickedText {
  text: string
  /** The language of the text. */
  lang: string
  /** Whether it is not in the interface language: the element should then carry lang (screen readers). */
  fallback: boolean
}

/** The text to show: interface language, then the default language, then any language that has one. */
export function pickText(
  value: unknown,
  uiLanguage: string,
  defaultLanguage: string | undefined,
  locales: string[] = PLATFORM_LANGUAGES,
): PickedText | null {
  if (value === null || value === undefined || typeof value !== 'object' || Array.isArray(value)) return null
  const texts = value as Record<string, unknown>
  const has = (l: string | undefined): l is string => !!l && typeof texts[l] === 'string' && texts[l] !== ''
  const ui = uiLanguage.split('-')[0]
  const order = [ui, defaultLanguage, ...locales, ...Object.keys(texts)]
  const lang = order.find(has)
  return lang ? { text: texts[lang] as string, lang, fallback: lang !== ui } : null
}

/** The form's texts as sent: no blank texts, nothing at all as null. */
export function textsToWire(value: unknown): Texts | null | undefined {
  if (value === undefined) return undefined
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return value === null ? null : undefined
  const texts: Texts = {}
  for (const [language, text] of Object.entries(value as Record<string, unknown>)) {
    if (typeof text === 'string' && text.trim() !== '') texts[language] = text
  }
  return Object.keys(texts).length > 0 ? texts : null
}
