import {
  getMailLanguage,
  getMailPreferences,
  LANGUAGE_NAMES,
  languages as interfaceLanguages,
  onMailLanguageChange,
  setMailLanguage,
  setMailPreference,
  type Language,
  type MailLanguage,
  type MailPreference,
} from '@jabiz/client'
import { useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Spinner } from '../components/spinner'
import { Button } from '../components/ui/button'
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
  /** Sets the mail language (null: the application's default); `setMailLanguage` by default. */
  saveLanguage?: (locale: Language | null) => Promise<void>
  className?: string
}

/** The value of the language choice that leaves it to the application (`locale: null`). */
const DEFAULT_CHOICE = 'default'

/** The languages to offer: those mail can be in that the interface knows, in the interface's order. */
function choices(language: MailLanguage | null): Language[] {
  const server = language?.languages ?? []
  return interfaceLanguages.filter((lang) => server.includes(lang))
}

/** A language's name as it names itself; the code for one the interface does not know. */
function languageName(code: string | undefined): string {
  return code === undefined ? '' : (LANGUAGE_NAMES[code as Language] ?? code)
}

/**
 * The signed-in user's mail settings (docs/design/18-numbering-approvals-tasks.md section 5.6; phase 16s, decision
 * D49): the language of their mail (one of the application's, or its default), a switch for each notification of
 * this frontend's sign-in entry, by its display name, and the notifications of other entries the user turned off, so
 * that they can turn them on again. Mail about the account itself (verification, password reset) is always sent and
 * not listed. Every change is saved at once; a mail language set elsewhere on the page (the notice of
 * `offerMailLanguage`) shows at once. Goes on a page of the caller's (a security or account page).
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
  const [failed, setFailed] = useState(false)
  const [done, setDone] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  // Read again when this changes (trying again after a failure).
  const [attempt, setAttempt] = useState(0)
  // The mail language read again when this changes (set elsewhere on the page).
  const [languageVersion, setLanguageVersion] = useState(0)

  // Listed again when the interface language changes: the names are in it.
  useEffect(() => {
    let cancelled = false
    Promise.all([load(), loadLanguage()]).then(
      ([list, mail]) => {
        if (cancelled) return
        setTemplates(list)
        setLanguage(mail)
        setError(null)
        setFailed(false)
      },
      (e: unknown) => {
        if (cancelled) return
        setError(failure(e, t('mailPreferences.failed')))
        setFailed(true)
      },
    )
    return () => {
      cancelled = true
    }
  }, [load, loadLanguage, t, current.language, attempt])

  useEffect(() => onMailLanguageChange(() => setLanguageVersion((v) => v + 1)), [])

  useEffect(() => {
    if (languageVersion === 0) return
    let cancelled = false
    loadLanguage().then(
      (mail) => {
        if (!cancelled) setLanguage(mail)
      },
      () => {
        // The choice shown stays; the next visit reads it again.
      },
    )
    return () => {
      cancelled = true
    }
  }, [languageVersion, loadLanguage])

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

  const changeLanguage = async (choice: string) => {
    const locale = choice === DEFAULT_CHOICE ? null : (choice as Language)
    setBusy('language')
    setDone(null)
    setError(null)
    try {
      await saveLanguage(locale)
      setLanguage((mail) => (mail ? { ...mail, locale: locale ?? undefined, effective: locale ?? mail.defaultLocale } : mail))
      setDone(locale === null
        ? t('mailPreferences.languageDefaultSaved', { language: languageName(language?.defaultLocale) })
        : t('mailPreferences.languageSaved', { language: LANGUAGE_NAMES[locale] }))
    } catch (e) {
      setError(failure(e, t('mailPreferences.failed')))
    } finally {
      setBusy(null)
    }
  }

  const offered = choices(language)
  // Controlled, always one of the items: a chosen language the interface does not offer shows as the default.
  const selected = language?.locale && (offered as string[]).includes(language.locale) ? language.locale : DEFAULT_CHOICE
  const loaded = templates !== null && language !== null
  const own = templates?.filter((p) => !p.otherEntry) ?? []
  const others = templates?.filter((p) => p.otherEntry) ?? []

  const list = (items: MailPreference[], label: string, testId: string) => (
    <ul className="divide-border flex flex-col divide-y rounded-md border" aria-label={label} data-testid={testId}>
      {items.map((preference) => {
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
  )

  return (
    <div className={cn('flex flex-col gap-4', className)} data-testid="mail-preferences">
      <p className="text-muted-foreground text-sm">{t('mailPreferences.hint')}</p>
      {done && (
        <Outcome ok testId="mail-preferences-state">
          {done}
        </Outcome>
      )}
      {error && <AuthError message={error} testId="mail-preferences-error" />}
      {failed && (
        <Button variant="outline" className="self-start" data-testid="mail-preferences-retry"
          onClick={() => {
            setError(null)
            setFailed(false)
            setAttempt((a) => a + 1)
          }}>
          {t('mailPreferences.retry')}
        </Button>
      )}
      {!loaded && !error && <Spinner />}
      {loaded && offered.length > 1 && (
        <div className="flex flex-col gap-2">
          <Label htmlFor={languageId}>{t('mailPreferences.language')}</Label>
          <Select value={selected} onValueChange={(choice) => void changeLanguage(choice)} disabled={busy !== null}>
            <SelectTrigger id={languageId} className="w-full sm:w-64" data-testid="mail-language">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={DEFAULT_CHOICE}>
                {t('mailPreferences.languageDefault', { language: languageName(language.defaultLocale ?? language.effective) })}
              </SelectItem>
              {offered.map((lang) => (
                <SelectItem key={lang} value={lang} lang={lang}>
                  {LANGUAGE_NAMES[lang]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}
      {loaded && own.length > 0 && list(own, t('mailPreferences.list'), 'mail-preferences-own')}
      {loaded && own.length === 0 && (
        <p className="text-muted-foreground text-sm" data-testid="mail-preferences-none">
          {t('mailPreferences.none')}
        </p>
      )}
      {loaded && others.length > 0 && (
        <div className="flex flex-col gap-2" data-testid="mail-preferences-others">
          <h3 className="text-sm font-medium">{t('mailPreferences.othersTitle')}</h3>
          <p className="text-muted-foreground text-sm">{t('mailPreferences.othersHint')}</p>
          {list(others, t('mailPreferences.othersTitle'), 'mail-preferences-other-list')}
        </div>
      )}
    </div>
  )
}
