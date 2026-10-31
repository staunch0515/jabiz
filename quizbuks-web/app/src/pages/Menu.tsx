import { ChevronRight, type LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from '../qb/router'

/** A titled list of entries, each leading to a page of its own (the account, Q56; My Kudos, Q62). */
export function MenuList({ label, children, testId }: { label: string; children: ReactNode; testId?: string }) {
  return (
    <nav aria-label={label} className="flex flex-col gap-2" data-testid={testId}>
      <h2 className="text-muted-foreground px-1 text-sm font-medium">{label}</h2>
      <ul className="bg-card divide-border flex flex-col divide-y overflow-hidden rounded-lg border">{children}</ul>
    </nav>
  )
}

export function MenuItem({ to, icon: Icon, title, hint, testId, status }: {
  to: string
  icon: LucideIcon
  title: string
  hint?: string
  testId?: string
  /** The state the entry shows, for tests (`data-status`). */
  status?: string
}) {
  return (
    <li>
      <Link to={to} data-testid={testId} data-status={status} className="hover:bg-accent flex min-h-12 items-center gap-3 px-3 py-3">
        <Icon aria-hidden className="text-primary size-5 shrink-0" />
        <span className="flex min-w-0 flex-1 flex-col">
          <span className="text-sm font-medium">{title}</span>
          {hint && <span className="text-muted-foreground text-xs">{hint}</span>}
        </span>
        <ChevronRight aria-hidden className="text-muted-foreground size-4 shrink-0" />
      </Link>
    </li>
  )
}
