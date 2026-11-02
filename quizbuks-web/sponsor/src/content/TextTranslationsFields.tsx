import { Badge, Checkbox, Input, Label, Textarea } from '@jabiz/ui'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import type { ContentLanguage } from '../qb/api'
import { QB } from '../qb/messages'
import { sourceHash } from './languages'

/** One of a sponsor's short texts that is translated (Q73c): its field, label, base text and limit. */
export interface TranslatedField {
  field: string
  label: string
  source: string
  maxLength: number
  multiline?: boolean
}

/** Texts by language and field. */
export type TextsByLanguage = Partial<Record<ContentLanguage, Record<string, string>>>

/** The stored `{language: {field: {text, hash}}}` (`content.TextTranslations`): its texts and its hashes. */
export function parseStoredTranslations(value: unknown): { texts: TextsByLanguage; hashes: TextsByLanguage } {
  const texts: TextsByLanguage = {}
  const hashes: TextsByLanguage = {}
  if (typeof value !== 'string' || !value) return { texts, hashes }
  try {
    const parsed = JSON.parse(value) as Record<string, Record<string, { text?: string; hash?: string }>>
    for (const [language, fields] of Object.entries(parsed)) {
      const l = language as ContentLanguage
      texts[l] = {}
      hashes[l] = {}
      for (const [field, entry] of Object.entries(fields ?? {})) {
        if (typeof entry?.text === 'string') texts[l]![field] = entry.text
        if (typeof entry?.hash === 'string') hashes[l]![field] = entry.hash
      }
    }
  } catch {
    return { texts: {}, hashes: {} }
  }
  return { texts, hashes }
}

/** The base texts' hashes, as `content/languages.ts` computes them (the server's `sourceHash`). */
function useSourceHashes(fields: TranslatedField[]): Record<string, string | null> {
  const [hashes, setHashes] = useState<Record<string, string | null>>({})
  const joined = fields.map((f) => `${f.field}\u0000${f.source}`).join('\u0001')
  useEffect(() => {
    let live = true
    void Promise.all(fields.map(async (f) => [f.field, await sourceHash(f.source)] as const))
      .then((pairs) => live && setHashes(Object.fromEntries(pairs)))
    return () => {
      live = false
    }
    // The fields by their content.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [joined])
  return hashes
}

/**
 * A sponsor's short texts in other languages (Q73c, docs/quizbuks/plans/Q73-content-languages.md §4): for each
 * language, each base text with its translation beside it. A translation made from an earlier base text is marked out
 * of date; the sponsor translates it again or confirms it still holds (`confirmed`: `<language>.<field>`), as the save
 * requires. Every check is the server's; its problems come at `translations.<language>.<field>`.
 */
export function TextTranslationsFields({ id, languages, fields, value, hashes, confirmed, onChange, onConfirmed,
  problemOf, disabled, testId = 'text-translations' }: {
  id: string
  languages: ContentLanguage[]
  fields: TranslatedField[]
  value: TextsByLanguage
  /** The stored translations' hashes (none for a new one). */
  hashes: TextsByLanguage
  confirmed: string[]
  onChange: (value: TextsByLanguage) => void
  onConfirmed: (confirmed: string[]) => void
  problemOf: (path: string) => string | undefined
  disabled?: boolean
  testId?: string
}) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const current = useSourceHashes(fields)
  const shown = fields.filter((f) => f.source.trim() !== '')
  if (languages.length === 0 || shown.length === 0) return null
  return (
    <div className="flex flex-col gap-4" data-testid={testId}>
      {languages.map((language) => (
        <fieldset key={language} className="flex flex-col gap-3 rounded-md border p-3"
          data-testid={`${testId}-${language}`}>
          <legend className="px-1 text-sm font-medium">
            {t('textTranslations.in', { language: tQb(`languages.${language}`) })}
          </legend>
          {shown.map((f) => {
            const fieldId = `${id}-${language}-${f.field}`
            const text = value[language]?.[f.field] ?? ''
            const stored = hashes[language]?.[f.field]
            // Out of date: stored, unchanged, and made from another base text.
            const key = `${language}.${f.field}`
            const stale = !!stored && !!current[f.field] && stored !== current[f.field]
            const problem = problemOf(`translations.${language}.${f.field}`)
            const onText = (next: string) =>
              onChange({ ...value, [language]: { ...value[language], [f.field]: next } })
            return (
              <div key={f.field} className="flex flex-col gap-1">
                <Label htmlFor={fieldId} className="flex items-center gap-2">
                  {f.label}
                  {stale && !confirmed.includes(key) && (
                    <Badge variant="default" data-testid="text-translation-stale">{t('textTranslations.stale')}</Badge>
                  )}
                </Label>
                <p className="text-muted-foreground text-xs whitespace-pre-line" id={`${fieldId}-source`}>
                  {f.source}
                </p>
                {f.multiline ? (
                  <Textarea id={fieldId} rows={3} value={text} maxLength={f.maxLength} disabled={disabled}
                    lang={language} aria-describedby={`${fieldId}-source`} aria-invalid={problem ? true : undefined}
                    data-testid={`${testId}-input`} data-key={key} onChange={(event) => onText(event.target.value)} />
                ) : (
                  <Input id={fieldId} value={text} maxLength={f.maxLength} disabled={disabled} lang={language}
                    autoComplete="off" aria-describedby={`${fieldId}-source`} aria-invalid={problem ? true : undefined}
                    data-testid={`${testId}-input`} data-key={key} onChange={(event) => onText(event.target.value)} />
                )}
                {stale && (
                  <div className="flex items-center gap-2">
                    <Checkbox id={`${fieldId}-confirm`} checked={confirmed.includes(key)} disabled={disabled}
                      data-testid="text-translation-confirm"
                      onCheckedChange={(checked) => onConfirmed(checked === true ? [...confirmed, key]
                        : confirmed.filter((c) => c !== key))} />
                    <Label htmlFor={`${fieldId}-confirm`} className="font-normal">{t('textTranslations.confirm')}</Label>
                  </div>
                )}
                {problem && <p className="text-destructive text-sm">{problem}</p>}
              </div>
            )
          })}
        </fieldset>
      ))}
    </div>
  )
}
