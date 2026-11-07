import type { KeyboardEvent, ReactNode } from 'react'

import { cn } from '../lib/utils'

/** A tab of a page (phase 17zj): a page of its own under the page's area, with an address of its own. */
export interface PageTab {
  key: string
  label: string
  /** Where the tab leads: tabs are links, so that each can be opened directly and bookmarked. */
  path: string
  /** A count after the label (things waiting there). */
  badge?: number
}

/** What a tab's link receives from {@link PageTabs}: pass it all on to the element rendered. */
export interface PageTabLinkProps {
  children: ReactNode
  className: string
  'aria-current'?: 'page'
  'data-testid': string
  /** Finds the links for the arrow keys, whatever element a router renders. */
  'data-slot': 'page-tab'
  onKeyDown: (event: KeyboardEvent<HTMLElement>) => void
}

export interface PageTabsProps {
  tabs: PageTab[]
  /** The tab whose page is shown. */
  current: string
  /** The navigation landmark's name (what the tabs switch between, e.g. the area's name). */
  label: string
  /** Renders a tab's link (a router's <Link>); a plain <a href> when absent. */
  renderLink?: (tab: PageTab, props: PageTabLinkProps) => ReactNode
  className?: string
}

function defaultLink(tab: PageTab, props: PageTabLinkProps) {
  return <a href={tab.path} {...props} />
}

/**
 * The row of tabs under a page's title (phase 17zj; Q108): the pages of one area, side by side. Links, not a tab
 * widget: each tab is a route that can be opened and bookmarked, and the current one is `aria-current="page"`. The
 * row scrolls sideways when it is wider than the screen; the arrow keys, Home and End move between the tabs.
 */
export function PageTabs({ tabs, current, label, renderLink = defaultLink, className }: PageTabsProps) {
  const move = (event: KeyboardEvent<HTMLElement>) => {
    const row = event.currentTarget.closest('[data-slot="page-tabs"]')
    const links = Array.from(row?.querySelectorAll<HTMLElement>('[data-slot="page-tab"]') ?? [])
    const at = links.indexOf(event.currentTarget)
    if (at < 0) return
    const next =
      event.key === 'ArrowRight' ? (at + 1) % links.length
        : event.key === 'ArrowLeft' ? (at - 1 + links.length) % links.length
          : event.key === 'Home' ? 0
            : event.key === 'End' ? links.length - 1
              : -1
    if (next < 0) return
    event.preventDefault()
    links[next].focus()
  }

  return (
    <nav aria-label={label} data-slot="page-tabs" className={cn('-mx-1 overflow-x-auto', className)}>
      <ul className="flex min-w-max gap-1 border-b px-1">
        {tabs.map((tab) => {
          const active = tab.key === current
          const content = (
            <>
              <span>{tab.label}</span>
              {tab.badge !== undefined && tab.badge > 0 && (
                <span className="bg-primary text-primary-foreground rounded-full px-1.5 text-xs leading-5 font-medium">
                  {tab.badge > 99 ? '99+' : tab.badge}
                </span>
              )}
            </>
          )
          return (
            <li key={tab.key} className="flex">
              {renderLink(tab, {
                children: content,
                className: cn(
                  'focus-visible:ring-ring -mb-px inline-flex items-center gap-1.5 border-b-2 px-3 py-2 text-sm',
                  'whitespace-nowrap outline-none focus-visible:ring-2 focus-visible:ring-offset-1',
                  active
                    ? 'border-primary text-foreground font-semibold'
                    : 'text-muted-foreground hover:text-foreground border-transparent',
                ),
                'aria-current': active ? 'page' : undefined,
                'data-testid': `page-tab-${tab.key}`,
                'data-slot': 'page-tab',
                onKeyDown: move,
              })}
            </li>
          )
        })}
      </ul>
    </nav>
  )
}
