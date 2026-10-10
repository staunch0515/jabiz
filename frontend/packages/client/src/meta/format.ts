import dayjs from 'dayjs'
import { formatDecimal, signum, toDecimal, type Decimal } from './decimal'

/**
 * Presentation of times and amounts by region (decision D22, item 7): with a region (`VITE_JABIZ_REGION`, for
 * example `en-US`, set by `jabizApp { region = ... }`) dates, numbers and currencies follow its conventions;
 * without one they keep the platform's neutral forms.
 */

/** The region from its build value, checked; undefined when unset. */
export function regionOf(value: string | undefined): string | undefined {
  if (value === undefined || value.trim() === '') return undefined
  try {
    const [canonical] = Intl.getCanonicalLocales(value.trim())
    if (!canonical.includes('-')) throw new RangeError('no region')
    return canonical
  } catch {
    throw new Error(`VITE_JABIZ_REGION must be a language with a region such as en-US, was "${value}"`)
  }
}

export const appRegion: string | undefined = regionOf(import.meta.env.VITE_JABIZ_REGION)

/** The locale numbers and currencies are formatted in: the region, else the interface language. */
export function displayLocale(language: string, region: string | undefined = appRegion): string {
  return region ?? language
}

/** A point in time: `01/31/2026, 02:05:09 PM` in en-US; `2026-01-31 14:05:09` without a region. */
export function formatDateTime(value: unknown, region: string | undefined = appRegion): string {
  const time = dayjs(String(value))
  if (!time.isValid()) return String(value)
  if (!region) return time.format('YYYY-MM-DD HH:mm:ss')
  return new Intl.DateTimeFormat(region, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(time.toDate())
}

/** A calendar date (`YYYY-MM-DD` in, `01/31/2026` out in en-US). */
export function formatDate(value: string, region: string | undefined = appRegion): string {
  const date = dayjs(value)
  if (!date.isValid()) return value
  if (!region) return date.format('YYYY-MM-DD')
  return new Intl.DateTimeFormat(region, { year: 'numeric', month: '2-digit', day: '2-digit', timeZone: 'UTC' })
    .format(new Date(Date.UTC(date.year(), date.month(), date.date())))
}

export interface AmountFormat {
  /**
   * ISO 4217 code: shown as the currency's symbol or code, or as the label the application gave it
   * ({@link setAmountUnit}). Absent: the number alone.
   */
  currency?: string
  /** Digits after the point, always shown (2 → 1,234.50). */
  scale: number
  /** `parentheses` for financial statements: (2,000.00). Default: a minus sign. */
  negative?: 'minus' | 'parentheses'
  locale?: string
}

const currencyLabels = new Map<string, string>()

/**
 * Shows amounts in `currency` with `label` after the number instead of the currency's symbol (decision D34 item 4):
 * an application whose ledger currency stands for points shows "1,234 Kudos" rather than "¥1,234". Only amounts
 * given in that currency change; numbers without a currency and amounts in other currencies keep their forms.
 * Set once at startup; an empty or undefined label restores the symbol.
 */
export function setAmountUnit(currency: string, label: string | undefined) {
  const code = currency.trim().toUpperCase()
  if (label === undefined || label.trim() === '') currencyLabels.delete(code)
  else currencyLabels.set(code, label.trim())
}

/**
 * An exact amount with grouping and a fixed number of decimals. The text of the decimal is formatted, never a
 * binary floating-point copy of it, so what is shown is what is stored (a value with more digits than `scale` is
 * rounded half away from zero for display only).
 */
export function formatAmount(value: unknown, format: AmountFormat): string {
  const d = toDecimal(value)
  if (!d) return value === null || value === undefined ? '' : String(value)
  const locale = format.locale ?? appRegion ?? 'en'
  const negative = signum(d) < 0
  const text = formatDecimal(negative ? negate(d) : d)
  const label = format.currency ? currencyLabels.get(format.currency.toUpperCase()) : undefined
  const options: Intl.NumberFormatOptions = {
    minimumFractionDigits: format.scale,
    maximumFractionDigits: format.scale,
    roundingMode: 'halfExpand',
    ...(format.currency && !label ? { style: 'currency', currency: format.currency } : {}),
  }
  // Intl formats decimal strings exactly (ES2023); the cast only satisfies the older signature.
  const number = new Intl.NumberFormat(locale, options).format(text as unknown as number)
  // A no-break space: the label never wraps away from its number.
  const body = label ? `${number}\u00a0${label}` : number
  if (!negative) return body
  return format.negative === 'parentheses' ? `(${body})` : `-${body}`
}

function negate(d: Decimal): Decimal {
  return { ...d, unscaled: -d.unscaled }
}
