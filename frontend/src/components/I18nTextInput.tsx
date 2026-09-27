import { Input, Tabs, Typography } from 'antd'
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
}

/**
 * One tab per language for a multilingual text (docs/design/16-content-authoring.md section 1.3): required languages
 * are marked, Markdown gets a preview rendered by the same renderer as the display.
 */
export default function I18nTextInput({ name, params, value, onChange, disabled, invalidLanguages = [] }: Props) {
  const { t, i18n } = useTranslation()
  const ui = i18n.language.split('-')[0]
  const [active, setActive] = useState(params.locales.includes(ui) ? ui : params.locales[0])
  const texts = value ?? {}
  const set = (language: string, text: string) => onChange?.({ ...texts, [language]: text })

  return (
    <Tabs
      size="small"
      activeKey={active}
      onChange={setActive}
      data-testid={`i18n-${name}`}
      items={params.locales.map((language) => {
        const required = params.required.includes(language)
        const invalid = invalidLanguages.includes(language)
        const text = texts[language] ?? ''
        const editor = params.multiline ? (
          <Input.TextArea
            lang={language}
            autoSize={{ minRows: 3, maxRows: 12 }}
            value={text}
            disabled={disabled}
            status={invalid ? 'error' : undefined}
            onChange={(e) => set(language, e.target.value)}
            data-testid={`i18n-${name}-${language}`}
          />
        ) : (
          <Input
            lang={language}
            value={text}
            disabled={disabled}
            status={invalid ? 'error' : undefined}
            onChange={(e) => set(language, e.target.value)}
            data-testid={`i18n-${name}-${language}`}
          />
        )
        return {
          key: language,
          label: (
            <span data-testid={`i18n-${name}-tab-${language}`} style={invalid ? { color: 'var(--ant-color-error, #ff4d4f)' } : undefined}>
              {t(`languages.${language}`, { defaultValue: language })}
              {required && <span aria-label={t('form.requiredLanguage')}> *</span>}
            </span>
          ),
          children: (
            <>
              {editor}
              {params.format === 'markdown' && text.trim() !== '' && (
                <div style={{ marginTop: 8 }}>
                  <Typography.Text type="secondary">{t('form.preview')}</Typography.Text>
                  <MarkdownView text={text} lang={language} />
                </div>
              )}
            </>
          ),
        }
      })}
    />
  )
}
