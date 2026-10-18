import {
  getMailLanguage,
  getMailPreferences,
  LANGUAGE_NAMES,
  languages as interfaceLanguages,
  setMailLanguage,
  setMailPreference,
  type Language,
  type MailLanguage,
  type MailPreference,
} from '@jabiz/client'
import { useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Spinner } from '../components/spinner'
import { Label } from '../components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../components/ui/select'
import { Switch } from '../components/ui/switch'
import { cn } from '../lib/utils'
import { AuthError, Outcome } from './frame'
import { AUTH_NAMESPACE } from './messages'
import { failure } from './problems'

export interface MailPreferencesProps {
  /** Lists the notifications; `getMailPreferences` of `@jabiz/client` by default. Pass a stable function. */
  load?: () => Promise<MailPreference[]>
  /** Turns one on or off; `setMailPreference` by default. */
  save?: (template: string, subscribed: boolean) => Promise<void>
  /** Reads the mail language; `getMailLanguage` by default. Pass a stable function. */
  loadLanguage?: () => Promise<MailLanguage>
  /** Sets the mail language; `setMailLanguage` by default. */
  saveLanguage?: (locale: Language) => Promise<void>
  className?: string
}

/** The languages to offer: those mail can be in that the interface knows, in the interface's order. */
function choices(language: MailLanguage | null): Language[] {
  const server = language?.languages ?? []
  return interfaceLanguages.filter((lang) => server.includes(lang))
}

/**
 * The signed-in user's mail settings (docs/design/18-numbering-approvals-tasks.md section 5.6; phase 16s, decision
 * D49): the language of their mail, and a switch for each notification of this frontend's sign-in entry, by its
 * display name. Mail about the account itself (verification, password reset) is always sent and not listed. Every
 * change is saved at once. Goes on a page of the caller's (a security or account page).
 */
export function MailPreferences({
  load = getMailPreferences,
  save = setMailPreference,
  loadLanguage = getMailLanguage,
  saveLanguage = setMailLanguage,
  className,
}: MailPreferencesProps) {
  const { t, i18n: current } = useTranslation(AUTH_NAMESPACE)
  const languageId = useId()
  const listId = useId()
  const [templates, setTemplates] = useState<MailPreference[] | null>(null)
  const [language, setLanguage] = useState<MailLanguage | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)

  // Listed again when the interface language changes: the names are in it.
  useEffect(() => {
    let cancelled = false
    Promise.all([load(), loadLanguage()]).then(
      ([list, mail]) => {
        if (cancelled) return
        setTemplates(list)
        setLanguage(mail)
        setError(null)
      },
      (e: unknown) => {
        if (!cancelled) setError(failure(e, t('mailPreferences.failed')))
      },
    )
    return () => {
      cancelled = true
    }
  }, [load, loadLanguage, t, current.language])

  const toggle = async (preference: MailPreference, subscribed: boolean) => {
    const template = preference.template!
    setBusy(template)
    setDone(null)
    setError(null)
    try {
      await save(template, subscribed)
      setTemplates((list) => list?.map((p) => (p.template === template ? { ...p, subscribed } : p)) ?? list)
      const name = preference.displayName ?? template
      setDone(subscribed ? t('mailPreferences.on', { name }) : t('mailPreferences.off', { name }))
    } catch (e) {
      setError(failure(e, t('mailPreferences.failed')))
    } finally {
      setBusy(null)
    }
  }

  const changeLanguage = async (lang: Language) => {
    setBusy('language')
    setDone(null)
    setError(null)
    try {
      await saveLanguage(lang)
      setLanguage((mail) => (mail ? { ...mail, locale: lang, effective: lang } : mail))
      setDone(t('mailPreferences.languageSaved', { language: LANGUAGE_NAMES[lang] }))
    } catch (e) {
      setError(failure(e, t('mailPreferences.failed')))
    } finally {
      setBusy(null)
    }
  }

  const offered = choices(language)
  const selected = (offered as string[]).includes(language?.effective ?? '') ? language!.effective : undefined
  const loaded = templates !== null && language !== null

  return (
    <div className={cn('flex flex-col gap-4', className)} data-testid="mail-preferences">
      <p className="text-muted-foreground text-sm">{t('mailPreferences.hint')}</p>
      {done && (
        <Outcome ok testId="mail-preferences-state">
          {done}
        </Outcome>
      )}
      {error && <AuthError message={error} testId="mail-preferences-error" />}
      {!loaded && !error && <Spinner />}
      {loaded && offered.length > 1 && (
        <div className="flex flex-col gap-2">
          <Label htmlFor={languageId}>{t('mailPreferences.language')}</Label>
          <Select value={selected} onValueChange={(lang) => void changeLanguage(lang as Language)}
            disabled={busy !== null}>
            <SelectTrigger id={languageId} className="w-full sm:w-64" data-testid="mail-language">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {offered.map((lang) => (
                <SelectItem key={lang} value={lang} lang={lang}>
                  {LANGUAGE_NAMES[lang]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}
      {loaded && templates.length > 0 && (
        <ul className="divide-border flex flex-col divide-y rounded-md border" aria-label={t('mailPreferences.list')}>
          {templates.map((preference) => {
            const id = `${listId}-${preference.template}`
            return (
              <li key={preference.template} className="flex items-center justify-between gap-4 p-3"
                data-testid="mail-preference">
                <Label htmlFor={id} className="font-normal">
                  {preference.displayName ?? preference.template}
                </Label>
                <Switch id={id} checked={preference.subscribed ?? true} disabled={busy !== null}
                  onCheckedChange={(on) => void toggle(preference, on)} data-testid={`mail-preference-${preference.template}`} />
              </li>
            )
          })}
        </ul>
      )}
      {loaded && templates.length === 0 && (
        <p className="text-muted-foreground text-sm" data-testid="mail-preferences-none">
          {t('mailPreferences.none')}
        </p>
      )}
    </div>
  )
}
