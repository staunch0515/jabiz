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
    // Q34b: the name on the card's title bar with its picture, the value large in the card's body.
    <Card className="gap-2 pb-4" data-testid={testId}>
      <CardHeader className="flex items-center gap-2 px-4">
        {Icon && <Icon aria-hidden className="text-primary size-4 shrink-0" />}
        <CardDescription className="text-foreground font-medium">{label}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-1 px-4">
        <CardTitle className="text-2xl tabular-nums" data-testid={`${testId}-value`}>{value}</CardTitle>
        {hint && <p className="text-muted-foreground text-xs">{hint}</p>}
      </CardContent>
    </Card>
  )
}
