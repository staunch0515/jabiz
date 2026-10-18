import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@jabiz/ui'
import type { ReactNode } from 'react'

/**
 * A key figure: its name, the value large, and an optional line of context (what it includes, since when). Not a
 * chart: one number reads best as a number.
 */
export function Metric({ label, value, hint, testId }: {
  label: string
  value: ReactNode
  hint?: ReactNode
  testId: string
}) {
  return (
    <Card className="gap-2 py-4" data-testid={testId}>
      <CardHeader className="px-4">
        <CardDescription>{label}</CardDescription>
        <CardTitle className="text-2xl tabular-nums" data-testid={`${testId}-value`}>{value}</CardTitle>
      </CardHeader>
      {hint && <CardContent className="text-muted-foreground px-4 text-xs">{hint}</CardContent>}
    </Card>
  )
}
