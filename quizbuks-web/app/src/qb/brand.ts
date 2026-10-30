/**
 * The brand profile chosen at build time (QB_BRAND, docs/quizbuks/plans/Q54-brand-profile.md): what pages show of it.
 * Its colours are the profile's brand.css, its mark the profile's mark.svg; texts name the product as `BRAND.name`,
 * never in words of their own.
 */
declare const __QB_BRAND__: {
  name: string
  shortName: string
  description: Record<'en' | 'zh' | 'ja', string>
  supportEmail: string
  siteUrl: string
}

export const BRAND = __QB_BRAND__
