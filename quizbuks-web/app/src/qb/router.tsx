import { sitePath } from '@jabiz/client'
import { cn } from '@jabiz/ui'
import { useEffect, useSyncExternalStore, type ReactNode } from 'react'

/**
 * A router of a handful of pages, as the platform's demo portal has (docs/guide/app-spa.md section 5): the sign-in
 * blocks of `@jabiz/ui` need none. Paths are the SPA's own (`/account`); the address bar has them under the build's
 * base (`/sponsor/account` in the sponsor console, `/account` in the app at the root).
 */
export const BASE = import.meta.env.BASE_URL

const CHANGE = 'qb:navigate'

/** The page's path within the SPA, from the address bar. */
export function currentPath(pathname: string = window.location.pathname): string {
  if (BASE !== '/' && (pathname + '/').startsWith(BASE)) return '/' + pathname.slice(BASE.length)
  return pathname || '/'
}

/** The address of a page of the SPA. */
export function hrefOf(path: string): string {
  return BASE + path.replace(/^\//, '')
}

/**
 * Goes to a page: a path of the SPA (`/account`), or a path of this site under the SPA's base (as the blocks give the
 * entry's app-path). Anything else (another site) goes to the SPA's home instead.
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

/** Goes to `to` once rendered (a redirect). */
export function Redirect({ to }: { to: string }) {
  useEffect(() => navigate(to, { replace: true }), [to])
  return null
}

/** A link to a page of the SPA, followed without reloading (modified clicks open a tab as usual). */
export function Link({ to, className, children, current, ...rest }: {
  to: string
  className?: string
  children: ReactNode
  /** Marks the link as the current page (`aria-current="page"`). */
  current?: boolean
  'data-testid'?: string
}) {
  return (
    <a
      href={hrefOf(to)}
      aria-current={current ? 'page' : undefined}
      className={cn(className)}
      onClick={(event) => {
        if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
        event.preventDefault()
        navigate(to)
      }}
      {...rest}
    >
      {children}
    </a>
  )
}
