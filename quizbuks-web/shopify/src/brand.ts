/**
 * The brand profile chosen at build time (QB_BRAND, docs/quizbuks/plans/Q54-brand-profile.md): what pages show of it.
 * The embedded pages keep Shopify admin's look (index.css), so only the name and the icon are the brand's; texts name
 * the product as `BRAND.names.<language>`, never in words of their own.
 */
declare const __QB_BRAND__: {
  name: string
  /** What a text in each language calls the product (Q61): `name` only where one name serves every language. */
  names: Record<'en' | 'zh' | 'ja', string>
  shortName: string
  description: Record<'en' | 'zh' | 'ja', string>
  supportEmail: string
  siteUrl: string
}

export const BRAND = __QB_BRAND__
