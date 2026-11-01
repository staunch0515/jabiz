import { Checkbox, Input, Label, RadioGroup, RadioGroupItem, Textarea } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import type { VersionRow } from '../content/api'
import { SPONSOR } from '../messages'
import type { ContentLanguage } from '../qb/api'
import { QB } from '../qb/messages'
import type { PublicationForm, PublicationTextField, RewardMethod } from './api'

const NAME_LENGTH = 100
const DESCRIPTION_LENGTH = 500

/**
 * The languages a publication offers (Q73b, docs/quizbuks/plans/Q73-content-languages.md §3): some of its version's,
 * one of them the main one (the feed's and a link's default), and its own texts in each other one, the base's shown
 * beside them. One publication, one budget: takers of every language answer the same quiz. Nothing shows for a
 * version of one language. Every check is the server's; its problems come back as the save's
 * (`languages`, `translations.<language>.<field>`).
 */
export function LanguagesFields({ id, form, set, version, method, locked, problemOf }: {
  id: string
  form: PublicationForm
  set: <K extends keyof PublicationForm>(field: K, value: PublicationForm[K]) => void
  version: VersionRow | null | undefined
  method: RewardMethod
  locked: boolean
  problemOf: (field: string) => string | undefined
}) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  if (!version || version.languages.length < 2) return null
  const base = version.language
  // None chosen: the version's base only, as before.
  const offered: ContentLanguage[] = form.languages.length > 0 ? form.languages : [base]
  const choose = (language: ContentLanguage, on: boolean) => {
    const next = on ? [...offered.filter((l) => l !== language), language] : offered.filter((l) => l !== language)
    // At least one; the main one stays first when kept.
    if (next.length === 0) return
    set('languages', next)
  }
  const makeMain = (language: ContentLanguage) =>
    set('languages', [language, ...offered.filter((l) => l !== language)])
  const typed = (language: ContentLanguage, field: PublicationTextField, value: string) =>
    set('translations', { ...form.translations, [language]: { ...form.translations[language], [field]: value } })
  // The base texts that are translated: the name always, a description when the reward or referral has one.
  const sources: [PublicationTextField, string][] = ([
    ['name', form.name],
    ['redeemDescription', method === 'OFFLINE_REDEEM' ? form.redeemDescription : ''],
    ['digitalDescription', method === 'DIGITAL_PRIZE' ? form.digitalDescription : ''],
    ['referralDescription', form.referralEnabled ? form.referralDescription : ''],
  ] as [PublicationTextField, string][]).filter(([, text]) => text.trim() !== '')
  return (
    <fieldset className="flex flex-col gap-4 rounded-lg border p-4" data-testid="publication-languages">
      <legend className="px-1 text-sm font-medium">{t('publishing.languages.title')}</legend>
      <p className="text-muted-foreground text-sm">{t('publishing.languages.hint')}</p>
      <div className="flex flex-wrap gap-4">
        {version.languages.map((language) => (
          <div key={language} className="flex items-center gap-2">
            <Checkbox id={`${id}-lang-${language}`} checked={offered.includes(language)} disabled={locked}
              data-testid={`publication-language-${language}`}
              onCheckedChange={(checked) => choose(language, checked === true)} />
            <Label htmlFor={`${id}-lang-${language}`} className="font-normal">
              {tQb(`languages.${language}`)}
              {language === base && <span className="text-muted-foreground"> ({t('publishing.languages.base')})</span>}
            </Label>
          </div>
        ))}
      </div>
      {problemOf('languages') && <p className="text-destructive text-sm">{problemOf('languages')}</p>}
      {offered.length > 1 && (
        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-medium">{t('publishing.languages.main')}</legend>
          <p className="text-muted-foreground text-xs">{t('publishing.languages.mainHint')}</p>
          <RadioGroup value={offered[0]} disabled={locked} onValueChange={(value) => makeMain(value as ContentLanguage)}>
            {offered.map((language) => (
              <div key={language} className="flex items-center gap-2">
                <RadioGroupItem id={`${id}-main-${language}`} value={language}
                  data-testid={`publication-language-main-${language}`} />
                <Label htmlFor={`${id}-main-${language}`} className="font-normal">{tQb(`languages.${language}`)}</Label>
              </div>
            ))}
          </RadioGroup>
        </fieldset>
      )}
      {offered.filter((language) => language !== base).map((language) => (
        <fieldset key={language} className="flex flex-col gap-3" data-testid={`publication-texts-${language}`}>
          <legend className="text-sm font-medium">
            {t('publishing.languages.texts', { language: tQb(`languages.${language}`) })}
          </legend>
          {sources.map(([field, source]) => {
            const fieldId = `${id}-text-${language}-${field}`
            const problem = problemOf(`translations.${language}.${field}`)
            const value = form.translations[language]?.[field] ?? ''
            const long = field !== 'name'
            return (
              <div key={field} className="flex flex-col gap-1">
                <Label htmlFor={fieldId}>{t(`publishing.languages.fields.${field}`)}</Label>
                <p className="text-muted-foreground text-xs whitespace-pre-line" id={`${fieldId}-source`}>
                  {t('publishing.languages.source', { language: tQb(`languages.${base}`), text: source })}
                </p>
                {long ? (
                  <Textarea id={fieldId} value={value} rows={2} maxLength={DESCRIPTION_LENGTH} disabled={locked}
                    aria-describedby={`${fieldId}-source`} aria-invalid={problem ? true : undefined}
                    data-testid={`publication-text-${language}-${field}`}
                    onChange={(event) => typed(language, field, event.target.value)} />
                ) : (
                  <Input id={fieldId} value={value} maxLength={NAME_LENGTH} autoComplete="off" disabled={locked}
                    aria-describedby={`${fieldId}-source`} aria-invalid={problem ? true : undefined}
                    data-testid={`publication-text-${language}-${field}`}
                    onChange={(event) => typed(language, field, event.target.value)} />
                )}
                {problem && <p className="text-destructive text-sm">{problem}</p>}
              </div>
            )
          })}
        </fieldset>
      ))}
    </fieldset>
  )
}
