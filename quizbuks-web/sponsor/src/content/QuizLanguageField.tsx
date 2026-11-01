import { Label, Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@jabiz/ui'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { CONTENT_LANGUAGES, type ContentLanguage } from '../qb/api'
import { QB } from '../qb/messages'

/** The language a quiz is written in (Q59a): takers see the quizzes of the languages they choose. */
export function QuizLanguageField({ value, onChange, testId, locked = false }: {
  value: ContentLanguage
  onChange: (language: ContentLanguage) => void
  testId: string
  /** Q73a (D-Q73-6): the base language stays while the quiz has other languages. */
  locked?: boolean
}) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id}>{t('editor.language')}</Label>
      <Select value={value} disabled={locked} onValueChange={(next) => onChange(next as ContentLanguage)}>
        <SelectTrigger id={id} className="w-48" aria-describedby={`${id}-hint`} data-testid={testId}>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {CONTENT_LANGUAGES.map((language) => (
            <SelectItem key={language} value={language}>{tQb(`languages.${language}`)}</SelectItem>
          ))}
        </SelectContent>
      </Select>
      <p id={`${id}-hint`} className="text-muted-foreground text-xs">
        {locked ? t('translations.baseLocked') : t('editor.languageHint')}
      </p>
    </div>
  )
}
