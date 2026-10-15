import { useQuery } from '@tanstack/react-query'
import { useCallback, useState } from 'react'
import { ApiError, formatAmount, formatDateTime, useLanguage } from '@jabiz/admin'
import { readDictionary } from './api'
import { CURRENCY } from './ids'

/** A dictionary's labels by code, in the interface language (the code itself until the server answered). */
export function useLabels(urn: string): (code: string | null | undefined) => string {
  const language = useLanguage()
  const items = useQuery({
    queryKey: ['qb-ext', 'dictionary', urn, language],
    queryFn: () => readDictionary(urn),
    staleTime: 5 * 60_000,
  })
  const data = items.data
  return useCallback((code) => (code ? (data?.find((item) => item.code === code)?.label ?? code) : ''), [data])
}

/** An amount of the ledger, in its real currency (administrators see JPY, not the apps' "Kudos"). */
export function money(value: string | null | undefined): string {
  return value == null ? '' : formatAmount(value, { currency: CURRENCY, scale: 0 })
}

/** A time as the administration shows it; empty when there is none. */
export function when(value: string | null | undefined): string {
  return value ? formatDateTime(value) : ''
}

/** How often the page reads again after a decision until the state follows it, and for how long at most. */
export const POLL_INTERVAL_MS = 1_000
export const POLL_LIMIT_MS = 30_000

/**
 * After a decision the subject's state changes when the platform delivers the approval's event (a moment later): the
 * page reads again every {@link POLL_INTERVAL_MS} while the state still shows the review, for at most
 * {@link POLL_LIMIT_MS}, and says that it updates shortly. The interval is the query's `refetchInterval`.
 */
export function useAfterDecision() {
  const [decidedAt, setDecidedAt] = useState<number | null>(null)
  return {
    decided: decidedAt !== null,
    decide: () => setDecidedAt(Date.now()),
    interval: (stillWaiting: boolean): number | false =>
      decidedAt !== null && stillWaiting && Date.now() - decidedAt < POLL_LIMIT_MS ? POLL_INTERVAL_MS : false,
  }
}

/** Whether a read was refused for want of a permission (403): a part of the page the user may not see. */
export const forbidden = (error: unknown) => error instanceof ApiError && error.status === 403

/** Query keys of the two review queues: a decision on a review page refreshes them. */
export const QUEUE = {
  publications: ['qb-ext', 'queue', 'publications'],
  sponsors: ['qb-ext', 'queue', 'sponsors'],
} as const
