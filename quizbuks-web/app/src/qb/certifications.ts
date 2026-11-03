import type { TFunction } from 'i18next'
import { pickText, type I18nText } from './legal'

/**
 * Who a certification belongs to and what it is called (Q88a, docs/quizbuks/plans/Q88-sponsor-certifications-entry-fee.md,
 * D-Q88-2): the catalog's certifications are keyed by bare words and named by the texts `certification.names.<key>`
 * (namespace `qb`); a sponsor's own is keyed `sp:<id>` and named by the row the templates give along with the key
 * (`certificationNames`, a text by language). The same file in both SPAs.
 */

/** The key prefix of a sponsor's own certification (`SponsorCertifications.PREFIX`). */
export const SPONSOR_CERTIFICATION_PREFIX = 'sp:'

export const isSponsorCertification = (key: string | null | undefined): boolean =>
  typeof key === 'string' && key.startsWith(SPONSOR_CERTIFICATION_PREFIX)

/** A certification's names as a template gives them: a JSON object text, an object, or nothing. */
export function certificationNamesOf(value: unknown): I18nText | null {
  let raw: unknown = value
  if (typeof raw === 'string') {
    try {
      raw = JSON.parse(raw)
    } catch {
      return null
    }
  }
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const names: I18nText = {}
  for (const [language, text] of Object.entries(raw as Record<string, unknown>)) {
    if (typeof text === 'string' && text.trim()) names[language] = text
  }
  return Object.keys(names).length > 0 ? names : null
}

/**
 * The name of the certification `key` in the reader's language: a sponsor's from its `names` (the key when they are
 * missing), the catalog's from the texts (`tQb`, namespace `qb`).
 */
export function certificationName(key: string, names: I18nText | null | undefined, language: string,
  tQb: TFunction): string {
  if (isSponsorCertification(key)) {
    const picked = pickText(names, language).text
    return picked || key
  }
  return tQb(`certification.names.${key}`, { defaultValue: key })
}
