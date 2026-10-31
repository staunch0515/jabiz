import type { AdminBrand } from '@jabiz/admin'
import { BRAND_PROFILES } from './brands.generated'

/*
 * QuizBuks's name and marks in the admin and operator back offices (Q55b; platform phase 17v), from the brand profile
 * QB_BRAND chooses (quizbuks-web/brands/<brand>, docs/quizbuks/plans/Q54-brand-profile.md). The profiles come in
 * through brands.generated.ts (quizbuks-web/brands/admin.mjs): the platform's frontend serves nothing outside the
 * extension's directory. Gradle passes the choice as VITE_QB_BRAND; the extension's own checks take QuizBuks.
 */

/** A profile as the admin frontend's brand; an unknown one stops the pages at once, naming it. */
export function adminBrand(id: string = import.meta.env.VITE_QB_BRAND?.trim() || 'quizbuks'): AdminBrand {
  const profile = BRAND_PROFILES[id]
  if (!profile) throw new Error(`QB_BRAND=${id}: no brand profile (there are: ${Object.keys(BRAND_PROFILES).join(', ')})`)
  return { name: profile.names, logo: profile.logo, favicon: profile.favicon }
}

/** The brand this build is, and its name in each language of the extension's texts (Q61). */
export const BRAND = adminBrand()
export const BRAND_NAMES = BRAND.name as Record<'en' | 'zh' | 'ja', string>
