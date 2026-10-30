import { i18n } from '@jabiz/client'
import { Checkbox, Label, notify } from '@jabiz/ui'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import {
  CONTENT_LANGUAGES,
  contentLanguageOf,
  DEFAULT_SETTINGS,
  saveMySettings,
  shownLanguages,
  type ContentLanguage,
  type UserSettings,
} from '../qb/api'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { SETTINGS_QUERY, useSoundSetting } from '../qb/SoundSettings'

/**
 * The languages of the quizzes the taker sees (Q59a): a switch per language, saved to the account at once. Until they
 * choose, the home follows the app's language; one stays on at least, so that the home is never empty by choice.
 */
export function QuizLanguages() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  // The same query as the sound's: one read of the settings.
  const settings = useSoundSetting()
  const shown = shownLanguages(settings.data, i18n.language)
  const following = (settings.data?.contentLanguages.length ?? 0) === 0
  const save = useMutation({
    mutationFn: (contentLanguages: ContentLanguage[]) => saveMySettings({ contentLanguages }),
    onMutate: (contentLanguages) => {
      const before = queryClient.getQueryData<UserSettings>(SETTINGS_QUERY)
      queryClient.setQueryData<UserSettings>(SETTINGS_QUERY, { ...(before ?? DEFAULT_SETTINGS), contentLanguages })
      return { before }
    },
    onError: (error, _languages, saved) => {
      queryClient.setQueryData<UserSettings>(SETTINGS_QUERY, saved?.before ?? DEFAULT_SETTINGS)
      notify.error(problemText(error))
    },
    // The home asks again for the languages now on.
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['qb', 'feed'] }),
  })
  const toggle = (language: ContentLanguage, on: boolean) => {
    const next = CONTENT_LANGUAGES.filter((code) => (code === language ? on : shown.includes(code)))
    if (next.length > 0) save.mutate(next)
  }
  return (
    <div className="flex flex-col gap-2" data-testid="quiz-languages">
      <ul className="flex flex-col gap-2">
        {CONTENT_LANGUAGES.map((language) => {
          const on = shown.includes(language)
          return (
            <li key={language} className="flex items-center gap-2">
              <Checkbox id={`quiz-language-${language}`} checked={on} data-testid={`quiz-language-${language}`}
                // The last one on stays on.
                disabled={settings.isPending || save.isPending || (on && shown.length === 1)}
                onCheckedChange={(checked) => toggle(language, checked === true)} />
              <Label htmlFor={`quiz-language-${language}`}>{tQb(`languages.${language}`)}</Label>
            </li>
          )
        })}
      </ul>
      {following && (
        <p className="text-muted-foreground text-sm" data-testid="quiz-languages-following">
          {t('account.quizLanguages.interface', { language: tQb(`languages.${contentLanguageOf(i18n.language)}`) })}
        </p>
      )}
      <p className="text-muted-foreground text-sm">{t('account.quizLanguages.hint')}</p>
    </div>
  )
}
