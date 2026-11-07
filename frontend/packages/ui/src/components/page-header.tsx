import type { ReactNode } from 'react'
import type { LucideIcon } from 'lucide-react'

import { useIsMobile } from '../hooks/use-mobile'
import { cn } from '../lib/utils'
import { Fab, PageActionButton, type PageAction } from './page-action'

export interface PageHeaderProps {
  /** The page's one <h1>. */
  title: ReactNode
  /** One line under the title. */
  description?: ReactNode
  /**
   * The icon of the page's area before the title (phase 17zj; the same icon as the area's menu entry). Decorative:
   * the title names the page.
   */
  icon?: LucideIcon
  /**
   * The page's main action (phase 17zj): right after the title on wide screens, so that it stays near the title
   * however wide the screen; the floating button above the bottom menu on narrow screens.
   */
  primaryAction?: PageAction
  /** Other buttons for the page's actions, at the end of the title's line. */
  actions?: ReactNode
  /** The page's tabs under the description, usually {@link PageTabs}. */
  tabs?: ReactNode
  /** A breadcrumb above the title. */
  breadcrumb?: ReactNode
  className?: string
}

/**
 * The head of a page: breadcrumb, the area's icon, the title (the page's heading for screen readers; the shell
 * moves the focus to it when the route changes), the main action beside it, the description, other actions at the
 * end of the line, and the page's tabs. Without `icon`, `primaryAction` and `tabs` it is the head it always was.
 */
export function PageHeader({
  title,
  description,
  icon: Icon,
  primaryAction,
  actions,
  tabs,
  breadcrumb,
  className,
}: PageHeaderProps) {
  const mobile = useIsMobile()
  return (
    <header data-slot="page-header" className={cn('flex flex-col gap-2 bg-transparent pb-4', className)}>
      {breadcrumb}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-1">
          <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
            <h1
              className={cn('text-2xl font-semibold tracking-tight outline-none', Icon && 'flex items-center gap-2')}
              data-testid="page-title"
              data-slot="page-title"
              tabIndex={-1}
            >
              {Icon ? (
                <>
                  <Icon aria-hidden className="text-muted-foreground size-6 shrink-0" />
                  <span className="min-w-0">{title}</span>
                </>
              ) : (
                title
              )}
            </h1>
            {primaryAction && !mobile && <PageActionButton action={primaryAction} testId="page-primary-action" />}
          </div>
          {description && <p className="text-muted-foreground text-sm">{description}</p>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
      {tabs}
      {primaryAction && mobile && <Fab action={primaryAction} />}
    </header>
  )
}
