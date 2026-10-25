import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@jabiz/ui'
import type { LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'

/**
 * A key figure: its name, the value large, and an optional line of context (what it includes, since when). Not a
 * chart: one number reads best as a number.
 */
export function Metric({ label, value, hint, icon: Icon, testId }: {
  label: string
  value: ReactNode
  hint?: ReactNode
  /** A picture of what the figure is about (Q32), decorative. */
  icon?: LucideIcon
  testId: string
}) {
  return (
    <Card className="relative gap-2 py-4" data-testid={testId}>
      {Icon && (
        <span aria-hidden className="bg-accent text-accent-foreground absolute top-4 right-4 flex size-9 items-center justify-center rounded-full">
          <Icon className="size-4" />
        </span>
      )}
      <CardHeader className="px-4 pr-16">
        <CardDescription>{label}</CardDescription>
        <CardTitle className="text-2xl tabular-nums" data-testid={`${testId}-value`}>{value}</CardTitle>
      </CardHeader>
      {hint && <CardContent className="text-muted-foreground px-4 text-xs">{hint}</CardContent>}
    </Card>
  )
}
