import { sitePath } from '@jabiz/client'
import { useSyncExternalStore } from 'react'

/**
 * A router of a handful of pages, enough for the portal: the platform's sign-in blocks need none (decision D43,
 * ROADMAP 16c-3), so an application's SPA may bring its own or none. Paths are the portal's own (`/orders`); the
 * address bar has them under the build's base (`/portal/orders`).
 */
export const BASE = import.meta.env.BASE_URL

const CHANGE = 'portal:navigate'

/** The page's path within the portal, from the address bar. */
export function currentPath(pathname: string = window.location.pathname): string {
  if (BASE !== '/' && (pathname + '/').startsWith(BASE)) return '/' + pathname.slice(BASE.length)
  return pathname || '/'
}

/** The address of a page of the portal. */
export function hrefOf(path: string): string {
  return BASE + path.replace(/^\//, '')
}

/**
 * Goes to a page: a path of the portal (`/orders`), or a path of this site (`/portal/orders`, as the blocks give the
 * entry's app-path) — anything else (another site) is refused for the portal's home.
 */
export function navigate(path: string, options: { replace?: boolean } = {}) {
  const site = sitePath(path, BASE)
  const target = BASE !== '/' && (site + '/').startsWith(BASE) ? site : hrefOf(site)
  if (options.replace) window.history.replaceState(null, '', target)
  else window.history.pushState(null, '', target)
  window.dispatchEvent(new Event(CHANGE))
}

function subscribe(onChange: () => void) {
  window.addEventListener('popstate', onChange)
  window.addEventListener(CHANGE, onChange)
  return () => {
    window.removeEventListener('popstate', onChange)
    window.removeEventListener(CHANGE, onChange)
  }
}

/** The current page's path (see {@link currentPath}) and query, following navigation and the browser's back button. */
export function useLocation(): { path: string; search: string } {
  const href = useSyncExternalStore(subscribe, () => window.location.pathname + window.location.search, () => '/')
  const url = new URL(href, window.location.origin)
  return { path: currentPath(url.pathname), search: url.search }
}

/** The navigation of the sign-in blocks of `@jabiz/ui`. */
export const navigation = { onNavigate: (path: string) => navigate(path), hrefOf }
