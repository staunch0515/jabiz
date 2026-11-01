import { Button, Checkbox, Input, Label, RadioGroup, RadioGroupItem, Spinner, Textarea } from '@jabiz/ui'
import { useQueryClient } from '@tanstack/react-query'
import { Languages } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AI_QUERY } from '../ai/AiQuestionsPanel'
import { TranslateDialog, useAiPublicationTextsJob } from '../ai/AiTranslatePanel'
import { acceptAiPublicationTexts, requestAiPublicationTexts, type AiJob } from '../ai/api'
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
export function LanguagesFields({ id, form, set, version, method, locked, problemOf, publicationId = null }: {
  id: string
  form: PublicationForm
  set: <K extends keyof PublicationForm>(field: K, value: PublicationForm[K]) => void
  version: VersionRow | null | undefined
  method: RewardMethod
  locked: boolean
  problemOf: (field: string) => string | undefined
  /** Q73d-2: the saved draft's key; none before the first save (no AI translation then). */
  publicationId?: string | null
}) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const job = useAiPublicationTextsJob(form.quizId, publicationId).data ?? null
  const [asking, setAsking] = useState<ContentLanguage | null>(null)
  const [used, setUsed] = useState<string[]>([])
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
  // Q73d-2: the AI's suggestions of one language, each confirmed and used by the sponsor in the form; the job is
  // recorded as used once every suggestion is (the texts are saved with the publication).
  const ready: AiJob | null = job?.status === 'READY' ? job : null
  const suggestion = (language: ContentLanguage, field: PublicationTextField) =>
    ready && ready.language === language && !used.includes(`${language}.${field}`)
      ? ready.translations.find((c) => c.key === `publication.${field}`) : undefined
  const takeSuggestion = (language: ContentLanguage, field: PublicationTextField, text: string) => {
    typed(language, field, text)
    const next = [...used, `${language}.${field}`]
    setUsed(next)
    if (ready && ready.translations.every((c) => next.includes(`${ready.language}.${c.key.slice('publication.'.length)}`))) {
      void acceptAiPublicationTexts(ready.aiJobId, publicationId!, ready.translations.length)
        .finally(() => queryClient.invalidateQueries({ queryKey: AI_QUERY }))
    }
  }
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
          {publicationId && form.quizId && !locked && sources.length > 0 && (
            job?.status === 'QUEUED' ? (
              <p role="status" className="flex items-center gap-2 text-sm" data-testid={`publication-ai-running-${language}`}>
                <Spinner />{t('ai.translate.running')}
              </p>
            ) : !(ready && ready.language === language) && (
              <Button type="button" variant="outline" size="sm" className="w-fit"
                data-testid={`publication-ai-translate-${language}`} onClick={() => setAsking(language)}>
                <Languages aria-hidden />{t('ai.translate.generate')}
              </Button>
            )
          )}
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
                {(() => {
                  const offer = suggestion(language, field)
                  return offer && (
                    <div className="bg-muted/50 flex flex-col gap-1 rounded-md p-2" data-testid="publication-ai-suggestion">
                      <span className="text-muted-foreground text-xs">{t('ai.translate.candidate')}</span>
                      <p className="text-sm whitespace-pre-line" lang={language}>{offer.text}</p>
                      <Button type="button" size="sm" variant="outline" className="w-fit"
                        data-testid="publication-ai-suggestion-use"
                        onClick={() => takeSuggestion(language, field, offer.text)}>
                        {t('publishing.languages.aiUse')}
                      </Button>
                    </div>
                  )
                })()}
              </div>
            )
          })}
        </fieldset>
      ))}
      {asking && publicationId && form.quizId && (
        <TranslateDialog language={asking} onOpenChange={(open) => !open && setAsking(null)}
          sent={t('publishing.languages.aiSent')}
          send={(all, aiNoticeVersionNo) => requestAiPublicationTexts({ quizId: form.quizId!, publicationId,
            language: asking, all, aiNoticeVersionNo })} />
      )}
    </fieldset>
  )
}
