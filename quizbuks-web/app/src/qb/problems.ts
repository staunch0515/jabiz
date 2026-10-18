import { ApiError, i18n } from '@jabiz/client'
import { CODE } from './ids'
import { QB } from './messages'

/**
 * What to tell the user about a failed call. The server translates its own problems by the request's language
 * (`QB_USER_BANNED`, `QB_PROFILE_CHANGED`, `EMAIL_NOT_VERIFIED`, a field's rule…), so their text is shown as it is;
 * anything else (offline, a server error without a message) gets the generic text.
 */
export function problemText(error: unknown): string {
  if (error instanceof ApiError && error.status < 500) {
    const text = error.display
    if (text && !/^HTTP \d+$/.test(text)) return text
  }
  return i18n.t('failed', { ns: QB })
}

/**
 * Whether the server refused because the caller must first accept a version of their terms (`QB_CONSENT_REQUIRED`,
 * Q7b): the pages then read the caller's standing again, and the prompt comes up (review M3).
 */
export function consentRequired(error: unknown): boolean {
  return error instanceof ApiError && error.violations.some((v) => v.ruleCode === CODE.consentRequired)
}

/** The server's message for one field of a refused form, if any. */
export function fieldProblem(error: unknown, field: string): string | undefined {
  if (!(error instanceof ApiError)) return undefined
  const messages = error.forField(field).map((v) => v.message)
  return messages.length > 0 ? messages.join(' ') : undefined
}

/** A refusal that names no field (or names one the form does not show), for the form's general message. */
export function generalProblem(error: unknown, fields: readonly string[]): string | undefined {
  if (error === undefined || error === null) return undefined
  if (!(error instanceof ApiError) || error.violations.length === 0) return problemText(error)
  const rest = error.violations.filter((v) => !v.field || !fields.includes(v.field))
  return rest.length > 0 ? rest.map((v) => v.message).join(' ') : undefined
}
