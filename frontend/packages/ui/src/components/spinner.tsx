import { Loader2Icon } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import { UI_NAMESPACE } from '../i18n'
import { cn, UI_SCOPE } from '../lib/utils'

export interface SpinnerProps {
  /** What is loading, for screen readers; "Loading…" when absent. */
  label?: string
  /** Shows the label next to the spinner, not only to screen readers. */
  showLabel?: boolean
  className?: string
}

/** Something is loading: a turning icon in a polite status region that names what it waits for. */
export function Spinner({ label, showLabel = false, className }: SpinnerProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  const text = label ?? t('loading')
  return (
    <span
      role="status"
      data-slot="spinner"
      className={cn(UI_SCOPE, 'text-muted-foreground inline-flex items-center gap-2 text-sm', className)}
    >
      <Loader2Icon aria-hidden className={cn(UI_SCOPE, 'size-4 animate-spin motion-reduce:animate-none')} />
      <span className={showLabel ? undefined : 'sr-only'}>{text}</span>
    </span>
  )
}
