import type { KeyboardEvent, ReactNode } from 'react'
import { hrefOf, navigate } from '../qb/router'

/**
 * A link of the frame for the platform's bottom menu and tabs, which pass on more than `qb/router`'s `Link` takes (the
 * arrow keys of the tabs, `data-slot`): followed without reloading, modified clicks open a tab as usual.
 */
export function FrameLink({ to, children, className, onKeyDown, ...rest }: {
  to: string
  children: ReactNode
  className?: string
  onKeyDown?: (event: KeyboardEvent<HTMLElement>) => void
  'aria-current'?: 'page'
  'aria-label'?: string
  'data-testid'?: string
  'data-slot'?: string
}) {
  return (
    <a
      href={hrefOf(to)}
      className={className}
      onKeyDown={onKeyDown}
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
