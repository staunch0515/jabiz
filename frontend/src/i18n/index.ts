import { addMessages, changeLanguage, i18n, languages, type Language } from '@jabiz/client'
import { resources } from './resources'

// The interface language and its i18next instance live in @jabiz/client (decision D34); the admin frontend adds its
// own texts as the default namespace.
addMessages('translation', {
  zh: resources.zh.translation,
  ja: resources.ja.translation,
  en: resources.en.translation,
})

export { changeLanguage, languages, type Language }
export default i18n
