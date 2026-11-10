import type { MouseEvent, ReactElement, ReactNode } from 'react'
import { hrefOf, navigate } from '../qb/router'

/**
 * A link to a page of the Sponsor app for the platform's blocks that render links (`PageTabs`, `BottomBar`, `ShellNav`,
 * `GlobalCreate`): every property they hand over goes on the `<a>`; a plain click goes there without reloading,
 * modified clicks open a tab as usual. `onFollow` runs first (a menu closing).
 */
export function spaLink(path: string, { children, ...props }: { children: ReactNode; [key: string]: unknown },
  onFollow?: () => void): ReactElement {
  return (
    <a {...props} href={hrefOf(path)} onClick={(event: MouseEvent<HTMLAnchorElement>) => {
      onFollow?.()
      if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
      event.preventDefault()
      navigate(path)
    }}>
      {children}
    </a>
  )
}
