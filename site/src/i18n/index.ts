import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import en from './en.json'
import ja from './ja.json'
import zh from './zh.json'

/**
 * The interface texts (design section 9.4). The language is part of every address (/en/, /zh/, /ja/), so the
 * instance is not switched globally: pages ask for the route's language (useT). Content comes in all languages from
 * the public interface and falls back separately (lib/localized.ts).
 */
export const resources = { en: { translation: en }, zh: { translation: zh }, ja: { translation: ja } } as const

void i18next.use(initReactI18next).init({
  resources,
  lng: 'en',
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
  returnNull: false,
})

export default i18next
