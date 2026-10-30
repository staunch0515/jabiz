import { BASE } from '../qb/router'
import { itemPage } from '../routes'

/** What a scanned code may open in the app: a publication (a shop's poster) or a sponsor's redeem code. */
export interface ScanTarget {
  page: 'p' | 'redeem'
  id: string
}

/**
 * The page of the app a scanned text links to, or null for anything else (Q52). Only this site's own links count: a
 * code is anybody's to print, so a link elsewhere, a page of another kind or plain text is never opened or followed.
 * Codes printed before the app moved under its path (Q30) link to the site's root; the server redirects those, and
 * they are taken the same.
 */
export function scanTarget(text: string, origin: string = window.location.origin, base: string = BASE): ScanTarget | null {
  let url: URL
  try {
    url = new URL(text.trim())
  } catch {
    return null
  }
  if (url.origin !== origin) return null
  const prefix = base.endsWith('/') ? base.slice(0, -1) : base
  const path = prefix && url.pathname.startsWith(`${prefix}/`) ? url.pathname.slice(prefix.length) : url.pathname
  const item = itemPage(path)
  if (!item || (item.page !== 'p' && item.page !== 'redeem')) return null
  return { page: item.page, id: item.id }
}

/** The app's path of a scanned target. */
export function targetPath(target: ScanTarget): string {
  return `/${target.page}/${encodeURIComponent(target.id)}`
}
