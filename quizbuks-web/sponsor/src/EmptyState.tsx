import type { ReactNode } from 'react'
import { EmptyArt } from './qb/Illustrations'

/**
 * An empty list with a picture (Q32): the illustration, the words, and what to do next (a button). Goes into a
 * DataTable's `empty`, so it is a block inside the table's one cell.
 */
export function EmptyState({ children, action, testId }: { children: ReactNode; action?: ReactNode; testId?: string }) {
  return (
    <span className="flex flex-col items-center gap-3 py-6 text-center" data-testid={testId}>
      <EmptyArt className="h-24 w-28" />
      <span className="text-muted-foreground text-sm">{children}</span>
      {action}
    </span>
  )
}
