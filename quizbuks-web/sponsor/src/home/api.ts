import { runQuery } from '@jabiz/client'
import { minuteNow } from '../qb/legal'

/**
 * The home's "to do" counts (F-F3b, template `qb.sponsor.todo`: one row, counts only). A column that is not a number
 * counts as none; the page adds nothing up itself except the prizes badge.
 */
export const TODO_QUERY = ['qb', 'sponsor-todo'] as const

export const TODO_TEMPLATE = 'qb.sponsor.todo'

/** The columns of the template, in the order the list shows them (the list's own order is `todo.ts`'s). */
export const TODO_COLUMNS = ['claimsToConfirm', 'digitalToSend', 'fundsShort', 'rewardsWaiting', 'invoicesOverdue',
  'publicationsInReview', 'pageInReview', 'sharedVideosInReview', 'videoUsesToConfirm', 'creditsExpiring'] as const
export type TodoColumn = (typeof TODO_COLUMNS)[number]
export type TodoCounts = Record<TodoColumn, number>

export const NO_TODO: TodoCounts = Object.fromEntries(TODO_COLUMNS.map((column) => [column, 0])) as TodoCounts

export async function readTodo(at: string = minuteNow()): Promise<TodoCounts> {
  const row = (await runQuery<Record<string, unknown>>(TODO_TEMPLATE, { params: { at }, limit: 1 })).items[0]
  const counts = { ...NO_TODO }
  for (const column of TODO_COLUMNS) {
    const n = Number(row?.[column] ?? 0)
    counts[column] = Number.isFinite(n) && n > 0 ? Math.floor(n) : 0
  }
  return counts
}
