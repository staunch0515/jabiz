/**
 * Exact decimals with the semantics of java.math.BigDecimal where the validator needs them: parsing, comparison,
 * scale after stripping trailing zeros and precision. Floating point would make "0.1 + 0.2"-style differences
 * between client and server checks; BigInt keeps every digit.
 */
export interface Decimal {
  /** value = unscaled × 10^-scale */
  readonly unscaled: bigint
  readonly scale: number
}

/** The grammar of new BigDecimal(String), which the server uses for text (after trimming). */
const DECIMAL = /^([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/

/** Parses decimal text the way the server does; null when it is not a decimal. */
export function parseDecimal(text: string): Decimal | null {
  const match = DECIMAL.exec(text)
  if (!match) return null
  const [, sign, integer, fraction = '', exponent] = match
  if (integer.length === 0 && fraction.length === 0) return null
  const digits = (integer + fraction).replace(/^0+(?=\d)/, '')
  const unscaled = BigInt(digits) * (sign === '-' ? -1n : 1n)
  const scale = fraction.length - (exponent ? Number.parseInt(exponent, 10) : 0)
  return { unscaled, scale }
}

/** A JavaScript number as the server sees it: a finite value, converted without binary noise. */
export function decimalFromNumber(value: number): Decimal | null {
  if (!Number.isFinite(value)) return null
  return parseDecimal(String(value))
}

/** Parameters of rules arrive as JSON numbers or text. */
export function toDecimal(value: unknown): Decimal | null {
  if (typeof value === 'number') return decimalFromNumber(value)
  if (typeof value === 'string') return parseDecimal(value.trim())
  return null
}

function pow10(n: number): bigint {
  return 10n ** BigInt(n)
}

/** Negative, zero or positive, as a.compareTo(b). */
export function compareDecimal(a: Decimal, b: Decimal): number {
  const scale = Math.max(a.scale, b.scale)
  const left = a.unscaled * pow10(scale - a.scale)
  const right = b.unscaled * pow10(scale - b.scale)
  return left < right ? -1 : left > right ? 1 : 0
}

/** BigDecimal.stripTrailingZeros(); zero becomes scale 0. */
export function stripTrailingZeros(d: Decimal): Decimal {
  if (d.unscaled === 0n) return { unscaled: 0n, scale: 0 }
  let unscaled = d.unscaled
  let scale = d.scale
  while (unscaled % 10n === 0n) {
    unscaled /= 10n
    scale -= 1
  }
  return { unscaled, scale }
}

/** BigDecimal.precision(): the number of digits of the unscaled value (1 for zero). */
export function precision(d: Decimal): number {
  const digits = (d.unscaled < 0n ? -d.unscaled : d.unscaled).toString()
  return digits.length
}

export function signum(d: Decimal): number {
  return d.unscaled < 0n ? -1 : d.unscaled > 0n ? 1 : 0
}

/** Plain decimal text (no exponent), for display and for sending to the server. */
export function formatDecimal(d: Decimal): string {
  const negative = d.unscaled < 0n
  let digits = (negative ? -d.unscaled : d.unscaled).toString()
  let text: string
  if (d.scale <= 0) {
    text = d.unscaled === 0n ? '0' : digits + '0'.repeat(-d.scale)
  } else {
    digits = digits.padStart(d.scale + 1, '0')
    text = `${digits.slice(0, digits.length - d.scale)}.${digits.slice(digits.length - d.scale)}`
  }
  return negative ? `-${text}` : text
}
