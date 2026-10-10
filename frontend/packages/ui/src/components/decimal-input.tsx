import { useId, type ComponentProps } from 'react'
import { formatDecimal, parseDecimal } from '@jabiz/client'
import { cn } from '../lib/utils'
import { Input } from './ui/input'

type InputProps = Omit<ComponentProps<'input'>, 'value' | 'onChange' | 'type' | 'inputMode' | 'defaultValue'>

export interface DecimalInputProps extends InputProps {
  /** The decimal as text ("1234.5"), exactly as the API takes it; "" when empty. */
  value: string
  onChange: (value: string) => void
  /** Most digits after the point; typing more is refused. Unlimited when absent. */
  scale?: number
  /** Whether a minus sign may be typed (default true). */
  allowNegative?: boolean
  /** Pads the fraction to `scale` digits when the field is left (amounts: "12" becomes "12.00"). */
  padToScale?: boolean
}

const PARTIAL = /^-?\d*(\.\d*)?$/

/** What may stand in the field while typing: digits, one point, a leading minus; no more than `scale` decimals. */
export function acceptsDecimalText(text: string, scale?: number, allowNegative = true): boolean {
  if (!PARTIAL.test(text)) return false
  if (!allowNegative && text.startsWith('-')) return false
  const fraction = text.split('.')[1]
  return scale === undefined || fraction === undefined || fraction.length <= scale
}

/** The text in its plain form when the field is left: "1." → "1", ".5" → "0.5", "-" → "", optionally padded. */
export function normalizeDecimalText(text: string, scale?: number, padToScale = false): string {
  const decimal = parseDecimal(text)
  if (!decimal) return ''
  let plain = formatDecimal(decimal)
  if (padToScale && scale !== undefined && scale > 0) {
    const [integer, fraction = ''] = plain.split('.')
    plain = `${integer}.${fraction.padEnd(scale, '0')}`
  }
  return plain
}

/**
 * A decimal typed as text and kept as text (never a binary floating-point number, CLAUDE.md "amounts"): what the
 * user typed is what the API receives. Grouping separators and spaces in pasted text are dropped.
 */
export function DecimalInput({
  value,
  onChange,
  scale,
  allowNegative = true,
  padToScale = false,
  onBlur,
  className,
  ...props
}: DecimalInputProps) {
  return (
    <Input
      {...props}
      type="text"
      inputMode="decimal"
      autoComplete="off"
      value={value}
      className={cn('text-right tabular-nums', className)}
      onChange={(event) => {
        const text = event.target.value.replace(/[\s,]/g, '')
        if (acceptsDecimalText(text, scale, allowNegative)) onChange(text)
      }}
      onBlur={(event) => {
        const normal = normalizeDecimalText(value, scale, padToScale)
        if (normal !== value) onChange(normal)
        onBlur?.(event)
      }}
    />
  )
}

export interface MoneyInputProps extends DecimalInputProps {
  /** Digits after the point of the currency (SemanticKind.Monetary's scale): typing more is refused. */
  scale: number
  /** ISO 4217 code shown at the end of the field; or {@link unit}. */
  currency?: string
  /** A unit that is no currency ("Kudos", decision D34 item 4). */
  unit?: string
}

/**
 * An amount: a {@link DecimalInput} with the currency's number of decimals, padded when left, and the currency or
 * unit shown in the field and read with it (it is part of the field's description).
 */
export function MoneyInput({ currency, unit, className, 'aria-describedby': describedBy, ...props }: MoneyInputProps) {
  const id = useId()
  const label = currency ?? unit
  return (
    <div className={cn('relative', className)} data-slot="money-input">
      <DecimalInput
        padToScale
        {...props}
        aria-describedby={[describedBy, label ? `${id}-unit` : undefined].filter(Boolean).join(' ') || undefined}
        className={label ? 'pr-16' : undefined}
      />
      {label && (
        <span
          id={`${id}-unit`}
          className="text-muted-foreground pointer-events-none absolute inset-y-0 right-3 flex items-center text-sm"
        >
          {label}
        </span>
      )}
    </div>
  )
}
