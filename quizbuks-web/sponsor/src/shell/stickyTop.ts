import { useEffect } from 'react'

/** The CSS variable with the height of what stays at the top of the page (the environment's banner and the header). */
export const STICKY_TOP_VAR = '--qb-sticky-top'

/**
 * Keeps {@link STICKY_TOP_VAR} at the height of the frame's sticky top (F-F3; WCAG 2.4.11, focus not obscured): the
 * page's `scroll-padding-top` (index.css) uses it, so a control the browser scrolls to (Tab, `scrollIntoView`, a find)
 * is never left under the header. The height follows the banner and the header as they change.
 */
export function useStickyTop() {
  useEffect(() => {
    const header = document.querySelector('[data-slot="app-shell-header"]')
    const sticky = header?.parentElement
    if (!sticky) return
    const root = document.documentElement
    const update = () => root.style.setProperty(STICKY_TOP_VAR, `${Math.ceil(sticky.getBoundingClientRect().height)}px`)
    update()
    const observer = new ResizeObserver(update)
    observer.observe(sticky)
    return () => {
      observer.disconnect()
      root.style.removeProperty(STICKY_TOP_VAR)
    }
  }, [])
}
