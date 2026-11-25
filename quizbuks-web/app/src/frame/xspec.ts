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
  /** Where the title starts in either head (the prototype's 72 px: after the avatar, or after the way back). */
  titleInset: 72,
  /** §9.1 "头部": the header's white, 85 % opaque, blurred under it by 12 px (§9.1a). */
  headerOpacity: 0.85,
  headerBlur: 12,
  /** §9.1 "底部栏（手机）": icons alone, 26 px, on a bar of 53 px (the platform's `BottomBar` `variant="icons"`). */
  bottomBar: 53,
  bottomIcon: 26,
  /** §9.1 "悬浮按钮": 56 px round, 20 px from the side and from the bottom menu (the platform's `Fab` `timeline`). */
  fab: 56,
  fabInset: 20,
  /** §9.1 "侧边抽屉": from the left, about 280 px wide; its rows' text 20 px bold. */
  drawer: 280,
  drawerText: 20,
  /** §9.1 "宽屏": the left column, the middle column, the right one and the gap between the two. */
  rail: 275,
  column: 600,
  aside: 350,
  asideGap: 30,
  /** The left column of a middle width, its icons alone (§5 "中等宽度"; x.com's own, 88 px, the spec measured none). */
  railCompact: 88,
  /** The left column's mark, its rows (58 px, a pill of 50 inside, 12 px in, the icon 26, the text 20, 20 apart). */
  railMark: 52,
  railItem: 58,
  railPill: 50,
  railPillInset: 12,
  railText: 20,
  railGap: 20,
  /** The left column's main button: 233 × 52 px, near black, 17 px bold (scanning, §9.2). */
  railButtonWidth: 233,
  railButtonHeight: 52,
  railButtonText: 17,
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
    '--x-title-inset': `${XSPEC.titleInset}px`,
    '--x-header-opacity': `${XSPEC.headerOpacity * 100}%`,
    '--x-header-blur': `${XSPEC.headerBlur}px`,
    '--x-header-transition': `${XSCROLL.headerTransition}s`,
    '--x-drawer': `${XSPEC.drawer}px`,
    '--x-drawer-text': `${XSPEC.drawerText}px`,
    '--x-rail': `${XSPEC.rail}px`,
    '--x-rail-compact': `${XSPEC.railCompact}px`,
    '--x-column': `${XSPEC.column}px`,
    '--x-aside': `${XSPEC.aside}px`,
    '--x-aside-gap': `${XSPEC.asideGap}px`,
    '--x-rail-mark': `${XSPEC.railMark}px`,
    '--x-rail-item': `${XSPEC.railItem}px`,
    '--x-rail-pill': `${XSPEC.railPill}px`,
    '--x-rail-pill-inset': `${XSPEC.railPillInset}px`,
    '--x-rail-icon': `${XSPEC.bottomIcon}px`,
    '--x-rail-text': `${XSPEC.railText}px`,
    '--x-rail-gap': `${XSPEC.railGap}px`,
    '--x-rail-button-width': `${XSPEC.railButtonWidth}px`,
    '--x-rail-button-height': `${XSPEC.railButtonHeight}px`,
    '--x-rail-button-text': `${XSPEC.railButtonText}px`,
  } as CSSProperties
}
