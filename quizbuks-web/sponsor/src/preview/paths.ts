/** The preview of what takers see (Q34b): a publication's, or a quiz's draft before it is published. */
export const previewOfPublication = (publicationId: string) => `/publications/${encodeURIComponent(publicationId)}/preview`
export const previewOfQuiz = (quizId: string) => `/quizzes/${encodeURIComponent(quizId)}/preview`

export type PreviewTarget = { kind: 'publication' | 'quiz'; id: string }

/** The preview a path asks for, or null. */
export function previewOf(path: string): PreviewTarget | null {
  const match = /^\/(publications|quizzes)\/([^/]+)\/preview\/?$/.exec(path)
  if (!match) return null
  return { kind: match[1] === 'publications' ? 'publication' : 'quiz', id: decodeURIComponent(match[2]) }
}
