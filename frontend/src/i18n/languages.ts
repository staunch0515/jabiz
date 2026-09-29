import { languages as platformLanguages, type Language } from './resources'

/** How each language names itself in the switch. */
export const LANGUAGE_NAMES: Record<Language, string> = { zh: '中文', ja: '日本語', en: 'English' }

/**
 * The interface languages of the application (decision D22, item 7): a subset of the platform's, in the platform's
 * order, from `VITE_JABIZ_LANGUAGES` (comma-separated; `jabizApp { languages(...) }` sets it). All when unset; an
 * unknown or empty list stops the application, as the server's startup check does.
 */
export function enabledLanguagesOf(value: string | undefined): Language[] {
  if (value === undefined || value.trim() === '') return [...platformLanguages]
  const wanted = value.split(',').map((code) => code.trim()).filter((code) => code !== '')
  const unknown = wanted.filter((code) => !(platformLanguages as readonly string[]).includes(code))
  if (unknown.length > 0) {
    throw new Error(`VITE_JABIZ_LANGUAGES: ${unknown.join(', ')} not among the platform languages ${platformLanguages.join(', ')}`)
  }
  if (wanted.length === 0) throw new Error('VITE_JABIZ_LANGUAGES names no language')
  return platformLanguages.filter((code) => wanted.includes(code))
}

export const enabledLanguages: readonly Language[] = enabledLanguagesOf(import.meta.env.VITE_JABIZ_LANGUAGES)
