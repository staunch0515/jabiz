import { changeLanguage, languages, LANGUAGE_NAMES, type Language } from '@jabiz/client'
import { AUTH_NAMESPACE, Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'

/**
 * The interface language, as the sign-in pages offer it (the platform remembers the choice and sends it with every
 * request, so the server's messages follow). Nothing when the application has one language.
 */
export function LanguageSelect({ className }: { className?: string }) {
  const { t, i18n } = useTranslation(AUTH_NAMESPACE)
  if (languages.length < 2) return null
  return (
    <Select value={i18n.language} onValueChange={(lang) => void changeLanguage(lang as Language)}>
      <SelectTrigger size="sm" aria-label={t('common.language')} className={className} data-testid="language-switch">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {languages.map((lang) => (
          <SelectItem key={lang} value={lang} lang={lang}>
            {LANGUAGE_NAMES[lang]}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}
