import type { TFunction } from 'i18next'
import en from '../i18n/en.json'

/**
 * Readable text for a code the site has no translation for (design section 16, item 2): database dictionaries can
 * grow without a release, so a new value is shown rather than hidden. `DIGITAL_ZINE` becomes "Digital zine".
 */
export function humanize(code: string): string {
  const words = code.replace(/[_-]+/g, ' ').trim().toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

/** The name of a dictionary value (media type, activity type, age group) in the interface language. */
export function dictLabel(t: TFunction, dictionary: 'mediaType' | 'activityType' | 'ageGroup', code: string | null | undefined): string {
  if (!code) return ''
  return t(`dict.${dictionary}.${code}`, { defaultValue: humanize(code) })
}

/**
 * The dictionary values the site has names for, offered as filters (design section 16, item 2). A value editors add
 * later is still shown on the content that uses it; it becomes a filter once the site names it.
 */
export const MEDIA_TYPES = Object.keys(en.dict.mediaType)
export const ACTIVITY_TYPES = Object.keys(en.dict.activityType)
export const AGE_GROUPS = Object.keys(en.dict.ageGroup)
