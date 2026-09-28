import { createContext, useContext } from 'react'
import { useTranslation } from 'react-i18next'
import type { Locale } from '../api/public-queries'

/** The interface language of the page, from its address. */
export const LocaleContext = createContext<Locale>('en')

export function useLocale(): Locale {
  return useContext(LocaleContext)
}

/** The interface texts in the page's language. */
export function useT() {
  const locale = useLocale()
  return useTranslation('translation', { lng: locale }).t
}
