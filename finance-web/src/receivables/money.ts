import { formatDecimal, parseDecimal, toDecimal, type Decimal } from '@jabiz/admin'

/**
 * Exact decimal arithmetic for the receivables pages: amounts are never added or multiplied in floating point.
 * Rounding is half away from zero, as the server's calc.Money.
 */

export const ZERO: Decimal = { unscaled: 0n, scale: 0 }

/** "1,234.5", "1234.5", "$1,234.50": separators optional and only in their places; none negative. */
const NUMBER = /^\$?\s*(?:\d{1,3}(?:,\d{3})+|\d*)(?:\.\d*)?$/

/** A non-negative number as typed, with at most {@code maxScale} digits after the point; null when it is not one. */
export function parseNumber(text: string, maxScale: number): Decimal | null {
  const trimmed = text.trim()
  if (trimmed === '' || !NUMBER.test(trimmed)) return null
  const decimal = parseDecimal(trimmed.replace(/[$,\s]/g, ''))
  if (!decimal) return null
  return significantScale(decimal) > maxScale ? null : decimal
}

/** Digits after the point that matter ("1.50" has one). */
export function significantScale(d: Decimal): number {
  let { unscaled, scale } = d
  while (scale > 0 && unscaled % 10n === 0n) {
    unscaled /= 10n
    scale -= 1
  }
  return Math.max(scale, 0)
}

export function rescale(d: Decimal, scale: number): Decimal {
  if (d.scale === scale) return d
  if (d.scale < scale) return { unscaled: d.unscaled * 10n ** BigInt(scale - d.scale), scale }
  const divisor = 10n ** BigInt(d.scale - scale)
  let quotient = d.unscaled / divisor
  const remainder = d.unscaled % divisor
  const twice = (remainder < 0n ? -remainder : remainder) * 2n
  if (twice >= divisor) quotient += d.unscaled < 0n ? -1n : 1n
  return { unscaled: quotient, scale }
}

export function add(a: Decimal, b: Decimal): Decimal {
  const scale = Math.max(a.scale, b.scale)
  return { unscaled: rescale(a, scale).unscaled + rescale(b, scale).unscaled, scale }
}

export function subtract(a: Decimal, b: Decimal): Decimal {
  return add(a, { unscaled: -b.unscaled, scale: b.scale })
}

export function multiply(a: Decimal, b: Decimal): Decimal {
  return { unscaled: a.unscaled * b.unscaled, scale: a.scale + b.scale }
}

export function min(a: Decimal, b: Decimal): Decimal {
  return subtract(a, b).unscaled <= 0n ? a : b
}

export function sign(d: Decimal): number {
  return d.unscaled === 0n ? 0 : d.unscaled < 0n ? -1 : 1
}

/** In cents, as plain text ("1234.50"). */
export function cents(d: Decimal): string {
  return formatDecimal(rescale(d, 2))
}

/** A stored amount (JSON number or text) as a decimal; zero when absent. */
export function stored(value: unknown): Decimal {
  return toDecimal(value) ?? ZERO
}

/** The sum of stored amounts in cents ("0.00" for none). */
export function sum(values: unknown[]): string {
  return cents(values.reduce<Decimal>((total, value) => add(total, stored(value)), ZERO))
}
