import { api, unwrap } from '../api/client'
import type { components } from '../api/schema'
import { session } from '../api/session'
import type { Language } from '../i18n/languages'

/**
 * The signed-in user's mail settings (docs/design/18-numbering-approvals-tasks.md section 5.6; phase 16s, decision
 * D49): which notifications they receive, and the language of their mail. What the `MailPreferences` block of
 * `@jabiz/ui` calls, for an application that draws its own page instead. Each throws an `ApiError` when refused.
 */

/**
 * One notification the user may turn off: the template's `name`, its `displayName` in the interface language and
 * whether it is received (`subscribed`).
 */
export type MailPreference = components['schemas']['TemplatePreference']

/**
 * The language of the user's mail: `locale` as they chose it (null or absent: not chosen, mail goes in the application's
 * default), `effective` the one their mail goes in, `languages` those mail can be in.
 */
export type MailLanguage = components['schemas']['MailLanguage']

/**
 * The notifications of this frontend's sign-in entry and whether the user receives each (`GET
 * /api/auth/mail/preferences`), named in the interface language. Transactional mail (verification, password reset)
 * cannot be turned off and is not listed; nor are the notifications of other entries.
 */
export async function getMailPreferences(): Promise<MailPreference[]> {
  const answer = (await unwrap(api.GET('/api/auth/mail/preferences'))) as { templates?: MailPreference[] }
  return answer.templates ?? []
}

/** Turns one notification on or off for the signed-in user (`POST /api/auth/mail/preferences`). */
export async function setMailPreference(template: string, subscribed: boolean): Promise<void> {
  await unwrap(api.POST('/api/auth/mail/preferences', { body: { template, subscribed } }))
}

/** The language of the signed-in user's mail (`GET /api/auth/account/locale`). */
export async function getMailLanguage(): Promise<MailLanguage> {
  return unwrap(api.GET('/api/auth/account/locale')) as Promise<MailLanguage>
}

/**
 * Sets the language of the signed-in user's mail (`POST /api/auth/account/locale`); null: the application's
 * default.
 */
export async function setMailLanguage(locale: Language | null): Promise<void> {
  await unwrap(api.POST('/api/auth/account/locale', { body: { locale: locale ?? '' } }))
}

/**
 * After the interface language changed to `lang`: whether to offer making it the language of the user's mail too.
 * The answer is `lang` when the user is signed in, mail can be in `lang` and their mail goes in another language;
 * null otherwise, and when it cannot be told (offline, refused). It changes nothing: changing the mail language is
 * the user's choice ({@link setMailLanguage} once they accept), never a side effect of switching the interface
 * (decision D49 item 3).
 */
export async function mailLanguageOffer(lang: Language): Promise<Language | null> {
  if (!session.accessToken() && !session.refreshToken()) return null
  try {
    const current = await getMailLanguage()
    const languages = current.languages ?? []
    if (!languages.includes(lang)) return null
    return current.effective === lang ? null : lang
  } catch {
    return null
  }
}
