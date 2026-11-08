import { runQuery } from '@jabiz/admin'
import type { Page, SortSpec } from './api'
import { TEMPLATE } from './ids'

/*
 * F-S9: the word list of topics with its figures (backend `topic.QbTopics`, template `qb.admin.topics`,
 * docs/quizbuks/plans/impl/F-S9.md); typed by hand after the template. Adding, renaming, deactivating and merging are
 * the platform's process pages.
 */

/** A row of `qb.admin.topics`. */
export interface TopicRow {
  topicId: string
  /** The name in each language (zh, ja, en). */
  name: Record<string, string>
  active: boolean
  /** The word it counts as now; null when not merged. */
  mergedInto: string | null
  /** Cards with the word (all, and live now); null for a merged word (its figures are on the word it counts as). */
  publications: number | null
  livePublications: number | null
  /** Users who like it, and of them with strict mode on; null below the minimum group (or for a merged word). */
  likedBy: number | null
  strictBy: number | null
  minGroup: number
}

type Row = Record<string, unknown>
const num = (value: unknown): number | null => (value == null || value === '' ? null : Number(value))

/** A word's names as the template gives them (the JSON text of its column); none when unreadable. */
export function namesOf(value: unknown): Record<string, string> {
  if (value && typeof value === 'object') return value as Record<string, string>
  if (typeof value !== 'string' || value === '') return {}
  try {
    const parsed: unknown = JSON.parse(value)
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, string>) : {}
  } catch {
    return {}
  }
}

/** One page of the words with their figures. */
export async function readTopics(offset: number, limit: number, sorts?: SortSpec[]): Promise<Page<TopicRow>> {
  const answer = await runQuery<Row>(TEMPLATE.topics, { sorts, offset, limit, count: true })
  return {
    items: answer.items.map((row) => ({
      topicId: String(row.topicId ?? ''),
      name: namesOf(row.name),
      active: row.active === true || row.active === 'true',
      mergedInto: row.mergedInto == null || row.mergedInto === '' ? null : String(row.mergedInto),
      publications: num(row.publications),
      livePublications: num(row.livePublications),
      likedBy: num(row.likedBy),
      strictBy: num(row.strictBy),
      minGroup: num(row.minGroup) ?? 5,
    })),
    total: answer.total,
  }
}
