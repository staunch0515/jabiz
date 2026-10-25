import type { ReactNode } from 'react'
import { EmptyArt } from './qb/Illustrations'

/**
 * An empty list with a picture (Q32): the illustration and the words (the page's own buttons say what to do next).
 * Goes into a DataTable's `empty`, so it is a block inside the table's one cell.
 */
export function EmptyState({ children, testId }: { children: ReactNode; testId?: string }) {
  return (
    <span className="flex flex-col items-center gap-3 py-6 text-center" data-testid={testId}>
      <EmptyArt className="h-24 w-28" />
      <span className="text-muted-foreground text-sm">{children}</span>
    </span>
  )
}
