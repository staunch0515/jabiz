import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import 'dayjs/locale/ja'
import { setApiLanguage } from '../api/client'
import { languages, resources, type Language } from './resources'

const STORAGE_KEY = 'jabiz.language'

/** The remembered language, else the browser's, else Chinese. */
function initialLanguage(): Language {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY)
    if (stored && (languages as readonly string[]).includes(stored)) return stored as Language
  } catch {
    // no storage: fall through
  }
  const browser = (typeof navigator === 'undefined' ? '' : navigator.language).slice(0, 2)
  return (languages as readonly string[]).includes(browser) ? (browser as Language) : 'zh'
}

function apply(lang: Language) {
  setApiLanguage(lang)
  dayjs.locale(lang === 'zh' ? 'zh-cn' : lang)
  if (typeof document !== 'undefined') document.documentElement.lang = lang
}

const language = initialLanguage()
apply(language)

void i18n.use(initReactI18next).init({
  resources,
  lng: language,
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
})

export function changeLanguage(lang: Language) {
  apply(lang)
  try {
    window.localStorage.setItem(STORAGE_KEY, lang)
  } catch {
    // remembered for this page only
  }
  return i18n.changeLanguage(lang)
}

export { languages, type Language }
export default i18n
