import type { HTMLAttributes, ReactNode } from 'react'
import { CheckIcon } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import { UI_NAMESPACE } from '../i18n'
import { cn, UI_SCOPE } from '../lib/utils'

export interface StepItem {
  /** Distinguishes the step among its siblings; its position when absent. */
  key?: string
  title: ReactNode
  description?: ReactNode
}

export interface StepsProps extends Omit<HTMLAttributes<HTMLOListElement>, 'children'> {
  items: StepItem[]
  /** The index of the current step; those before it are complete. */
  current: number
}

/**
 * The steps of a task done in order (an import: file, columns, preview, result) as an ordered list: the current step
 * carries `aria-current="step"`, a complete one a check mark and the words "completed" for screen readers, so its
 * state is never told by colour alone. The list is named "Progress" unless given an `aria-label`. Replaces antd's
 * `Steps`.
 */
export function Steps({ items, current, className, ...rest }: StepsProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  return (
    <ol
      data-slot="steps"
      aria-label={rest['aria-labelledby'] ? undefined : t('steps.label')}
      className={cn(UI_SCOPE, 'flex flex-col gap-3 sm:flex-row sm:items-start sm:gap-2', className)}
      {...rest}
    >
      {items.map((item, index) => {
        const state = index < current ? 'complete' : index === current ? 'current' : 'upcoming'
        return (
          <li
            key={item.key ?? index}
            data-slot="step"
            data-state={state}
            aria-current={state === 'current' ? 'step' : undefined}
            className="flex min-w-0 flex-1 items-start gap-2"
          >
            <span
              aria-hidden
              className={cn(
                UI_SCOPE,
                'flex size-7 shrink-0 items-center justify-center rounded-full border text-sm font-medium',
                state === 'complete' && 'border-primary text-primary',
                state === 'current' && 'border-primary bg-primary text-primary-foreground',
                state === 'upcoming' && 'border-border text-muted-foreground',
              )}
            >
              {state === 'complete' ? <CheckIcon className="size-4" /> : index + 1}
            </span>
            <span className="flex min-w-0 flex-col pt-0.5">
              <span className={cn('text-sm', state === 'current' ? 'text-foreground font-semibold' : 'text-muted-foreground')}>
                {item.title}
                {state === 'complete' && <span className="sr-only"> ({t('steps.completed')})</span>}
              </span>
              {item.description && <span className="text-muted-foreground text-xs">{item.description}</span>}
            </span>
            {index < items.length - 1 && <span aria-hidden className="bg-border mt-3.5 hidden h-px flex-1 sm:block" />}
          </li>
        )
      })}
    </ol>
  )
}
