import { Button } from '@jabiz/ui'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { useId, type ReactNode } from 'react'

/**
 * A row that opens into a panel (Q78): a button carrying `aria-expanded` and `aria-controls`, and the panel rendered
 * only while open. Built from the kit's `Button`, as `@jabiz/ui` has no accordion yet (platform decision D34: no
 * copied components); the kit's accordion replaces it when it comes.
 */
export function Disclosure({ expanded, onToggle, summary, label, actions, children, testId }: {
  expanded: boolean
  onToggle: () => void
  /** What the row shows while closed and as the panel's heading while open. */
  summary: ReactNode
  /** The toggle's accessible name (the summary is visual). */
  label: string
  /** Buttons on the row, usable without opening it. */
  actions?: ReactNode
  children: ReactNode
  testId?: string
}) {
  const id = useId()
  return (
    <div className="bg-card flex flex-col rounded-md border" data-testid={testId} data-expanded={expanded}>
      <div className="flex flex-wrap items-center gap-2 p-2">
        <Button type="button" variant="ghost" className="min-w-0 flex-1 justify-start text-left font-normal"
          aria-expanded={expanded} aria-controls={expanded ? `${id}-panel` : undefined} aria-label={label}
          data-testid={testId ? `${testId}-toggle` : undefined} onClick={onToggle}>
          {expanded ? <ChevronDown aria-hidden className="shrink-0" /> : <ChevronRight aria-hidden className="shrink-0" />}
          <span className="flex min-w-0 flex-1 flex-wrap items-center gap-x-3 gap-y-1">{summary}</span>
        </Button>
        {actions && <div className="flex flex-wrap items-center gap-1">{actions}</div>}
      </div>
      {expanded && (
        <div id={`${id}-panel`} className="border-t p-4" data-testid={testId ? `${testId}-detail` : undefined}>
          {children}
        </div>
      )}
    </div>
  )
}
