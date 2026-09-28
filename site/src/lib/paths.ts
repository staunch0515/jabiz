import type { Locale } from '../api/public-queries'

export type Section = 'people' | 'themes' | 'stories' | 'method' | 'resources' | 'about' | 'map' | 'search'

/** The address of a page in a language (design section 9.1); the language is always the first segment. */
export function path(locale: Locale, section?: Section, slug?: string | null): string {
  let result = `/${locale}/`
  if (section) result += section
  if (section && slug) result += `/${encodeURIComponent(slug)}`
  return result
}
