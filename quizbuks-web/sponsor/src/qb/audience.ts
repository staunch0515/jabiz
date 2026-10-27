import { api, runProcess, unwrap } from '@jabiz/client'
import { AUDIENCE } from './ids'

/*
 * Customer matching (docs/quizbuks/plans/Q39-audience.md): the criteria a taker may tell of themselves and a sponsor
 * may aim a publication at. The list mirrors `audience.AudienceCriteria.ALL` in backend/quizbuks (keys, options,
 * bounds); the server checks every value again, the pages only offer them. Labels are in `qb/messages.ts` under
 * `audience.<key>` and `audience.<key>Options.<option>`.
 */

export type CriterionKind = 'SET' | 'RANGE'

export interface Criterion {
  key: string
  kind: CriterionKind
  /** The options of a set, in the order shown. */
  options: readonly string[]
  /** A taker may tell several options (interests); otherwise one. */
  multiple: boolean
  /** A range's bounds (an age). */
  min?: number
  max?: number
}

/** The youngest age a birth year may give (the terms' minimum) and the earliest birth year. */
export const MIN_AGE = 13
export const EARLIEST_BIRTH_YEAR = 1900

export const CRITERIA: readonly Criterion[] = [
  { key: 'gender', kind: 'SET', options: ['FEMALE', 'MALE', 'OTHER', 'NO_ANSWER'], multiple: false },
  // The profile keeps the birth year; a publication aims at ages.
  { key: 'age', kind: 'RANGE', options: [], multiple: false, min: MIN_AGE, max: 100 },
  {
    key: 'occupation', kind: 'SET', multiple: false,
    options: ['STUDENT', 'EMPLOYEE', 'CIVIL_SERVANT', 'SELF_EMPLOYED', 'PART_TIME', 'HOMEMAKER', 'RETIRED', 'OTHER'],
  },
  {
    key: 'interest', kind: 'SET', multiple: true,
    options: ['FOOD', 'TRAVEL', 'TECHNOLOGY', 'CULTURE', 'SPORTS', 'FASHION', 'BEAUTY', 'FINANCE', 'PARENTING',
      'GAMES', 'MUSIC', 'BOOKS', 'OUTDOORS', 'PETS'],
  },
]

export function criterionOf(key: string): Criterion | undefined {
  return CRITERIA.find((criterion) => criterion.key === key)
}

/** `AudienceCriteria.AnswerInput`: what a taker tells of one criterion, their options or their birth year. */
export interface Answer {
  key: string
  options?: string[] | null
  number?: number | null
}

/** `AudienceCriteria.ConditionInput`: a publication's options of a criterion, or the bounds of a range. */
export interface Condition {
  key: string
  options?: string[] | null
  min?: number | null
  max?: number | null
}

/** `ProfileAttributeProcesses.AttributesOutput`. */
export interface AttributesOutput {
  answered: number
}

type Row = Record<string, unknown>

/** Stored rows (one per option, or one number) gathered into one answer per criterion, in the criteria's order. */
export function answersOf(rows: readonly Row[]): Answer[] {
  const byKey = new Map<string, Answer>()
  for (const row of rows) {
    const key = String(row.criterionKey)
    const answer = byKey.get(key) ?? { key }
    if (row.valueCode != null) {
      answer.options = [...(answer.options ?? []), String(row.valueCode)]
    } else if (row.valueNumber != null) {
      answer.number = Number(row.valueNumber)
    }
    byKey.set(key, answer)
  }
  return CRITERIA.flatMap((criterion) => {
    const answer = byKey.get(criterion.key)
    if (!answer) return []
    const options = answer.options
      ? criterion.options.filter((option) => answer.options!.includes(option))
      : undefined
    return [{ ...answer, ...(options ? { options } : {}) }]
  })
}

/** The caller's own answers (the `me:` dataset holds nothing else; at most every option of every criterion). */
export async function readMyAnswers(): Promise<Answer[]> {
  const page = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: AUDIENCE.myAttributes } },
    body: { limit: 100 },
  }))
  return answersOf((page.items ?? []).map((item) => (item.attributes ?? {}) as Row))
}

/** Saves the whole of what the caller tells: a criterion left out is cleared. */
export const saveMyAnswers = (answers: Answer[]) =>
  runProcess<AttributesOutput>(AUDIENCE.attributesSave, { answers })
