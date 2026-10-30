export type Language = 'en' | 'zh' | 'ja'

/** What a page may show of the brand (`__QB_BRAND__` in the SPAs). */
export interface PublicBrand {
  name: string
  shortName: string
  description: Record<Language, string>
  supportEmail: string
  siteUrl: string
}

export interface Brand extends PublicBrand {
  id: string
  dir: string
  themeColor: string
  backgroundColor: string
}

export const BRANDS_DIR: string
export const DEFAULT_BRAND: string
export const LANGUAGES: readonly Language[]
export const FILES: readonly string[]
export function brandIds(): string[]
export function chosenBrand(env?: Record<string, string | undefined>): string
export function problems(id: string, profile: Record<string, unknown>, exists?: (path: string) => boolean): string[]
export function loadBrand(id?: string): Brand
export function publicBrand(brand: Brand): PublicBrand
