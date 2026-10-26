/**
 * The test system's banner in the app's narrow frame (Q34): small type on one line, the explanation cut with an
 * ellipsis rather than wrapped, so the stripe never takes two lines of a phone's (or the phone-sized window's) height.
 * The environment's name, the part that matters, always shows in full.
 */
export const ONE_LINE_BANNER = 'py-1 text-xs whitespace-nowrap [&>span:last-child]:min-w-0 [&>span:last-child]:truncate'
