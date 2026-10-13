import type { ReactNode } from 'react'

import { cn } from '../lib/utils'
import { ToggleGroup, ToggleGroupItem } from './ui/toggle-group'

export interface SegmentedOption<V extends string = string> {
  value: V
  label: ReactNode
  disabled?: boolean
}

export interface SegmentedControlProps<V extends string = string> {
  value: V
  onChange: (value: V) => void
  options: SegmentedOption<V>[]
  /** The group's accessible name (what the choice is about); or name it with {@link 'aria-labelledby'}. */
  'aria-label'?: string
  'aria-labelledby'?: string
  className?: string
  'data-testid'?: string
}

/**
 * One of a few options, side by side (what a table shows: all rows, errors, duplicates): a radio group
 * (`role="radiogroup"`, each option `role="radio"` with `aria-checked`) built on shadcn's toggle group. Arrow keys
 * move between the options, Space or Enter chooses; one option is always chosen (choosing it again keeps it). The
 * chosen option is raised and bold, not only coloured. Replaces antd's `Segmented`.
 */
export function SegmentedControl<V extends string = string>({
  value,
  onChange,
  options,
  className,
  ...rest
}: SegmentedControlProps<V>) {
  return (
    <ToggleGroup
      type="single"
      value={value}
      // Radix empties a single group when its chosen item is pressed again; a segmented control keeps it.
      onValueChange={(next) => {
        if (next) onChange(next as V)
      }}
      data-slot="segmented-control"
      className={cn('bg-muted text-muted-foreground gap-0.5 rounded-lg p-[3px]', className)}
      {...rest}
    >
      {options.map((option) => (
        <ToggleGroupItem
          key={option.value}
          value={option.value}
          disabled={option.disabled}
          className={cn(
            'h-8 rounded-md px-3 first:rounded-md last:rounded-md',
            'text-foreground dark:text-muted-foreground hover:bg-background/60 hover:text-foreground',
            'data-[state=on]:bg-background data-[state=on]:text-foreground data-[state=on]:font-semibold data-[state=on]:shadow-sm',
            'dark:data-[state=on]:bg-input/30 dark:data-[state=on]:border-input border border-transparent',
          )}
        >
          {option.label}
        </ToggleGroupItem>
      ))}
    </ToggleGroup>
  )
}
