import { cn, Input, Tabs, TabsContent, TabsList, TabsTrigger, Textarea, UI_SCOPE } from '@jabiz/ui'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { I18nTextParams, Texts } from '../meta/i18nText'
import MarkdownView from './MarkdownView'

interface Props {
  /** The field's name, which scopes the test ids of the inputs. */
  name: string
  params: I18nTextParams
  value?: Texts
  onChange?(value: Texts): void
  disabled?: boolean
  /** Languages with a problem, marked on their tabs. */
  invalidLanguages?: string[]
  /** The field's label: each language's input is named "label (language)". */
  label?: string
  /** The field's problems, read with every language's input. */
  'aria-describedby'?: string
}

/**
 * One tab per language for a multilingual text (docs/design/16-content-authoring.md section 1.3): required languages
 * are marked, Markdown gets a preview rendered by the same renderer as the display. The interface language opens
 * first.
 */
export default function I18nTextInput({
  name,
  params,
  value,
  onChange,
  disabled,
  invalidLanguages = [],
  label,
  'aria-describedby': describedBy,
}: Props) {
  const { t, i18n } = useTranslation()
  const ui = i18n.language.split('-')[0]
  const [active, setActive] = useState(params.locales.includes(ui) ? ui : params.locales[0])
  const texts = value ?? {}
  const set = (language: string, text: string) => onChange?.({ ...texts, [language]: text })

  return (
    <Tabs value={active} onValueChange={setActive} data-testid={`i18n-${name}`} className={cn(UI_SCOPE, 'gap-2')}>
      <TabsList>
        {params.locales.map((language) => {
          const required = params.required.includes(language)
          const invalid = invalidLanguages.includes(language)
          return (
            <TabsTrigger
              key={language}
              value={language}
              data-invalid={invalid || undefined}
              className={cn(invalid && 'text-destructive data-[state=active]:text-destructive')}
            >
              <span data-testid={`i18n-${name}-tab-${language}`}>
                {t(`languages.${language}`, { defaultValue: language })}
                {required && <span aria-label={t('form.requiredLanguage')}> *</span>}
              </span>
            </TabsTrigger>
          )
        })}
      </TabsList>
      {params.locales.map((language) => {
        const invalid = invalidLanguages.includes(language)
        const text = texts[language] ?? ''
        const languageName = t(`languages.${language}`, { defaultValue: language })
        const common = {
          lang: language,
          value: text,
          disabled,
          'aria-label': label ? `${label} (${languageName})` : languageName,
          'aria-invalid': invalid || undefined,
          'aria-required': params.required.includes(language) || undefined,
          'aria-describedby': describedBy,
          'data-testid': `i18n-${name}-${language}`,
        }
        return (
          <TabsContent key={language} value={language} className="flex flex-col gap-2">
            {params.multiline ? (
              <Textarea {...common} rows={3} className="max-h-72" onChange={(e) => set(language, e.target.value)} />
            ) : (
              <Input {...common} onChange={(e) => set(language, e.target.value)} />
            )}
            {params.format === 'markdown' && text.trim() !== '' && (
              <div className="flex flex-col gap-1">
                <span className="text-muted-foreground text-sm">{t('form.preview')}</span>
                <MarkdownView text={text} lang={language} />
              </div>
            )}
          </TabsContent>
        )
      })}
    </Tabs>
  )
}
