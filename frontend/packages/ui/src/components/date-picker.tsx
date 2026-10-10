import { useId, useState, type ComponentProps } from 'react'
import { appRegion, formatDate, formatDateTime } from '@jabiz/client'
import { CalendarIcon, XIcon } from 'lucide-react'
import type { Locale } from 'react-day-picker'
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
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
  return Number.isNaN(date.getTime()) ? undefined : date
}

/** A local date as `YYYY-MM-DD`, the form of date fields (LocalDate) in the API. */
export function toDateValue(date: Date): string {
  return `${pad(date.getFullYear(), 4)}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

type TriggerProps = Pick<
  ComponentProps<'button'>,
  'id' | 'aria-label' | 'aria-labelledby' | 'aria-describedby' | 'aria-invalid'
>

export interface DatePickerProps extends TriggerProps {
  /** `YYYY-MM-DD`; null or undefined when no date is chosen. */
  value: string | null | undefined
  onChange: (value: string | null) => void
  placeholder?: string
  disabled?: boolean
  /** Offers a button that empties the field. */
  clearable?: boolean
  className?: string
  /** Dates that cannot be chosen (before / after a bound). */
  disabledDays?: ComponentProps<typeof Calendar>['disabled']
}

/**
 * A calendar date (a posting date, a due date; CLAUDE.md "time"): a button showing the date in the application's
 * region and a calendar in the interface language. Name it with a <Label htmlFor={id}> or `aria-label`.
 */
export function DatePicker({
  value,
  onChange,
  placeholder,
  disabled,
  clearable = false,
  className,
  disabledDays,
  ...trigger
}: DatePickerProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  const calendar = useCalendarLocale()
  const [open, setOpen] = useState(false)
  const selected = parseDateValue(value)

  return (
    <div className={cn(UI_SCOPE, 'flex items-center gap-1', className)}>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            variant="outline"
            disabled={disabled}
            className={cn(UI_SCOPE, 'w-full min-w-40 justify-start font-normal', !selected && 'text-muted-foreground')}
            {...trigger}
          >
            <CalendarIcon aria-hidden />
            {selected && value ? formatDate(value) : (placeholder ?? t('date.placeholder'))}
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-auto p-0" align="start">
          <Calendar
            mode="single"
            selected={selected}
            defaultMonth={selected}
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
      {clearable && selected && !disabled && (
        <Button variant="ghost" size="icon-sm" aria-label={t('date.clear')} onClick={() => onChange(null)}>
          <XIcon aria-hidden />
        </Button>
      )}
    </div>
  )
}

export interface DateTimePickerProps extends TriggerProps {
  /** A point in time, ISO 8601 (`2026-01-31T05:05:09Z`); null or undefined when none is chosen. */
  value: string | null | undefined
  /** The chosen local date and time as an ISO 8601 instant in UTC. */
  onChange: (value: string | null) => void
  placeholder?: string
  disabled?: boolean
  clearable?: boolean
  className?: string
}

/**
 * A point in time: a date from the calendar and a time (to the second) in the browser's time zone, given back as
 * an instant. Shown in the application's region like every other time.
 */
export function DateTimePicker({
  value,
  onChange,
  placeholder,
  disabled,
  clearable = false,
  className,
  ...trigger
}: DateTimePickerProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  const calendar = useCalendarLocale()
  const [open, setOpen] = useState(false)
  const timeId = useId()
  const parsed = value ? new Date(value) : undefined
  const current = parsed && !Number.isNaN(parsed.getTime()) ? parsed : undefined
  const time = current ? `${pad(current.getHours())}:${pad(current.getMinutes())}:${pad(current.getSeconds())}` : ''

  const emit = (date: Date | undefined, hms: string) => {
    if (!date) {
      onChange(null)
      return
    }
    const [h = 0, m = 0, s = 0] = hms.split(':').map((part) => Number(part) || 0)
    onChange(new Date(date.getFullYear(), date.getMonth(), date.getDate(), h, m, s).toISOString())
  }

  return (
    <div className={cn(UI_SCOPE, 'flex items-center gap-1', className)}>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            variant="outline"
            disabled={disabled}
            className={cn(UI_SCOPE, 'w-full min-w-52 justify-start font-normal', !current && 'text-muted-foreground')}
            {...trigger}
          >
            <CalendarIcon aria-hidden />
            {current ? formatDateTime(value) : (placeholder ?? t('date.placeholder'))}
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-auto p-0" align="start">
          <Calendar
            mode="single"
            selected={current}
            defaultMonth={current}
            onSelect={(date) => {
              if (date || clearable) emit(date, time || '00:00:00')
            }}
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
      {clearable && current && !disabled && (
        <Button variant="ghost" size="icon-sm" aria-label={t('date.clear')} onClick={() => onChange(null)}>
          <XIcon aria-hidden />
        </Button>
      )}
    </div>
  )
}
