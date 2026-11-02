import type { MySponsorPage } from '../page/api'
import type { Sponsor } from '../qb/api'

export type ChecklistKey = 'basics' | 'page' | 'products' | 'staff' | 'mailServer'

export interface ChecklistItem {
  key: ChecklistKey
  done: boolean
  /** Nice to have, not needed to publish quizzes. */
  optional: boolean
}

export interface ChecklistFacts {
  sponsor: Sponsor | null
  page: MySponsorPage | null
  /** Listed products (unlisted or taken down do not count). */
  listedProducts: number
  staff: number
  mailServerVerified: boolean
}

/**
 * The hub's completion list (Q79, D-Q79-4): what a sponsor has set up, in the order it is worth doing. The basic
 * profile counts once applied with a company (its review is shown apart); the shop page once published; the rest
 * are optional.
 */
export function checklist(facts: ChecklistFacts): ChecklistItem[] {
  return [
    { key: 'basics', done: !!facts.sponsor && facts.sponsor.company.trim() !== '', optional: false },
    { key: 'page', done: facts.page?.status === 'PUBLISHED', optional: false },
    { key: 'products', done: facts.listedProducts > 0, optional: true },
    { key: 'staff', done: facts.staff > 0, optional: true },
    { key: 'mailServer', done: facts.mailServerVerified, optional: true },
  ]
}

/** How many of the items are done, and how many there are. */
export function progress(items: readonly ChecklistItem[]): { done: number; total: number } {
  return { done: items.filter((item) => item.done).length, total: items.length }
}
