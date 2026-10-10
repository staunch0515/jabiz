import type { ReactNode } from 'react'

import { cn, UI_SCOPE } from '../lib/utils'

export interface PageHeaderProps {
  /** The page's one <h1>. */
  title: ReactNode
  description?: ReactNode
  /** Buttons for the page's actions, at the end of the line. */
  actions?: ReactNode
  /** A breadcrumb above the title. */
  breadcrumb?: ReactNode
  className?: string
}

/** The head of a page: breadcrumb, title (the page's heading for screen readers), description and actions. */
export function PageHeader({ title, description, actions, breadcrumb, className }: PageHeaderProps) {
  return (
    <header data-slot="page-header" className={cn(UI_SCOPE, 'flex flex-col gap-2 bg-transparent pb-4', className)}>
      {breadcrumb}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-1">
          <h1 className="text-2xl font-semibold tracking-tight" data-testid="page-title">
            {title}
          </h1>
          {description && <p className="text-muted-foreground text-sm">{description}</p>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
    </header>
  )
}
