import { useTranslation } from 'react-i18next'
import type { Language } from '../i18n/resources'
import type { AdminBrand } from './api'

/** The brand's name in `language`: its own, else the first it gives; null without a brand. */
export function brandName(brand: AdminBrand | undefined, language: string): string | null {
  if (!brand) return null
  if (typeof brand.name === 'string') return brand.name.trim() || null
  const own = brand.name[language as Language]?.trim()
  if (own) return own
  return Object.values(brand.name).map((value) => value?.trim()).find((value) => !!value) ?? null
}

/** The name the frontend goes by: the application's brand, else the platform's (`app.title`). */
export function useBrandName(brand: AdminBrand | undefined): string {
  const { t, i18n } = useTranslation()
  return brandName(brand, i18n.language) ?? t('app.title')
}

/**
 * The browser tab's title and icon from the brand (the page's own `<title>` and `<link rel="icon">` until then); in
 * `language`, followed again when the language changes.
 */
export function applyBrandHead(brand: AdminBrand | undefined, language: string, doc: Document = document) {
  const name = brandName(brand, language)
  if (name) doc.title = name
  if (brand?.favicon) {
    let link = doc.querySelector<HTMLLinkElement>('link[rel~="icon"]')
    if (!link) {
      link = doc.createElement('link')
      link.rel = 'icon'
      doc.head.appendChild(link)
    }
    link.href = brand.favicon
    link.removeAttribute('type')
  }
}
