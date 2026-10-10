import { useEffect, useId, useRef, useState, type ComponentProps, type KeyboardEvent } from 'react'
import { appRegion } from '@jabiz/client'
import { CalendarIcon, XIcon } from 'lucide-react'
import { dateMatchModifiers, type Locale, type Matcher } from 'react-day-picker'
import { enUS, ja, zhCN } from 'react-day-picker/locale'
import { useTranslation } from 'react-i18next'

import { UI_NAMESPACE } from '../i18n'
import {
  formatDateText,
  formatDateTimeText,
  localDate,
  parseDateText,
  parseDateTimeText,
  regionalPattern,
  toDateValue,
} from '../lib/date-text'
import { cn, UI_SCOPE } from '../lib/utils'
import { Button } from './ui/button'
import { Calendar } from './ui/calendar'
import { Input } from './ui/input'
import { Popover, PopoverContent, PopoverTrigger } from './ui/popover'

export { formatDateText, formatDateTimeText, parseDateText, parseDateTimeText, toDateValue }

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

function useCalendarLocale(region: string | undefined) {
  const { i18n } = useTranslation(UI_NAMESPACE)
  return { locale: LOCALES[i18n.language] ?? enUS, weekStartsOn: weekStartsOn(region) }
}

const pad = (n: number, width = 2) => String(n).padStart(width, '0')

/** `YYYY-MM-DD` as a local date (midnight), or undefined when it is not one. */
export function parseDateValue(value: string | null | undefined): Date | undefined {
  const match = value ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(value) : null
  if (!match) return undefined
  return localDate(Number(match[1]), Number(match[2]), Number(match[3]))
}

function instant(value: string | null | undefined): Date | undefined {
  if (!value) return undefined
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? undefined : date
}

function asMatchers(matchers: Matcher | Matcher[] | undefined): Matcher[] {
  return matchers === undefined ? [] : Array.isArray(matchers) ? matchers : [matchers]
}

/** Why typed text was not taken. */
type Refusal = 'invalid' | 'notAllowed'

/**
 * The text of a typed field: what the user is typing (a draft) until it is read on leaving the field or Enter.
 * A draft that is no valid value stays, marked invalid, and is not given to the caller, which learns of it through
 * `onInvalidChange` (true while such a draft stands, false once it is corrected, replaced or the field is gone).
 * A new value from outside, or a new `resetKey`, replaces the draft.
 */
function useTypedText(
  shown: string,
  resetKey: unknown,
  read: (text: string) => 'same' | Refusal | (() => void),
  onInvalidChange: ((invalid: boolean) => void) | undefined,
) {
  const [draft, setDraft] = useState<string | null>(null)
  const [refusal, setRefusal] = useState<Refusal | null>(null)
  const [last, setLast] = useState({ shown, resetKey })
  if (last.shown !== shown || last.resetKey !== resetKey) {
    setLast({ shown, resetKey })
    setDraft(null)
    setRefusal(null)
  }
  const report = useRef(onInvalidChange)
  useEffect(() => {
    report.current = onInvalidChange
  })
  const invalid = refusal !== null
  useEffect(() => {
    if (!invalid) return
    report.current?.(true)
    return () => report.current?.(false)
  }, [invalid])

  const commit = () => {
    if (draft === null) return
    const result = read(draft)
    if (result === 'invalid' || result === 'notAllowed') {
      setRefusal(result)
      return
    }
    setDraft(null)
    setRefusal(null)
    if (result !== 'same') result()
  }
  return {
    text: draft ?? shown,
    refusal,
    onChange: (text: string) => setDraft(text),
    commit,
    onKeyDown: (event: KeyboardEvent<HTMLInputElement>) => {
      // Enter reads what was typed (as a date picker confirms its choice) without submitting a form around the
      // field; Enter with nothing new typed submits it as in any other field.
      if (event.key === 'Enter' && draft !== null) {
        event.preventDefault()
        commit()
      }
    },
  }
}

type InputAttributes = Omit<
  ComponentProps<'input'>,
  'value' | 'onChange' | 'type' | 'defaultValue' | 'placeholder' | 'disabled' | 'className'
>

interface TypedFieldProps extends InputAttributes {
  placeholder?: string
  disabled?: boolean
  /** Offers a button that empties the field. */
  clearable?: boolean
  className?: string
  /**
   * The region whose form the field shows and reads (decision D22 item 7); the application's region by default,
   * the neutral ISO form without one. The ISO form is read in every region.
   */
  region?: string
  /** Called with true while the field holds typed text that is no allowed value, false when that ends. */
  onInvalidChange?: (invalid: boolean) => void
  /** Changing it drops what was typed (a form's reset). */
  resetKey?: string | number
}

export interface DatePickerProps extends TypedFieldProps {
  /** `YYYY-MM-DD`; null or undefined when no date is chosen. */
  value: string | null | undefined
  onChange: (value: string | null) => void
  /** Dates that cannot be chosen (before / after a bound): disabled in the calendar, refused when typed. */
  disabledDays?: Matcher | Matcher[]
}

/** The text field with its problem under it, tied by `aria-describedby`. */
function TypedInput({
  typed,
  format,
  input,
  ariaInvalid,
  describedBy,
  readOnly,
  disabled,
  onBlur,
  placeholder,
  className,
}: {
  typed: ReturnType<typeof useTypedText>
  format: string
  input: InputAttributes
  ariaInvalid: ComponentProps<'input'>['aria-invalid']
  describedBy: string | undefined
  readOnly: boolean | undefined
  disabled: boolean | undefined
  onBlur: ComponentProps<'input'>['onBlur']
  placeholder: string | undefined
  className: string
}) {
  const { t } = useTranslation(UI_NAMESPACE)
  const errorId = useId()
  return (
    <>
      <Input
        {...input}
        type="text"
        autoComplete="off"
        value={typed.text}
        placeholder={placeholder ?? format}
        disabled={disabled}
        readOnly={readOnly}
        aria-invalid={typed.refusal !== null || ariaInvalid || undefined}
        aria-describedby={[describedBy, typed.refusal ? errorId : undefined].filter(Boolean).join(' ') || undefined}
        onChange={(event) => typed.onChange(event.target.value)}
        onBlur={(event) => {
          typed.commit()
          onBlur?.(event)
        }}
        onKeyDown={typed.onKeyDown}
        className={className}
      />
      {typed.refusal && (
        <p id={errorId} data-slot="date-error" className="text-destructive basis-full text-sm">
          {t(typed.refusal === 'invalid' ? 'date.invalid' : 'date.notAllowed', { format })}
        </p>
      )}
    </>
  )
}

/**
 * A calendar date (a posting date, a due date; CLAUDE.md "time"): typed in the application's region form (or ISO
 * `YYYY-MM-DD`) or picked from a calendar in the interface language (month and year can be chosen in its heading).
 * The text is read when the field is left or on Enter (which then does not submit a form around the field); a
 * text that is no date (or a date not allowed) is not taken: the field is `aria-invalid`, says why under it and
 * tells `onInvalidChange`. Name the text field with a <Label htmlFor={id}> or `aria-label`; it takes the other input
 * attributes too.
 */
export function DatePicker({
  value,
  onChange,
  placeholder,
  disabled,
  clearable = false,
  className,
  disabledDays,
  region = appRegion,
  onInvalidChange,
  resetKey,
  readOnly,
  onBlur,
  'aria-invalid': ariaInvalid,
  'aria-describedby': describedBy,
  ...input
}: DatePickerProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  const calendar = useCalendarLocale(region)
  const [open, setOpen] = useState(false)
  const selected = parseDateValue(value)
  const matchers = asMatchers(disabledDays)
  const format = regionalPattern(region)?.datePlaceholder ?? 'YYYY-MM-DD'
  const typed = useTypedText(
    selected && value ? formatDateText(value, region) : '',
    resetKey,
    (text) => {
      if (text.trim() === '') return selected ? () => onChange(null) : 'same'
      const day = parseDateText(text, region)
      const date = parseDateValue(day)
      if (!day || !date) return 'invalid'
      if (matchers.length > 0 && dateMatchModifiers(date, matchers)) return 'notAllowed'
      return day === value ? 'same' : () => onChange(day)
    },
    onInvalidChange,
  )
  const locked = disabled || readOnly

  return (
    <div data-slot="date-picker" className={cn(UI_SCOPE, 'flex flex-wrap items-center gap-1', className)}>
      <TypedInput
        typed={typed}
        format={format}
        input={{ ...input, inputMode: region ? undefined : 'numeric' }}
        ariaInvalid={ariaInvalid}
        describedBy={describedBy}
        readOnly={readOnly}
        disabled={disabled}
        onBlur={onBlur}
        placeholder={placeholder}
        className="w-auto min-w-32 flex-1 tabular-nums"
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

export interface DateTimePickerProps extends TypedFieldProps {
  /** A point in time, ISO 8601 (`2026-01-31T05:05:09Z`); null or undefined when none is chosen. */
  value: string | null | undefined
  /** The chosen local date and time as an ISO 8601 instant in UTC. */
  onChange: (value: string | null) => void
  /** Days that cannot be chosen: disabled in the calendar, refused when typed. */
  disabledDays?: Matcher | Matcher[]
  /** The latest point in time that may be chosen (no future: now); later days are disabled, later times refused. */
  toDate?: Date
}

const TIME = /^(\d{2}):(\d{2})(?::(\d{2}))?$/

/**
 * A point in time: typed in the application's region form (or ISO local time `YYYY-MM-DD HH:mm:ss`, minutes and
 * seconds may be left out), or a day picked from the calendar and a time (to the second) in the browser's time zone,
 * given back as an instant. Typed text is read as in {@link DatePicker}. A value is changed only when the text names
 * another second, so the milliseconds of a value the user did not change are kept.
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
  region = appRegion,
  onInvalidChange,
  resetKey,
  readOnly,
  onBlur,
  'aria-invalid': ariaInvalid,
  'aria-describedby': describedBy,
  ...input
}: DateTimePickerProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  const calendar = useCalendarLocale(region)
  const [open, setOpen] = useState(false)
  const timeId = useId()
  const current = instant(value)
  const shown = current ? formatDateTimeText(current, region) : ''
  const time = current ? `${pad(current.getHours())}:${pad(current.getMinutes())}:${pad(current.getSeconds())}` : ''
  const format = regionalPattern(region)?.dateTimePlaceholder ?? 'YYYY-MM-DD HH:mm:ss'
  const dayMatchers = [...asMatchers(disabledDays), ...(toDate ? [{ after: toDate }] : [])]
  const allowed = (date: Date) =>
    !(toDate && date.getTime() > toDate.getTime()) &&
    !(dayMatchers.length > 0 && dateMatchModifiers(new Date(date.getFullYear(), date.getMonth(), date.getDate()), dayMatchers))

  const typed = useTypedText(
    shown,
    resetKey,
    (text) => {
      if (text.trim() === '') return current ? () => onChange(null) : 'same'
      const date = parseDateTimeText(text, region)
      if (!date) return 'invalid'
      if (!allowed(date)) return 'notAllowed'
      return current && Math.floor(current.getTime() / 1000) === Math.floor(date.getTime() / 1000)
        ? 'same'
        : () => onChange(date.toISOString())
    },
    onInvalidChange,
  )

  const emit = (date: Date | undefined, hms: string) => {
    if (!date) {
      onChange(null)
      return
    }
    const [h = 0, m = 0, s = 0] = hms.split(':').map((part) => Number(part) || 0)
    let next = new Date(date.getFullYear(), date.getMonth(), date.getDate(), h, m, s)
    // The day is allowed, the time on it may not be (today with a time after now): the latest allowed.
    if (toDate && next.getTime() > toDate.getTime()) next = new Date(Math.floor(toDate.getTime() / 1000) * 1000)
    if (!allowed(next)) return
    onChange(next.toISOString())
  }
  const locked = disabled || readOnly

  return (
    <div data-slot="date-time-picker" className={cn(UI_SCOPE, 'flex flex-wrap items-center gap-1', className)}>
      <TypedInput
        typed={typed}
        format={format}
        input={input}
        ariaInvalid={ariaInvalid}
        describedBy={describedBy}
        readOnly={readOnly}
        disabled={disabled}
        onBlur={onBlur}
        placeholder={placeholder}
        className="w-auto min-w-48 flex-1 tabular-nums"
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
              onChange={(event) => {
                // Emptied or half typed: the time stays. A browser giving hours and minutes keeps the seconds.
                const match = TIME.exec(event.target.value)
                if (!match || !current) return
                emit(current, `${match[1]}:${match[2]}:${match[3] ?? pad(current.getSeconds())}`)
              }}
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
