import type { PageAction } from '@jabiz/ui'
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
