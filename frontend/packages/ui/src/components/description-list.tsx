import type { HTMLAttributes, ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

import { UI_NAMESPACE } from '../i18n'
import { cn } from '../lib/utils'
import { CopyButton } from './copy-button'

export interface DescriptionItem {
  /** Distinguishes the item among its siblings; the label when absent and a string. */
  key?: string
  label: ReactNode
  value: ReactNode
  /** Offers a button copying this text (a hash, a key) beside the value, named "Copy <label>". */
  copy?: string
}

export interface DescriptionListProps extends Omit<HTMLAttributes<HTMLDListElement>, 'children'> {
  items: DescriptionItem[]
  /** Label and value side by side (default) or the value under its label. */
  layout?: 'horizontal' | 'vertical'
  /**
   * Items side by side on wider screens (an import's figures): one column on phones, two from `sm`, this many from
   * `lg`. Each value is then under its label.
   */
  columns?: 1 | 2 | 3 | 4
}

const COLUMNS = {
  1: '',
  2: 'sm:grid-cols-2',
  3: 'sm:grid-cols-2 lg:grid-cols-3',
  4: 'sm:grid-cols-2 lg:grid-cols-4',
} as const

/**
 * Labels and their values (an operation's details, an entry at a point in time) as a description list (<dl>), so
 * screen readers read each value with its label. Replaces antd's `Descriptions`.
 */
export function DescriptionList({ items, layout = 'horizontal', columns, className, ...rest }: DescriptionListProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  const grid = columns !== undefined && columns > 1
  const horizontal = layout === 'horizontal' && !grid
  return (
    <dl
      data-slot="description-list"
      data-columns={grid ? columns : undefined}
      className={cn(
        'grid rounded-md border text-sm',
        grid ? cn('gap-x-6 gap-y-1 p-1', COLUMNS[columns]) : 'divide-border divide-y',
        horizontal && 'sm:grid-cols-[minmax(8rem,auto)_1fr]',
        className,
      )}
      {...rest}
    >
      {items.map((item, index) => (
        <div
          key={item.key ?? (typeof item.label === 'string' ? item.label : index)}
          data-slot="description-item"
          className={cn('grid gap-1 px-3 py-2', horizontal && 'sm:col-span-2 sm:grid-cols-subgrid sm:gap-4')}
        >
          <dt className="text-muted-foreground font-medium">{item.label}</dt>
          <dd className={cn('min-w-0 break-words', item.copy !== undefined && 'flex items-start gap-1')}>
            {item.copy !== undefined ? (
              <>
                <span className="min-w-0 break-all">{item.value}</span>
                <CopyButton
                  value={item.copy}
                  label={typeof item.label === 'string' ? t('copy.copyValue', { value: item.label }) : undefined}
                  className="-my-1 shrink-0"
                />
              </>
            ) : (
              item.value
            )}
          </dd>
        </div>
      ))}
    </dl>
  )
}
