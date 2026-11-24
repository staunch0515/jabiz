import type { ReactNode } from 'react'
import { PlusIcon, type LucideIcon } from 'lucide-react'

import { cn } from '../lib/utils'
import { Button } from './ui/button'

/** What the element of a link-like action receives: pass it all on (to a router's <Link>). */
export interface PageActionLinkProps {
  children: ReactNode
  className?: string
  'aria-label'?: string
  'data-testid': string
}

/**
 * A page's main action (phase 17zj): next to the title on wide screens, the floating button above the bottom menu on
 * narrow ones. A button (`onClick`) or a link (`href`, rendered by `renderLink` when the application has a router).
 */
export interface PageAction {
  label: string
  /** The icon; the floating button shows only it (a plus when absent), named by the label. */
  icon?: LucideIcon
  onClick?: () => void
  href?: string
  renderLink?: (props: PageActionLinkProps) => ReactNode
  disabled?: boolean
}

function linkOf(action: PageAction, props: PageActionLinkProps): ReactNode {
  if (action.renderLink) return action.renderLink(props)
  return <a href={action.href} {...props} />
}

/** The main action as a button beside the title. */
export function PageActionButton({ action, testId }: { action: PageAction; testId: string }) {
  const Icon = action.icon
  const content = (
    <>
      {Icon && <Icon aria-hidden />}
      {action.label}
    </>
  )
  if (action.href !== undefined || action.renderLink) {
    return (
      <Button asChild size="sm">
        {linkOf(action, { children: content, 'data-testid': testId })}
      </Button>
    )
  }
  return (
    <Button size="sm" onClick={action.onClick} disabled={action.disabled} data-testid={testId}>
      {content}
    </Button>
  )
}

export interface FabProps {
  action: PageAction
  className?: string
  /**
   * Phase 17zz31 (12 §13.1): `timeline` keeps the button 20 px from the screen's side and from the bottom menu, as a
   * timeline's floating button is; `default` is the button as it always was, 16 px from both (`--fab-inset`). In an
   * {@link AppShell} whose bottom menu is a `BottomBar` of `variant="icons"` the shell asks for the 20 px itself.
   */
  variant?: 'default' | 'timeline'
  /**
   * The button's opacity (0 to 1, default 1), changed over 0.17 s like the bottom menu's `dim`: an application fades
   * both together as the page scrolls on. It stays where it is and can still be pressed.
   */
  dim?: number
}

/**
 * The floating action button of narrow screens (phase 17zj): the page's main action, bottom right, above the bottom
 * menu when there is one (`--bottom-bar-offset`, set by the shell to the bar's height) and the device's safe area. Only the icon shows;
 * the label names it for assistive technology and as its tooltip.
 */
export function Fab({ action, className, variant = 'default', dim }: FabProps) {
  const Icon = action.icon ?? PlusIcon
  const classes = cn(
    'fixed z-30 size-14 rounded-full shadow-lg [&_svg]:size-6',
    'right-(--fab-inset,1rem) bottom-[calc(var(--bottom-bar-offset,0px)+var(--fab-inset,1rem)+env(safe-area-inset-bottom))]',
    variant === 'timeline' && '[--fab-inset:1.25rem]',
    dim !== undefined && 'transition-opacity duration-[170ms] ease-linear',
    className,
  )
  const style = dim !== undefined ? { opacity: Math.min(1, Math.max(0, dim)) } : undefined
  const icon = <Icon aria-hidden />
  if (action.href !== undefined || action.renderLink) {
    return (
      <Button asChild size="icon" className={classes} style={style} title={action.label} data-variant={variant}>
        {linkOf(action, { children: icon, 'aria-label': action.label, 'data-testid': 'page-fab' })}
      </Button>
    )
  }
  return (
    <Button
      size="icon"
      className={classes}
      style={style}
      data-variant={variant}
      onClick={action.onClick}
      disabled={action.disabled}
      aria-label={action.label}
      title={action.label}
      data-testid="page-fab"
      data-slot="fab"
    >
      {icon}
    </Button>
  )
}
