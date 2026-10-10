import { useSyncExternalStore } from 'react'

const MOBILE_BREAKPOINT = 768
const QUERY = `(max-width: ${MOBILE_BREAKPOINT - 1}px)`

function subscribe(onChange: () => void) {
  if (typeof window.matchMedia !== 'function') return () => {}
  const query = window.matchMedia(QUERY)
  query.addEventListener?.('change', onChange)
  return () => query.removeEventListener?.('change', onChange)
}

const narrow = () => window.innerWidth < MOBILE_BREAKPOINT

/** Whether the window is narrower than the `md` breakpoint (the sidebar then becomes a sheet). */
export function useIsMobile(): boolean {
  return useSyncExternalStore(subscribe, narrow, () => false)
}
