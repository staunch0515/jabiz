import type { CSSProperties } from 'react'

/*
 * The timeline's layout as measured on x.com (docs/quizbuks/plans/Q113-taker-ui-tone.md section 9, 2026-11-24): every
 * number of the spec the frame uses, named once. Pages and components read the names, never the numbers (the lint
 * rule in eslint.config.js refuses the pixel values in `frame/`), and the geometry test (e2e x-spec.spec.ts) measures
 * the real pages against the same table. Phases of Q113 add the rows of their own pages here.
 */
export const XSPEC = {
  /** §9.1 "头部": the sticky header's row, and the row of tabs under it. */
  headerHeight: 53,
  tabsHeight: 53,
  /** §9.1 "一级页头部": the avatar that opens the drawer, its distance from the side, the title's size. */
  avatar: 32,
  sideInset: 16,
  titleSize: 20,
  /** §9.1 "子页头部": the back button, its distance from the side, the line under the title. */
  backButton: 36,
  backInset: 8,
  noteSize: 13,
  /** §9.1 "头部": the header's white, 85 % opaque, blurred under it by 12 px (§9.1a). */
  headerOpacity: 0.85,
  headerBlur: 12,
} as const

/** §9.1a: how the header and the bottom menu answer the page's scrolling on a phone (`scrollChrome.ts`). */
export const XSCROLL = {
  /** Scrolled down this far from where the header was shown before it starts to leave. */
  slack: 20,
  /** The header leaves one to one with the scrolling, at most this far (the header and its tabs). */
  maxOffset: 106,
  /** One upward move longer than this brings everything back; shorter ones, however many, do not. */
  revealDistance: 50,
  /** Moves of one swipe come this close together; a longer pause starts another swipe. */
  swipeGap: 150,
  /** The bottom menu's opacity when the header is all the way out. */
  minOpacity: 0.3,
  /** The header's transition, and the bottom menu's (s). */
  headerTransition: 0.35,
  barTransition: 0.17,
} as const

/**
 * The spec's lengths as CSS variables on the frame (`--x-…`), for the classes of its parts: `h-(--x-header)` and the
 * like. One source for the numbers above and the stylesheet.
 */
export function xspecVariables(): CSSProperties {
  return {
    '--x-header': `${XSPEC.headerHeight}px`,
    '--x-tabs': `${XSPEC.tabsHeight}px`,
    '--x-avatar': `${XSPEC.avatar}px`,
    '--x-side': `${XSPEC.sideInset}px`,
    '--x-title': `${XSPEC.titleSize}px`,
    '--x-back': `${XSPEC.backButton}px`,
    '--x-back-inset': `${XSPEC.backInset}px`,
    '--x-note': `${XSPEC.noteSize}px`,
    '--x-header-opacity': `${XSPEC.headerOpacity * 100}%`,
    '--x-header-blur': `${XSPEC.headerBlur}px`,
    '--x-header-transition': `${XSCROLL.headerTransition}s`,
  } as CSSProperties
}
