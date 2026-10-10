import type { HTMLAttributes, ReactNode } from 'react'

import { cn, UI_SCOPE } from '../lib/utils'

export type TimelineTone = 'default' | 'primary' | 'muted' | 'destructive' | 'success' | 'warning'

export interface TimelineItem {
  key: string | number
  /** The colour of the item's dot: what kind of event it is (a deletion, something scheduled). */
  tone?: TimelineTone
  content: ReactNode
}

export interface TimelineProps extends Omit<HTMLAttributes<HTMLOListElement>, 'children'> {
  items: TimelineItem[]
}

const DOTS: Record<TimelineTone, string> = {
  default: 'bg-foreground',
  primary: 'bg-primary',
  muted: 'bg-muted-foreground',
  destructive: 'bg-destructive',
  success: 'bg-success',
  warning: 'bg-warning',
}

/**
 * Events in order (a history's versions) as an ordered list: a dot and a line on the side, the content beside it.
 * The dot's colour is decoration; say in the content what it means (a "scheduled" badge). Replaces antd's
 * `Timeline`.
 */
export function Timeline({ items, className, ...rest }: TimelineProps) {
  return (
    <ol data-slot="timeline" className={cn(UI_SCOPE, 'flex flex-col', className)} {...rest}>
      {items.map((item, index) => (
        <li key={item.key} data-slot="timeline-item" className="relative flex gap-3 pb-6 last:pb-0">
          <div aria-hidden className="flex w-3 shrink-0 flex-col items-center">
            <span className={cn(UI_SCOPE, 'mt-1.5 size-2.5 shrink-0 rounded-full', DOTS[item.tone ?? 'primary'])} />
            {index < items.length - 1 && <span className="bg-border mt-1 w-px flex-1" />}
          </div>
          <div className="min-w-0 flex-1">{item.content}</div>
        </li>
      ))}
    </ol>
  )
}
