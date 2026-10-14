import { i18n } from '@jabiz/client'

/** The languages of QuizBuks (`jabizApp { languages("en", "zh", "ja") }`). */
export const LANGUAGES = ['en', 'zh', 'ja'] as const

/** The dotted key of every text in a nested message object, sorted. */
export function keyPaths(messages: unknown, prefix = ''): string[] {
  if (typeof messages !== 'object' || messages === null) return [prefix]
  return Object.entries(messages as Record<string, unknown>)
    .flatMap(([key, value]) => keyPaths(value, prefix ? `${prefix}.${key}` : key))
    .sort()
}

/** The texts of a namespace in one language, as registered with `addMessages`. */
export function bundle(namespace: string, language: string): Record<string, unknown> {
  return (i18n.getResourceBundle(language, namespace) ?? {}) as Record<string, unknown>
}

/** Texts that are empty (or only blanks) in a namespace and language, by key. */
export function emptyTexts(namespace: string, language: string): string[] {
  const texts = bundle(namespace, language)
  return keyPaths(texts).filter((key) => {
    const value = key.split('.').reduce<unknown>((at, part) => (at as Record<string, unknown>)?.[part], texts)
    return typeof value !== 'string' || value.trim() === ''
  })
}
