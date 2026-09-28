import type { Locale, LocalizedText } from '../api/public-queries'

export const LOCALES: readonly Locale[] = ['en', 'zh', 'ja']

export function isLocale(value: string | undefined): value is Locale {
  return value !== undefined && (LOCALES as readonly string[]).includes(value)
}

/** A text in the language asked for, or in the one it falls back to: English, then any language that has it. */
export interface Picked {
  text: string
  /** The language of `text`. */
  lang: Locale
  fallback: boolean
}

export function pick(value: LocalizedText | null | undefined, locale: Locale): Picked | null {
  if (!value) return null
  const order: Locale[] = [locale, 'en', ...LOCALES.filter((l) => l !== locale && l !== 'en')]
  for (const lang of order) {
    const text = value[lang]
    if (text && text.trim() !== '') return { text, lang, fallback: lang !== locale }
  }
  return null
}

/** The plain string, for attributes (alt, title) where no lang attribute can be set on the fallback. */
export function pickText(value: LocalizedText | null | undefined, locale: Locale): string {
  return pick(value, locale)?.text ?? ''
}

/** The language the browser prefers among ours, English when none matches (design section 9.1). */
export function preferredLocale(languages: readonly string[]): Locale {
  for (const language of languages) {
    const primary = language.toLowerCase().split('-')[0]
    if (isLocale(primary)) return primary
  }
  return 'en'
}
