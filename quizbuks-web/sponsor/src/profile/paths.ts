/**
 * The sponsor hub (Q79, docs/quizbuks/plans/Q79-sponsor-hub.md): one group of pages under `/profile` with a
 * sub-navigation. The pages that were spread over the settings menu (onboarding, shop page, products, staff, mail
 * server) live here; their old addresses lead to the new ones.
 */
export const PROFILE_PATH = '/profile'

// F-C1c (the requester, 2026-11-07): the products moved to the asset library's "products" tab (`MOVED_PATHS`).
export const PROFILE_SECTIONS = ['overview', 'basics', 'page', 'staff', 'mail-server'] as const
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
  '/staff': 'staff',
  '/mail-server': 'mail-server',
}

/**
 * The addresses of pages that moved out of the hub and the menu into the asset library's tabs (F-C1c, the requester
 * 2026-11-07): the products (`/profile/products`, and `/products` before the hub) and the certifications. Old
 * bookmarks keep working; the certifications' went with them (F-S2c2, D6) and lead to the library.
 */
export const PRODUCTS_TAB_PATH = '/assets?tab=products'
export const MOVED_PATHS: Readonly<Record<string, string>> = {
  '/products': PRODUCTS_TAB_PATH,
  '/profile/products': PRODUCTS_TAB_PATH,
  '/profile/products/': PRODUCTS_TAB_PATH,
  '/certifications': '/assets',
  '/certifications/': '/assets',
}
