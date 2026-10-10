import { formatDate, formatDateTime } from '@jabiz/client'

/**
 * Dates and times as the user reads and types them (decision D22 item 7): in the application's region when it has
 * one (`01/31/2026, 02:05:09 PM` in en-US), else in the neutral forms `YYYY-MM-DD` / `YYYY-MM-DD HH:mm:ss`. The ISO
 * form is accepted in every region and read first: a text starting with a four-digit year and a hyphen is year,
 * month, day. Regional text is read by the order of year, month and day the region writes (the year must have four
 * digits), with the region's AM / PM where it uses a 12-hour clock.
 */

type Part = 'year' | 'month' | 'day'

export interface RegionalPattern {
  /** The order of the date's parts. */
  order: Part[]
  /** AM and PM as the region writes them; undefined for a 24-hour clock. */
  am?: string
  pm?: string
  /** Characters the region puts between the parts (besides digits). */
  literals: string
  /** The placeholders: `MM/DD/YYYY` and `MM/DD/YYYY, hh:mm:ss AM`. */
  datePlaceholder: string
  dateTimePlaceholder: string
}

const pad = (n: number, width = 2) => String(n).padStart(width, '0')

const DATE_OPTIONS: Intl.DateTimeFormatOptions = { year: 'numeric', month: '2-digit', day: '2-digit' }
const TIME_OPTIONS: Intl.DateTimeFormatOptions = { hour: '2-digit', minute: '2-digit', second: '2-digit' }

const TOKENS: Partial<Record<Intl.DateTimeFormatPartTypes, string>> = {
  year: 'YYYY',
  month: 'MM',
  day: 'DD',
  minute: 'mm',
  second: 'ss',
}

const patterns = new Map<string, RegionalPattern>()

/** How a region writes dates and times; undefined without a region (the neutral forms). */
export function regionalPattern(region: string | undefined): RegionalPattern | undefined {
  if (!region) return undefined
  const known = patterns.get(region)
  if (known) return known
  const sample = new Date(2026, 0, 31, 13, 5, 9)
  const dateParts = new Intl.DateTimeFormat(region, DATE_OPTIONS).formatToParts(sample)
  const dateTime = new Intl.DateTimeFormat(region, { ...DATE_OPTIONS, ...TIME_OPTIONS })
  const twelve = ['h11', 'h12'].includes(dateTime.resolvedOptions().hourCycle ?? '')
  const period = (hour: number) =>
    dateTime.formatToParts(new Date(2026, 0, 31, hour)).find((p) => p.type === 'dayPeriod')?.value
  const placeholder = (parts: Intl.DateTimeFormatPart[]) =>
    parts
      .map((p) =>
        p.type === 'hour' ? (twelve ? 'hh' : 'HH') : p.type === 'dayPeriod' ? 'AM' : p.type === 'literal' ? p.value : (TOKENS[p.type] ?? p.value),
      )
      .join('')
      // Narrow and non-breaking spaces (en-US before AM) as plain ones: a placeholder is read, not parsed.
      .replace(/[\s\u202f\u00a0]+/gu, ' ')
  const allParts = dateTime.formatToParts(sample)
  const pattern: RegionalPattern = {
    order: dateParts.filter((p) => p.type === 'year' || p.type === 'month' || p.type === 'day').map((p) => p.type as Part),
    am: twelve ? period(1) : undefined,
    pm: twelve ? period(13) : undefined,
    literals: allParts.filter((p) => p.type === 'literal').map((p) => p.value).join(''),
    datePlaceholder: placeholder(dateParts),
    dateTimePlaceholder: placeholder(allParts),
  }
  patterns.set(region, pattern)
  return pattern
}

/** The local date, when the parts name a real day and time (no 31 February rolling over into March). */
export function localDate(year: number, month: number, day: number, h = 0, m = 0, s = 0): Date | undefined {
  if (month < 1 || month > 12 || day < 1 || day > 31 || h > 23 || m > 59 || s > 59) return undefined
  const date = new Date(year, month - 1, day, h, m, s)
  if (Number.isNaN(date.getTime())) return undefined
  return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day ? date : undefined
}

/** A local date as `YYYY-MM-DD`, the form of date fields (LocalDate) in the API. */
export function toDateValue(date: Date): string {
  return `${pad(date.getFullYear(), 4)}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** `YYYY-MM-DD HH:mm:ss` with an optional time (minutes and seconds also optional) or a `T`. */
const ISO = /^(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2})(?::(\d{1,2}))?(?::(\d{1,2}))?)?$/

interface Parts {
  y: number
  mo: number
  d: number
  time?: [number, number, number]
}

function regionalParts(text: string, pattern: RegionalPattern): Parts | undefined {
  let rest = text
  let period: 'am' | 'pm' | undefined
  for (const [name, value] of [['pm', pattern.pm], ['am', pattern.am]] as const) {
    if (value && rest.toLowerCase().includes(value.toLowerCase())) {
      period = name
      rest = rest.replace(new RegExp(value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i'), ' ')
      break
    }
  }
  // Only digits, the region's own separators, whitespace and common separators may remain.
  const allowed = new Set([...pattern.literals, ...' -/.:T,'])
  for (const char of rest) {
    if (!/[\d\s]/u.test(char) && !allowed.has(char)) return undefined
  }
  const numbers = rest.match(/\d+/g) ?? []
  if (numbers.length < 3 || numbers.length > 6) return undefined
  const date: Record<Part, string> = { year: '', month: '', day: '' }
  pattern.order.forEach((part, i) => (date[part] = numbers[i]))
  if (date.year.length !== 4) return undefined
  const parts: Parts = { y: Number(date.year), mo: Number(date.month), d: Number(date.day) }
  if (numbers.length > 3) {
    let h = Number(numbers[3])
    if (period && (h < 1 || h > 12)) return undefined
    if (period === 'pm' && h < 12) h += 12
    if (period === 'am' && h === 12) h = 0
    parts.time = [h, Number(numbers[4] ?? 0), Number(numbers[5] ?? 0)]
  }
  return parts
}

function partsOf(text: string, region: string | undefined): Parts | undefined {
  const trimmed = text.trim()
  const iso = ISO.exec(trimmed)
  if (iso) {
    const [, y, mo, d, h, mi, s] = iso
    return { y: Number(y), mo: Number(mo), d: Number(d), time: h === undefined ? undefined : [Number(h), Number(mi ?? 0), Number(s ?? 0)] }
  }
  const pattern = regionalPattern(region)
  return pattern ? regionalParts(trimmed, pattern) : undefined
}

/** What was typed into a date field as `YYYY-MM-DD`; undefined when it is no date. No time zone is involved. */
export function parseDateText(text: string, region?: string): string | undefined {
  const parts = partsOf(text, region)
  if (!parts || parts.time) return undefined
  const date = localDate(parts.y, parts.mo, parts.d)
  return date ? toDateValue(date) : undefined
}

/** What was typed into a date-time field (local time, to the second), or undefined when it is no point in time. */
export function parseDateTimeText(text: string, region?: string): Date | undefined {
  const parts = partsOf(text, region)
  if (!parts) return undefined
  const [h, m, s] = parts.time ?? [0, 0, 0]
  return localDate(parts.y, parts.mo, parts.d, h, m, s)
}

/** A date (`YYYY-MM-DD`) as the field shows it: in the region's form, else as it is. */
export function formatDateText(value: string, region?: string): string {
  return region ? formatDate(value, region) : value
}

/** A point in time as the field shows it: in the region's form, else local `YYYY-MM-DD HH:mm:ss`. */
export function formatDateTimeText(date: Date, region?: string): string {
  if (region) return formatDateTime(date.toISOString(), region)
  return `${toDateValue(date)} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}
