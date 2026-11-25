import { XSCROLL } from './xspec'

/*
 * How the header and the bottom menu of a phone answer the page's scrolling (Q113 §9.1a, measured on x.com): down, the
 * header follows the finger after a little slack, one to one, until it is out; the bottom menu fades with it. Up, it
 * does not follow: only one quick upward swipe brings everything back, as does reaching the top. A pure function of
 * the positions the page reports, so that every row of the spec is a unit test (scrollChrome.test.ts).
 */

/** Where the header is and what the last moves were. */
export interface ScrollChrome {
  /** The document's scroll position last seen (px). */
  y: number
  /** How far the header is moved out (0 shown, `XSCROLL.maxOffset` out). */
  offset: number
  /** Downward travel since the header was last all shown, spent on the slack first. */
  slack: number
  /** Upward travel of the swipe going on (moves at most `swipeGap` ms apart). */
  upRun: number
  /** When the last move came (ms, any clock). */
  at: number
}

/** The start: at the top, everything shown. */
export const SHOWN: ScrollChrome = { y: 0, offset: 0, slack: 0, upRun: 0, at: 0 }

/** The next state after the page reports the scroll position `y` at the time `at` (ms). */
export function nextScrollChrome(state: ScrollChrome, y: number, at: number): ScrollChrome {
  // At the top (or pulled past it), everything is shown.
  if (y <= 0) return { ...SHOWN, at }
  const delta = y - state.y
  if (delta > 0) {
    if (state.offset === 0) {
      const slack = state.slack + delta
      return { y, offset: clampOffset(slack - XSCROLL.slack), slack, upRun: 0, at }
    }
    return { y, offset: clampOffset(state.offset + delta), slack: state.slack, upRun: 0, at }
  }
  if (delta < 0) {
    const run = (at - state.at <= XSCROLL.swipeGap ? state.upRun : 0) - delta
    if (run > XSCROLL.revealDistance) return { y, offset: 0, slack: 0, upRun: 0, at }
    // The header stays where it is; while it is all shown, moving up gives back the slack spent.
    const slack = state.offset === 0 ? Math.max(0, state.slack + delta) : state.slack
    return { y, offset: state.offset, slack, upRun: run, at }
  }
  return { ...state, at }
}

/** The bottom menu's (and the floating button's) opacity for the header's offset: 1 shown, 0.3 out. */
export function chromeOpacity(offset: number): number {
  return 1 - (1 - XSCROLL.minOpacity) * (clampOffset(offset) / XSCROLL.maxOffset)
}

function clampOffset(value: number): number {
  return Math.min(XSCROLL.maxOffset, Math.max(0, value))
}
