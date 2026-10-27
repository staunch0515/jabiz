import { useCallback, useSyncExternalStore } from 'react'

/**
 * The embedded pages' own addresses (docs/quizbuks/shopify/S4-launch.md P-1): Shopify's admin shows the app's menu
 * (App Bridge's ui-nav-menu) and opens a page by its address, so each page has one under the base (/shopify/), and a
 * reload stays on it. A page also moves to another with {@link navigate}, as App Bridge does: through the history, the
 * query (Shopify's shop and host) kept.
 */
export type Page = 'overview' | 'campaigns' | 'plan'

export const PAGES: Page[] = ['overview', 'campaigns', 'plan']

const SEGMENT: Record<Page, string> = { overview: '', campaigns: 'campaigns', plan: 'plan' }

function base(): string {
  return import.meta.env.BASE_URL.endsWith('/') ? import.meta.env.BASE_URL : `${import.meta.env.BASE_URL}/`
}

/** The page an address shows; the overview for any other. */
export function pageOf(pathname: string): Page {
  const root = base()
  const rest = pathname.startsWith(root) ? pathname.slice(root.length) : ''
  const segment = rest.split('/')[0]
  return PAGES.find((page) => SEGMENT[page] === segment && segment !== '') ?? 'overview'
}

/** A page's address. */
export function pathOf(page: Page): string {
  return `${base()}${SEGMENT[page]}`
}

function subscribe(changed: () => void) {
  window.addEventListener('popstate', changed)
  return () => window.removeEventListener('popstate', changed)
}

/** Shows {@code page}: a new entry of the history, Shopify's query kept. */
export function navigate(page: Page) {
  if (pageOf(window.location.pathname) === page) return
  window.history.pushState(null, '', `${pathOf(page)}${window.location.search}`)
  window.dispatchEvent(new PopStateEvent('popstate'))
}

/** The page the address shows now, and a way to show another. */
export function usePage(): [Page, (page: Page) => void] {
  const page = useSyncExternalStore(subscribe, () => pageOf(window.location.pathname))
  return [page, useCallback((next: Page) => navigate(next), [])]
}
