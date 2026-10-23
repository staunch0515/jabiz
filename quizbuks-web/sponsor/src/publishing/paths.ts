/** The publishing pages and the keys of what they read (invalidated together after a change). */
export const PUBLICATIONS_PATH = '/publications'
export const NEW_PUBLICATION = 'new'
export const publicationPath = (publicationId: string) => `${PUBLICATIONS_PATH}/${encodeURIComponent(publicationId)}`

/**
 * The publication of a page's path: {@link NEW_PUBLICATION} for `/publications/new`, its key for
 * `/publications/<id>`, otherwise null.
 */
export function publicationIdOf(path: string): string | null {
  const match = /^\/publications\/([^/]+)\/?$/.exec(path)
  return match ? decodeURIComponent(match[1]) : null
}

export const PUBLICATIONS_QUERY = ['qb', 'publications'] as const
export const publicationQuery = (publicationId: string) => [...PUBLICATIONS_QUERY, 'one', publicationId] as const

/**
 * The link a sponsor shares (Q15): the app's page of the publication, which anybody can open; before signing in it
 * shows the quiz and asks for an e-mail address only. The app is at this site's root.
 */
export function shareLink(publicationId: string, origin: string = window.location.origin): string {
  return `${origin}/p/${encodeURIComponent(publicationId)}`
}
