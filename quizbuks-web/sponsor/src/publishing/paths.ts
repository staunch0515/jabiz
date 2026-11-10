import { profilePath } from '../profile/paths'
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
  if (!match || RESERVED.includes(match[1])) return null
  return decodeURIComponent(match[1])
}

/**
 * The tabs of a submitted publication's page (F-F3c; F-F3 plan 2.2): the overview is the publication's own address, the
 * analytics keep theirs (`/publications/<id>/analytics`), the others are under it. A draft has none (it is the wizard).
 */
export const PUBLICATION_TABS = ['overview', 'questions', 'audience', 'rewards', 'fees', 'analytics', 'share'] as const
export type PublicationTab = (typeof PUBLICATION_TABS)[number]
/** The tabs of the page itself (the analytics are a page of their own). */
export type PageOfPublicationTab = Exclude<PublicationTab, 'analytics'>
const PAGE_TABS: readonly string[] = ['questions', 'audience', 'rewards', 'fees', 'share']

/** A tab's address (the analytics', too). */
export function publicationTabPath(publicationId: string, tab: PublicationTab): string {
  const base = publicationPath(publicationId)
  return tab === 'overview' ? base : `${base}/${tab}`
}

/** The publication and tab of `/publications/<id>/<questions|audience|rewards|fees|share>`, otherwise null. */
export function publicationTabOf(path: string): { id: string; tab: PageOfPublicationTab } | null {
  const match = /^\/publications\/([^/]+)\/([^/]+)\/?$/.exec(path)
  if (!match || RESERVED.includes(match[1]) || !PAGE_TABS.includes(match[2])) return null
  return { id: decodeURIComponent(match[1]), tab: match[2] as PageOfPublicationTab }
}

/** The publications area's tabs under its address (F-F3): never a publication's key (those are UUIDs). */
const RESERVED: readonly string[] = ['map']

export const PUBLICATIONS_QUERY = ['qb', 'publications'] as const

/** The sponsor's own mail server (Q29): the page, and the key of what it reads. */
export const MAIL_SERVER_PATH = profilePath('mail-server')
export const MAIL_SERVER_QUERY = ['qb', 'mail-server'] as const
export const publicationQuery = (publicationId: string) => [...PUBLICATIONS_QUERY, 'one', publicationId] as const

/**
 * The link a sponsor shares (Q15): the app's page of the publication, which anybody can open; before signing in it
 * shows the quiz and asks for an e-mail address only. The app is under this site's /taker/ (Q30). It carries the
 * quiz's language (Q59b): a visitor new to the app sees it in that language.
 */
export const APP_BASE = '/taker/'

export function shareLink(publicationId: string, language?: string | null,
  origin: string = window.location.origin): string {
  const page = `${origin}${APP_BASE}p/${encodeURIComponent(publicationId)}`
  return language ? `${page}?lang=${encodeURIComponent(language)}` : page
}

/**
 * The link in a redeem code (Q22b): the app's page that collects the caller's prize at the sponsor whose code it is.
 * The key is the secret the server issued; the app is under this site's /taker/ (Q30).
 */
export function redeemLink(redeemKey: string, origin: string = window.location.origin): string {
  return `${origin}${APP_BASE}redeem/${encodeURIComponent(redeemKey)}`
}
