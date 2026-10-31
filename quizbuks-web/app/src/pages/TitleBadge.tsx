import { cn } from '@jabiz/ui'
import type { LucideIcon } from 'lucide-react'

/**
 * The round badge before a page's title (Q67): the account's initial, or the bottom bar's icon of the page, so the
 * pages of the bar look alike. Decorative: the title says what the page is.
 */
export function TitleBadge({ icon: Icon, children }: { icon?: LucideIcon; children?: string }) {
  return (
    <span aria-hidden data-testid="title-badge"
      className={cn('bg-primary text-primary-foreground flex size-12 shrink-0 items-center justify-center',
        'rounded-full text-lg font-semibold')}>
      {Icon ? <Icon className="size-6" /> : children}
    </span>
  )
}
