import rules from './legacy-paths.json'

/**
 * An old address of the Sponsor app and where it leads now (F-F3; Q79's `LEGACY_PROFILE_PATHS` and F-C1c's
 * `MOVED_PATHS` are entries of the same list). Bookmarks, links in mails already sent and Stripe's return addresses of
 * Checkouts started before keep working. Every entry carries an example and where it should end; the unit test and the
 * e2e test (`e2e/tests/sponsor-areas.spec.ts`) open each one.
 */
export interface LegacyRule {
  from: string
  to: string
  /** `prefix`: the rest of the path after `from` follows `to` (an id); exact otherwise. */
  match?: 'exact' | 'prefix'
  /** Only when the old address's `?tab=` is this (the asset library's and message centre's tabs). */
  tab?: string
  /** The old address's query goes along (Stripe's `?checkout=`, the transactions' filters). */
  keepSearch?: boolean
  example: string
  expect: string
}

export const LEGACY_RULES: readonly LegacyRule[] = rules as LegacyRule[]

/** The path without a trailing slash (the root stays `/`). */
const trimmed = (path: string) => (path.length > 1 ? path.replace(/\/+$/, '') : path)

/** Where an old address leads now, with its query when the entry keeps it; null for an address that is not old. */
export function legacyTarget(path: string, search: string): string | null {
  const at = trimmed(path)
  const tab = new URLSearchParams(search).get('tab')
  for (const rule of LEGACY_RULES) {
    const query = rule.keepSearch ? search : ''
    if (rule.match === 'prefix') {
      if (at.startsWith(rule.from) && at.length > rule.from.length) return rule.to + at.slice(rule.from.length) + query
    } else if (at === rule.from && (rule.tab === undefined || rule.tab === tab)) {
      return rule.to + query
    }
  }
  return null
}
