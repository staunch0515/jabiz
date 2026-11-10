/**
 * The sponsor hub (Q79, docs/quizbuks/plans/Q79-sponsor-hub.md): one group of pages under `/profile` with a
 * sub-navigation. The pages that were spread over the settings menu (onboarding, shop page, products, staff, mail
 * server) live here; their old addresses lead to the new ones.
 */
export const PROFILE_PATH = '/profile'

// F-C1c (the requester, 2026-11-07): the products moved to the asset library's "products" tab (`MOVED_PATHS`).
// F-S4c: the challenges of the Sponsor's operator, answered here (the plan's "challenges" tab of the hub).
// F-M3: the company verification, beside the basics it verifies.
export const PROFILE_SECTIONS = ['overview', 'basics', 'verification', 'page', 'staff', 'mail-server', 'challenges'] as const
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

/**
 * The products' tab of the asset library (F-C1c, the requester 2026-11-07). The addresses the hub's pages had before
 * (Q79) and those of the pages that moved out of it are in the one list of old addresses (`shell/legacy-paths.json`,
 * F-F3).
 */
export const PRODUCTS_TAB_PATH = '/assets/products'
