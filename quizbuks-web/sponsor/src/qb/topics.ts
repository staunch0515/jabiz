import { runQuery } from '@jabiz/client'
import { contentLanguageOf } from './api'

/*
 * F-S9 (docs/quizbuks/plans/impl/F-S9.md): the platform's word list of topics, as both SPAs read it (the app for a
 * taker's liked topics, `qb.app.topics`; the sponsor console for a quiz's and a publication's, `qb.sponsor.topics`).
 * A word is named by its code and shown by its name in the reader's language. A word deactivated is still shown where
 * it was chosen but not offered anew; a word merged counts as the one it was merged into and is shown by that one's
 * name (backend `topic.TopicRules`, the one definition).
 */

/** A word of the list. */
export interface Topic {
  topicId: string
  /** Its name in each of the platform's languages (zh, ja, en). */
  names: Record<string, string>
  active: boolean
  /** The word it counts as now; null when not merged. */
  mergedInto: string | null
}

type Row = Record<string, unknown>

/** The most words read at once: a word list stays far smaller. */
export const TOPICS_LIMIT = 500

/** A word's names as the template gives them (the JSON text of its column). */
function namesOf(value: unknown): Record<string, string> {
  if (value && typeof value === 'object') return value as Record<string, string>
  if (typeof value !== 'string' || value === '') return {}
  try {
    const parsed: unknown = JSON.parse(value)
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, string>) : {}
  } catch {
    return {}
  }
}

/** Every word of the list, by the template given (the app's or the sponsor console's). */
export async function readTopics(template: string): Promise<Topic[]> {
  const page = await runQuery<Row>(template, { limit: TOPICS_LIMIT })
  return page.items.map((row) => ({
    topicId: String(row.topicId ?? ''),
    names: namesOf(row.name),
    active: row.active === true || row.active === 'true',
    mergedInto: row.mergedInto == null || row.mergedInto === '' ? null : String(row.mergedInto),
  }))
}

/** The words by code. */
export function byCode(topics: readonly Topic[]): Map<string, Topic> {
  return new Map(topics.map((topic) => [topic.topicId, topic]))
}

/** Whether a word may be chosen anew: active and not merged. */
export function selectable(topic: Topic | undefined): boolean {
  return topic !== undefined && topic.active && topic.mergedInto === null
}

/** The code a word stands for now: what it was merged into, else itself. */
export function resolvedCode(code: string, words: ReadonlyMap<string, Topic>): string {
  return words.get(code)?.mergedInto ?? code
}

/** A word's name in the language (the base of `language`), the one it was merged into's when merged; else its code. */
export function topicName(code: string, words: ReadonlyMap<string, Topic>, language: string): string {
  const word = words.get(resolvedCode(code, words)) ?? words.get(code)
  if (!word) return code
  const lang = contentLanguageOf(language)
  return word.names[lang] || word.names.en || word.names.ja || word.names.zh || code
}

/** The words offered, sorted by their names in the language. */
export function offered(topics: readonly Topic[], language: string): Topic[] {
  const words = byCode(topics)
  return topics.filter(selectable).sort((a, b) =>
    topicName(a.topicId, words, language).localeCompare(topicName(b.topicId, words, language), language))
}

/** Codes as shown: each as the word it stands for now, once (a merged word and its target are one). */
export function shownCodes(codes: readonly string[], words: ReadonlyMap<string, Topic>): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  for (const code of codes) {
    const resolved = resolvedCode(code, words)
    if (!seen.has(resolved)) {
      seen.add(resolved)
      out.push(code)
    }
  }
  return out
}

/** The codes of a column (comma-separated), each once, in order. */
export function codesOf(value: unknown): string[] {
  const listed = Array.isArray(value) ? value.map(String) : typeof value === 'string' ? value.split(',') : []
  return [...new Set(listed.map((code) => code.trim()).filter((code) => code !== ''))]
}

/** The custom words of a column (a JSON array); none when absent or unreadable. */
export function customOf(value: unknown): string[] {
  if (Array.isArray(value)) return value.map(String)
  if (typeof value !== 'string' || value === '') return []
  try {
    const parsed: unknown = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
}

/** A custom word as kept: blanks around it dropped and runs of blanks made one space (as the server does). */
export function normalizedWord(word: string): string {
  return word.normalize('NFC').trim().replace(/\s+/g, ' ')
}

/** Whether two custom words are one (case does not count, as on the server). */
export function sameWord(a: string, b: string): boolean {
  return normalizedWord(a).toLowerCase() === normalizedWord(b).toLowerCase()
}

/** The parameters' declared values (the server reads `qb.topic.max-per-quiz` and `qb.topic.custom-max-length`). */
export const TOPIC_LIMITS = { maxPerQuiz: 10, customMaxLength: 30 } as const
