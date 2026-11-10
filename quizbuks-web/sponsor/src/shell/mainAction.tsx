import type { PageAction } from '@jabiz/ui'
import { useEffect, useRef } from 'react'
import type { LucideIcon } from 'lucide-react'

/**
 * A page's main button as the head's `primaryAction` (F-F3d): beside the title on wide screens, the floating button
 * on narrow ones. The platform's button carries one fixed test id, so the element is made here to keep the page's own
 * (`new-quiz`, `topup-open` …) on both forms: a page's tests and the end-to-end specs find it by the same name.
 */
export function mainAction({ label, icon, testId, onClick, disabled }: {
  label: string
  icon: LucideIcon
  testId: string
  onClick: () => void
  disabled?: boolean
}): PageAction {
  return {
    label,
    icon,
    renderLink: ({ children, className, 'aria-label': ariaLabel }) => (
      <button type="button" className={className} aria-label={ariaLabel} data-testid={testId} onClick={onClick}
        disabled={disabled}>
        {children}
      </button>
    ),
  }
}

/** What a section hosted under a page's head hands up for that head's main button (F-F3d): open the editor, or not. */
export interface HostedAdd {
  open: () => void
  disabled?: boolean
}

/**
 * A section whose "add" belongs in the page head (the notices tab, the products tab) tells its host through `onAdd`
 * while it is on screen. Used alone (no `onAdd`), the section keeps its own button.
 */
export function useHostedAdd(onAdd: ((add: HostedAdd | null) => void) | undefined, open: () => void, disabled: boolean) {
  const latest = useRef(open)
  useEffect(() => {
    latest.current = open
  })
  useEffect(() => {
    onAdd?.({ open: () => latest.current(), disabled })
    return () => onAdd?.(null)
  }, [onAdd, disabled])
}
