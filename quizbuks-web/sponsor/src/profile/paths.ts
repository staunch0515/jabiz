/**
 * The sponsor hub (Q79, docs/quizbuks/plans/Q79-sponsor-hub.md): one group of pages under `/profile` with a
 * sub-navigation. The pages that were spread over the settings menu (onboarding, shop page, products, staff, mail
 * server) live here; their old addresses lead to the new ones.
 */
export const PROFILE_PATH = '/profile'

export const PROFILE_SECTIONS = ['overview', 'basics', 'page', 'products', 'staff', 'mail-server'] as const
export type ProfileSection = (typeof PROFILE_SECTIONS)[number]

/** The address of a section; the overview is the hub's own. */
export function profilePath(section: ProfileSection): string {
  return section === 'overview' ? PROFILE_PATH : `${PROFILE_PATH}/${section}`
}

/** The section a path names; null for a path outside the hub (or an unknown section). */
export function profileSectionOf(path: string): ProfileSection | null {
  if (path === PROFILE_PATH || path === `${PROFILE_PATH}/`) return 'overview'
  if (!path.startsWith(`${PROFILE_PATH}/`)) return null
  const rest = path.slice(PROFILE_PATH.length + 1).replace(/\/$/, '')
  return (PROFILE_SECTIONS as readonly string[]).includes(rest) && rest !== 'overview' ? rest as ProfileSection : null
}

/** The addresses the pages had before the hub, each leading to its section (bookmarks and the PWA's start). */
export const LEGACY_PROFILE_PATHS: Readonly<Record<string, ProfileSection>> = {
  '/onboarding': 'basics',
  '/page': 'page',
  '/products': 'products',
  '/staff': 'staff',
  '/mail-server': 'mail-server',
}
