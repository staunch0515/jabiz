/** The content workshop's pages and the keys of what they read (invalidated together after an edit). */
export const QUIZZES_PATH = '/quizzes'
export const quizPath = (quizId: string) => `${QUIZZES_PATH}/${quizId}`

/** The quiz of an editor's path (`/quizzes/<id>`), or null. */
export function quizIdOf(path: string): string | null {
  const match = /^\/quizzes\/([^/]+)\/?$/.exec(path)
  return match ? decodeURIComponent(match[1]) : null
}

export const QUIZZES_QUERY = ['qb', 'quizzes'] as const
export const draftQuery = (quizId: string) => [...QUIZZES_QUERY, 'draft', quizId] as const
export const versionsQuery = (quizId: string) => [...QUIZZES_QUERY, 'versions', quizId] as const
