import { ApiError } from '@jabiz/client'
import type { TFunction } from 'i18next'
import { useEffect, useState } from 'react'

/** A value that follows `value` once it has stopped changing for `ms`. */
export function useDebounced<T>(value: T, ms: number): T {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), ms)
    return () => clearTimeout(timer)
  }, [value, ms])
  return settled
}

/** What went wrong with a call of the mail pages, said plainly where the status alone tells it. */
export function explain(error: unknown, t: TFunction): string {
  if (!(error instanceof ApiError)) return String(error)
  if (error.status === 409) return t('mail.errors.conflict')
  if (error.status === 429) return t('mail.errors.rateLimited')
  if (error.violations.some((v) => v.ruleCode === 'MAIL_TEXT_NEEDS_APPROVAL')) return t('mail.errors.needsApproval')
  if (error.status === 400 && error.violations.length > 0) return t('mail.errors.invalid')
  return error.display
}

/** The problems of one field in a rejected call; empty for other errors. */
export function fieldProblems(error: unknown, field: string): string[] {
  return error instanceof ApiError ? error.forField(field).map((v) => v.message ?? '') : []
}

/** The problems of a rejected call that belong to none of the given fields. */
export function otherProblems(error: unknown, fields: string[]): string[] {
  return error instanceof ApiError
    ? error.violations.filter((v) => !v.field || !fields.includes(v.field)).map((v) => v.message ?? '')
    : []
}
