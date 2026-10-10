import { useId, useState, type ComponentProps, type KeyboardEvent } from 'react'
import { appRegion } from '@jabiz/client'
import { CalendarIcon, XIcon } from 'lucide-react'
import { dateMatchModifiers, type Locale, type Matcher } from 'react-day-picker'
import { enUS, ja, zhCN } from 'react-day-picker/locale'
import { useTranslation } from 'react-i18next'

import { UI_NAMESPACE } from '../i18n'
import { cn, UI_SCOPE } from '../lib/utils'
import { Button } from './ui/button'
import { Calendar } from './ui/calendar'
import { Input } from './ui/input'
import { Popover, PopoverContent, PopoverTrigger } from './ui/popover'

/** The calendar's language (month names, weekday names, the navigation buttons' names) per interface language. */
const LOCALES: Record<string, Locale> = { zh: zhCN, ja, en: enUS }

/** The first day of the week in the application's region (en-US: Sunday), else the language's. */
function weekStartsOn(region: string | undefined): 0 | 1 | 2 | 3 | 4 | 5 | 6 | undefined {
  if (!region) return undefined
  try {
    const locale = new Intl.Locale(region) as Intl.Locale & {
      getWeekInfo?: () => { firstDay: number }
      weekInfo?: { firstDay: number }
    }
    const firstDay = (locale.getWeekInfo?.() ?? locale.weekInfo)?.firstDay
    // Intl counts Monday as 1 and Sunday as 7; the calendar Sunday as 0.
    return firstDay === undefined ? undefined : ((firstDay % 7) as 0 | 1 | 2 | 3 | 4 | 5 | 6)
  } catch {
    return undefined
  }
}

function useCalendarLocale() {
  const { i18n } = useTranslation(UI_NAMESPACE)
  return { locale: LOCALES[i18n.language] ?? enUS, weekStartsOn: weekStartsOn(appRegion) }
}

const pad = (n: number, width = 2) => String(n).padStart(width, '0')

/** `YYYY-MM-DD` as a local date (midnight), or undefined when it is not one. */
export function parseDateValue(value: string | null | undefined): Date | undefined {
  const match = value ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(value) : null
  if (!match) return undefined
  const date = localDate(Number(match[1]), Number(match[2]), Number(match[3]))
  return date
}

/** A local date as `YYYY-MM-DD`, the form of date fields (LocalDate) in the API. */
export function toDateValue(date: Date): string {
  return `${pad(date.getFullYear(), 4)}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** The local date, when the parts name a real day (no 31 February rolling over into March). */
function localDate(year: number, month: number, day: number, h = 0, m = 0, s = 0): Date | undefined {
  if (month < 1 || month > 12 || day < 1 || day > 31 || h > 23 || m > 59 || s > 59) return undefined
  const date = new Date(year, month - 1, day, h, m, s)
  if (Number.isNaN(date.getTime())) return undefined
  return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day ? date : undefined
}

/** `YYYY-MM-DD HH:mm:ss` with an optional time (minutes and seconds also optional) or a `T`, as typed. */
const TYPED = /^(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2})(?::(\d{1,2}))?(?::(\d{1,2}))?)?$/

/**
 * What was typed into a date field as `YYYY-MM-DD`; undefined when it is no date. The day is the one typed: no time
 * zone is involved (02 section 1.1).
 */
export function parseDateText(text: string): string | undefined {
  const match = TYPED.exec(text.trim())
  if (!match || match[4] !== undefined) return undefined
  const date = localDate(Number(match[1]), Number(match[2]), Number(match[3]))
  return date ? toDateValue(date) : undefined
}

/** What was typed into a date-time field (local time, to the second), or undefined when it is no point in time. */
export function parseDateTimeText(text: string): Date | undefined {
  const match = TYPED.exec(text.trim())
  if (!match) return undefined
  const [, y, mo, d, h = '0', mi = '0', s = '0'] = match
  return localDate(Number(y), Number(mo), Number(d), Number(h), Number(mi), Number(s))
}

/** A point in time as the field shows it: local time, `YYYY-MM-DD HH:mm:ss`. */
export function formatDateTimeText(date: Date): string {
  return `${toDateValue(date)} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function instant(value: string | null | undefined): Date | undefined {
  if (!value) return undefined
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? undefined : date
}

function asMatchers(matchers: Matcher | Matcher[] | undefined): Matcher[] {
  return matchers === undefined ? [] : Array.isArray(matchers) ? matchers : [matchers]
}

/**
 * The text of a typed field: what the user is typing (a draft) until it is read on leaving the field or Enter.
 * A draft that is no valid value stays, marked invalid, and is not given to the caller.
 */
function useTypedText(shown: string, read: (text: string) => 'same' | 'invalid' | (() => void)) {
  const [draft, setDraft] = useState<string | null>(null)
  const [invalid, setInvalid] = useState(false)
  // A new value from outside (the calendar, the clear button, the caller) replaces what was being typed.
  const [lastShown, setLastShown] = useState(shown)
  if (lastShown !== shown) {
    setLastShown(shown)
    setDraft(null)
    setInvalid(false)
  }
  const commit = (): boolean => {
    if (draft === null) return true
    const result = read(draft)
    if (result === 'invalid') {
      setInvalid(true)
      return false
    }
    setDraft(null)
    setInvalid(false)
    if (result !== 'same') result()
    return true
  }
  return {
    text: draft ?? shown,
    invalid,
    onChange: (text: string) => {
      setDraft(text)
      setInvalid(false)
    },
    commit,
    onKeyDown: (event: KeyboardEvent<HTMLInputElement>) => {
      // Enter reads the text; a form around the field is submitted only with a valid value.
      if (event.key === 'Enter' && !commit()) event.preventDefault()
    },
  }
}

type InputAttributes = Omit<
  ComponentProps<'input'>,
  'value' | 'onChange' | 'type' | 'defaultValue' | 'placeholder' | 'disabled' | 'className'
>

export interface DatePickerProps extends InputAttributes {
  /** `YYYY-MM-DD`; null or undefined when no date is chosen. */
  value: string | null | undefined
  onChange: (value: string | null) => void
  /** The text field's placeholder; the format (`YYYY-MM-DD`) when absent. */
  placeholder?: string
  disabled?: boolean
  /** Offers a button that empties the field. */
  clearable?: boolean
  className?: string
  /** Dates that cannot be chosen (before / after a bound): disabled in the calendar, refused when typed. */
  disabledDays?: Matcher | Matcher[]
}

/**
 * A calendar date (a posting date, a due date; CLAUDE.md "time"): typed as `YYYY-MM-DD` or picked from a calendar in
 * the interface language (month and year can be chosen in its heading). The text is read when the field is left or
 * on Enter; a text that is no date (or a date not allowed) is not taken and marks the field `aria-invalid`. Name the
 * text field with a <Label htmlFor={id}> or `aria-label`; it takes the other input attributes too.
 */
export function DatePicker({
  value,
  onChange,
  placeholder,
  disabled,
  clearable = false,
  className,
  disabledDays,
  readOnly,
  onBlur,
  'aria-invalid': ariaInvalid,
  ...input
}: DatePickerProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  const calendar = useCalendarLocale()
  const [open, setOpen] = useState(false)
  const selected = parseDateValue(value)
  const matchers = asMatchers(disabledDays)
  const typed = useTypedText(selected ? toDateValue(selected) : '', (text) => {
    if (text.trim() === '') return selected ? () => onChange(null) : 'same'
    const day = parseDateText(text)
    const date = parseDateValue(day)
    if (!day || !date || (matchers.length > 0 && dateMatchModifiers(date, matchers))) return 'invalid'
    return day === value ? 'same' : () => onChange(day)
  })
  const locked = disabled || readOnly

  return (
    <div data-slot="date-picker" className={cn(UI_SCOPE, 'flex items-center gap-1', className)}>
      <Input
        {...input}
        type="text"
        inputMode="numeric"
        autoComplete="off"
        value={typed.text}
        placeholder={placeholder ?? 'YYYY-MM-DD'}
        disabled={disabled}
        readOnly={readOnly}
        aria-invalid={typed.invalid || ariaInvalid || undefined}
        onChange={(event) => typed.onChange(event.target.value)}
        onBlur={(event) => {
          typed.commit()
          onBlur?.(event)
        }}
        onKeyDown={typed.onKeyDown}
        className="min-w-32 tabular-nums"
      />
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button variant="outline" size="icon" disabled={locked} aria-label={t('date.openCalendar')}>
            <CalendarIcon aria-hidden />
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-auto p-0" align="start">
          <Calendar
            mode="single"
            captionLayout="dropdown"
            selected={selected}
            defaultMonth={selected}
            endMonth={new Date(new Date().getFullYear() + 20, 11)}
            onSelect={(date) => {
              // The calendar unselects a day clicked again; only a clearable field may become empty that way.
              if (date) onChange(toDateValue(date))
              else if (clearable) onChange(null)
              setOpen(false)
            }}
            disabled={disabledDays}
            locale={calendar.locale}
            weekStartsOn={calendar.weekStartsOn}
            autoFocus
          />
        </PopoverContent>
      </Popover>
      {clearable && selected && !locked && (
        <Button variant="ghost" size="icon-sm" aria-label={t('date.clear')} onClick={() => onChange(null)}>
          <XIcon aria-hidden />
        </Button>
      )}
    </div>
  )
}

export interface DateTimePickerProps extends InputAttributes {
  /** A point in time, ISO 8601 (`2026-01-31T05:05:09Z`); null or undefined when none is chosen. */
  value: string | null | undefined
  /** The chosen local date and time as an ISO 8601 instant in UTC. */
  onChange: (value: string | null) => void
  /** The text field's placeholder; the format (`YYYY-MM-DD HH:mm:ss`) when absent. */
  placeholder?: string
  disabled?: boolean
  clearable?: boolean
  className?: string
  /** Days that cannot be chosen: disabled in the calendar, refused when typed. */
  disabledDays?: Matcher | Matcher[]
  /** The latest point in time that may be chosen (no future: now); later days are disabled, later times refused. */
  toDate?: Date
}

/**
 * A point in time: typed as local time `YYYY-MM-DD HH:mm:ss` (minutes and seconds may be left out) or a day picked
 * from the calendar and a time (to the second) in the browser's time zone, given back as an instant. The text is
 * read when the field is left or on Enter; a text that is no point in time (or one not allowed) is not taken and
 * marks the field `aria-invalid`. A value is changed only when the text names another second, so the milliseconds
 * of a value the user did not change are kept.
 */
export function DateTimePicker({
  value,
  onChange,
  placeholder,
  disabled,
  clearable = false,
  className,
  disabledDays,
  toDate,
  readOnly,
  onBlur,
  'aria-invalid': ariaInvalid,
  ...input
}: DateTimePickerProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  const calendar = useCalendarLocale()
  const [open, setOpen] = useState(false)
  const timeId = useId()
  const current = instant(value)
  const shown = current ? formatDateTimeText(current) : ''
  const time = shown.slice(11)
  const dayMatchers = [...asMatchers(disabledDays), ...(toDate ? [{ after: toDate }] : [])]
  const allowed = (date: Date) =>
    !(toDate && date.getTime() > toDate.getTime()) &&
    !(dayMatchers.length > 0 && dateMatchModifiers(new Date(date.getFullYear(), date.getMonth(), date.getDate()), dayMatchers))

  const typed = useTypedText(shown, (text) => {
    if (text.trim() === '') return current ? () => onChange(null) : 'same'
    const date = parseDateTimeText(text)
    if (!date || !allowed(date)) return 'invalid'
    return formatDateTimeText(date) === shown ? 'same' : () => onChange(date.toISOString())
  })

  const emit = (date: Date | undefined, hms: string) => {
    if (!date) {
      onChange(null)
      return
    }
    const [h = 0, m = 0, s = 0] = hms.split(':').map((part) => Number(part) || 0)
    let next = new Date(date.getFullYear(), date.getMonth(), date.getDate(), h, m, s)
    // The day is allowed, the time on it may not be (today with a time after now): the latest allowed.
    if (toDate && next.getTime() > toDate.getTime()) next = new Date(Math.floor(toDate.getTime() / 1000) * 1000)
    onChange(next.toISOString())
  }
  const locked = disabled || readOnly

  return (
    <div data-slot="date-time-picker" className={cn(UI_SCOPE, 'flex items-center gap-1', className)}>
      <Input
        {...input}
        type="text"
        autoComplete="off"
        value={typed.text}
        placeholder={placeholder ?? 'YYYY-MM-DD HH:mm:ss'}
        disabled={disabled}
        readOnly={readOnly}
        aria-invalid={typed.invalid || ariaInvalid || undefined}
        onChange={(event) => typed.onChange(event.target.value)}
        onBlur={(event) => {
          typed.commit()
          onBlur?.(event)
        }}
        onKeyDown={typed.onKeyDown}
        className="min-w-48 tabular-nums"
      />
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button variant="outline" size="icon" disabled={locked} aria-label={t('date.openCalendar')}>
            <CalendarIcon aria-hidden />
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-auto p-0" align="start">
          <Calendar
            mode="single"
            captionLayout="dropdown"
            selected={current}
            defaultMonth={current}
            endMonth={toDate ?? new Date(new Date().getFullYear() + 20, 11)}
            onSelect={(date) => {
              if (date || clearable) emit(date, time || '00:00:00')
            }}
            disabled={dayMatchers.length > 0 ? dayMatchers : undefined}
            locale={calendar.locale}
            weekStartsOn={calendar.weekStartsOn}
            autoFocus
          />
          <div className="flex items-center gap-2 border-t p-3">
            <label className="text-sm" htmlFor={timeId}>
              {t('date.time')}
            </label>
            <Input
              id={timeId}
              type="time"
              step={1}
              value={time}
              disabled={!current}
              onChange={(event) => emit(current, event.target.value)}
              className="w-36"
            />
          </div>
        </PopoverContent>
      </Popover>
      {clearable && current && !locked && (
        <Button variant="ghost" size="icon-sm" aria-label={t('date.clear')} onClick={() => onChange(null)}>
          <XIcon aria-hidden />
        </Button>
      )}
    </div>
  )
}
