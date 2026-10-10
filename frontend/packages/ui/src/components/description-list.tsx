import type { HTMLAttributes, ReactNode } from 'react'

import { cn, UI_SCOPE } from '../lib/utils'

export interface DescriptionItem {
  /** Distinguishes the item among its siblings; the label when absent and a string. */
  key?: string
  label: ReactNode
  value: ReactNode
}

export interface DescriptionListProps extends Omit<HTMLAttributes<HTMLDListElement>, 'children'> {
  items: DescriptionItem[]
  /** Label and value side by side (default) or the value under its label. */
  layout?: 'horizontal' | 'vertical'
}

/**
 * Labels and their values (an operation's details, an entry at a point in time) as a description list (<dl>), so
 * screen readers read each value with its label. Replaces antd's `Descriptions`.
 */
export function DescriptionList({ items, layout = 'horizontal', className, ...rest }: DescriptionListProps) {
  return (
    <dl
      data-slot="description-list"
      className={cn(
        UI_SCOPE,
        'divide-border grid divide-y rounded-md border text-sm',
        layout === 'horizontal' && 'sm:grid-cols-[minmax(8rem,auto)_1fr]',
        className,
      )}
      {...rest}
    >
      {items.map((item, index) => (
        <div
          key={item.key ?? (typeof item.label === 'string' ? item.label : index)}
          data-slot="description-item"
          className={cn('grid gap-1 px-3 py-2', layout === 'horizontal' && 'sm:col-span-2 sm:grid-cols-subgrid sm:gap-4')}
        >
          <dt className="text-muted-foreground font-medium">{item.label}</dt>
          <dd className="min-w-0 break-words">{item.value}</dd>
        </div>
      ))}
    </dl>
  )
}
