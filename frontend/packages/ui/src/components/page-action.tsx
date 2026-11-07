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
}

/**
 * The floating action button of narrow screens (phase 17zj): the page's main action, bottom right, above the bottom
 * menu when there is one (`--bottom-bar-offset`, set by the shell) and the device's safe area. Only the icon shows;
 * the label names it for assistive technology and as its tooltip.
 */
export function Fab({ action, className }: FabProps) {
  const Icon = action.icon ?? PlusIcon
  const classes = cn(
    'fixed right-4 z-30 size-14 rounded-full shadow-lg [&_svg]:size-6',
    'bottom-[calc(var(--bottom-bar-offset,0px)+1rem+env(safe-area-inset-bottom))]',
    className,
  )
  const icon = <Icon aria-hidden />
  if (action.href !== undefined || action.renderLink) {
    return (
      <Button asChild size="icon" className={classes} title={action.label}>
        {linkOf(action, { children: icon, 'aria-label': action.label, 'data-testid': 'page-fab' })}
      </Button>
    )
  }
  return (
    <Button
      size="icon"
      className={classes}
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
