import { cn } from '@jabiz/ui'
import type { Violation } from '../meta/types'

interface Props {
  violations: Violation[]
  /** The id the field's input refers to with `aria-describedby`, so the messages are read with it. */
  id?: string
  className?: string
}

/**
 * Violations of one field, from the client check or from the server. Both come with the rule code, which is kept on
 * the element: the client and the server report the same codes (decision D15).
 */
export default function FieldErrors({ violations, id, className }: Props) {
  if (violations.length === 0) return null
  return (
    <div id={id} data-slot="field-errors" className={cn('flex flex-col gap-0.5', className)}>
      {violations.map((v) => (
        <p
          key={v.ruleCode + v.message}
          className="text-destructive text-sm"
          data-rule-code={v.ruleCode}
          role="alert"
        >
          {v.message}
        </p>
      ))}
    </div>
  )
}
