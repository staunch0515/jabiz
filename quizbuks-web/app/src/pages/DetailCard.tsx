import { cn } from '@jabiz/ui'
import type { LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'

/**
 * A block of the start page (Q34): a card whose title bar has its own background and a rule under it, so the title and
 * the content never run together. `flush` leaves the content without padding (a list that draws its own rows).
 */
export function DetailCard({ title, titleId, icon: Icon, testId, flush, action, className, children }: {
  title: string
  titleId: string
  icon?: LucideIcon
  testId?: string
  flush?: boolean
  /** Something at the end of the title bar (a count, a link). */
  action?: ReactNode
  className?: string
  children: ReactNode
}) {
  return (
    <section aria-labelledby={titleId} className={cn('bg-card overflow-hidden border shadow-xs', className)}
      data-testid={testId}>
      <div className="bg-muted flex items-center gap-2 border-b px-3 py-2">
        {Icon && <Icon aria-hidden className="text-primary size-4 shrink-0" />}
        <h2 id={titleId} className="flex-1 text-sm font-semibold">{title}</h2>
        {action}
      </div>
      <div className={cn(!flush && 'flex flex-col gap-2 p-3')}>{children}</div>
    </section>
  )
}
