import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { i18n } from '@jabiz/client'
import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '../test/axe'
import {
  DatePicker,
  DateTimePicker,
  formatDateTimeText,
  parseDateText,
  parseDateTimeText,
  parseDateValue,
  toDateValue,
} from './date-picker'

describe('date values', () => {
  it('read and write YYYY-MM-DD as local dates', () => {
    expect(toDateValue(parseDateValue('2026-01-31')!)).toBe('2026-01-31')
    expect(parseDateValue('2026-1-31')).toBeUndefined()
    expect(parseDateValue('2026-02-30')).toBeUndefined()
    expect(parseDateValue(null)).toBeUndefined()
    expect(toDateValue(new Date(2026, 1, 3))).toBe('2026-02-03')
  })

  it('read typed dates without a time zone and refuse what is no date', () => {
    expect(parseDateText(' 2026-1-5 ')).toBe('2026-01-05')
    expect(parseDateText('2026-02-29')).toBeUndefined()
    expect(parseDateText('2024-02-29')).toBe('2024-02-29')
    expect(parseDateText('2026-13-01')).toBeUndefined()
    expect(parseDateText('2026-01-05 10:00')).toBeUndefined()
    expect(parseDateText('yesterday')).toBeUndefined()
  })

  it('read typed points in time as local time, to the second', () => {
    expect(parseDateTimeText('2026-01-31 14:05:09')).toEqual(new Date(2026, 0, 31, 14, 5, 9))
    expect(parseDateTimeText('2026-01-31T14:05')).toEqual(new Date(2026, 0, 31, 14, 5, 0))
    expect(parseDateTimeText('2026-01-31')).toEqual(new Date(2026, 0, 31))
    expect(parseDateTimeText('2026-01-31 24:00:00')).toBeUndefined()
    expect(parseDateTimeText('2026-01-31 10:60')).toBeUndefined()
    expect(formatDateTimeText(new Date(2026, 0, 31, 4, 5, 9, 123))).toBe('2026-01-31 04:05:09')
  })
})

describe('DatePicker', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  it('shows the date in a named text field and picks a day from the calendar', async () => {
    const onChange = vi.fn()
    render(<DatePicker aria-label="Due date" value="2026-01-15" onChange={onChange} />)
    expect(screen.getByLabelText('Due date')).toHaveValue('2026-01-15')
    await userEvent.click(screen.getByRole('button', { name: 'Open calendar' }))
    expect(screen.getByRole('grid')).toBeInTheDocument()
    // The heading chooses month and year.
    expect(screen.getAllByRole('combobox').length).toBe(2)
    await expectAccessible()
    await userEvent.click(screen.getByRole('button', { name: /January 20th, 2026/ }))
    expect(onChange).toHaveBeenCalledWith('2026-01-20')
    expect(screen.queryByRole('grid')).toBeNull()
  })

  it('takes a typed date when the field is left or on Enter, and refuses one that is no date', async () => {
    function Field() {
      const [value, setValue] = useState<string | null>(null)
      return (
        <>
          <label htmlFor="due">Due</label>
          <DatePicker id="due" value={value} onChange={setValue} clearable />
          <output>{String(value)}</output>
        </>
      )
    }
    render(<Field />)
    const input = screen.getByLabelText('Due')
    await userEvent.type(input, '2026-3-7{Enter}')
    expect(screen.getByRole('status')).toHaveTextContent('2026-03-07')
    expect(input).toHaveValue('2026-03-07')
    expect(input).not.toHaveAttribute('aria-invalid')

    await userEvent.clear(input)
    await userEvent.type(input, '2026-02-30')
    await userEvent.tab()
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveValue('2026-02-30')
    expect(screen.getByRole('status')).toHaveTextContent('2026-03-07')

    // Emptied: no date.
    await userEvent.clear(input)
    await userEvent.tab()
    expect(screen.getByRole('status')).toHaveTextContent('null')
  })

  it('refuses typed days outside the allowed range and disables them in the calendar', async () => {
    const onChange = vi.fn()
    render(
      <DatePicker
        aria-label="Due"
        value="2026-01-15"
        onChange={onChange}
        disabledDays={{ before: new Date(2026, 0, 10) }}
      />,
    )
    const input = screen.getByLabelText('Due')
    await userEvent.clear(input)
    await userEvent.type(input, '2026-01-09{Enter}')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(onChange).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Open calendar' }))
    expect(screen.getByRole('button', { name: /January 9th, 2026/ })).toBeDisabled()
  })

  it('keeps the date when the chosen day is clicked again, unless the field may be emptied', async () => {
    const onChange = vi.fn()
    const { rerender } = render(<DatePicker aria-label="Due date" value="2026-01-15" onChange={onChange} />)
    await userEvent.click(screen.getByRole('button', { name: 'Open calendar' }))
    await userEvent.click(screen.getByRole('button', { name: /January 15th, 2026/ }))
    expect(onChange).not.toHaveBeenCalled()
    expect(screen.queryByRole('grid')).toBeNull()

    rerender(<DatePicker aria-label="Due date" value="2026-01-15" onChange={onChange} clearable />)
    await userEvent.click(screen.getByRole('button', { name: 'Open calendar' }))
    await userEvent.click(screen.getByRole('button', { name: /January 15th, 2026/ }))
    expect(onChange).toHaveBeenCalledWith(null)
  })

  it('is used with the keyboard: the chosen day has focus, arrows move it, Enter picks', async () => {
    function Field() {
      const [value, setValue] = useState<string | null>('2026-01-15')
      return (
        <>
          <DatePicker aria-label="Due date" value={value} onChange={setValue} />
          <output>{value}</output>
        </>
      )
    }
    render(<Field />)
    screen.getByRole('button', { name: 'Open calendar' }).focus()
    await userEvent.keyboard('{Enter}')
    expect(screen.getByRole('button', { name: /January 15th, 2026/ })).toHaveFocus()
    await userEvent.keyboard('{ArrowRight}{Enter}')
    expect(screen.getByRole('status')).toHaveTextContent('2026-01-16')
  })

  it('offers clearing and the calendar in the interface language', async () => {
    await act(() => i18n.changeLanguage('zh'))
    const onChange = vi.fn()
    const { rerender } = render(<DatePicker aria-label="到期日" value={null} onChange={onChange} clearable />)
    expect(screen.getByLabelText('到期日')).toHaveAttribute('placeholder', 'YYYY-MM-DD')
    expect(screen.queryByRole('button', { name: '清除' })).toBeNull()
    rerender(<DatePicker aria-label="到期日" value="2026-03-01" onChange={onChange} clearable />)
    await userEvent.click(screen.getByRole('button', { name: '清除' }))
    expect(onChange).toHaveBeenCalledWith(null)
    await userEvent.click(screen.getByRole('button', { name: '打开日历' }))
    // The calendar's own buttons are named in Chinese too.
    expect(screen.getByRole('button', { name: /上/ })).toBeInTheDocument()
    await expectAccessible()
  })
})

describe('DateTimePicker', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  it('combines the day and the time into an instant', async () => {
    const onChange = vi.fn()
    const start = new Date(2026, 0, 15, 9, 30, 0).toISOString()
    render(<DateTimePicker aria-label="Effective from" value={start} onChange={onChange} />)
    expect(screen.getByLabelText('Effective from')).toHaveValue('2026-01-15 09:30:00')
    await userEvent.click(screen.getByRole('button', { name: 'Open calendar' }))
    await userEvent.click(screen.getByRole('button', { name: /January 20th, 2026/ }))
    expect(onChange).toHaveBeenLastCalledWith(new Date(2026, 0, 20, 9, 30, 0).toISOString())

    // Clicking the chosen day again keeps it (the field is not clearable).
    onChange.mockClear()
    await userEvent.click(screen.getByRole('button', { name: /January 15th, 2026/ }))
    expect(onChange).not.toHaveBeenCalled()

    // The value is the caller's (still the 15th here); a time input changes as a whole.
    fireEvent.change(screen.getByLabelText('Time'), { target: { value: '14:05:09' } })
    expect(onChange).toHaveBeenLastCalledWith(new Date(2026, 0, 15, 14, 5, 9).toISOString())
    await expectAccessible()
  })

  it('takes typed local time as an instant in UTC, and keeps the milliseconds of an unchanged value', async () => {
    const onChange = vi.fn()
    const value = new Date(2026, 0, 15, 9, 30, 0, 456).toISOString()
    render(<DateTimePicker aria-label="At" value={value} onChange={onChange} />)
    const input = screen.getByLabelText('At')
    // Left without a change: nothing is given back (the milliseconds stay).
    await userEvent.click(input)
    await userEvent.tab()
    expect(onChange).not.toHaveBeenCalled()

    await userEvent.clear(input)
    await userEvent.type(input, '2026-07-01 23:59{Enter}')
    expect(onChange).toHaveBeenLastCalledWith(new Date(2026, 6, 1, 23, 59, 0).toISOString())

    await userEvent.clear(input)
    await userEvent.type(input, '2026-07-01 25:00{Enter}')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('refuses times after the latest allowed one and days that are disabled', async () => {
    const onChange = vi.fn()
    const toDate = new Date(2026, 0, 15, 12, 0, 0)
    render(
      <DateTimePicker
        aria-label="At"
        value={null}
        onChange={onChange}
        toDate={toDate}
        disabledDays={{ dayOfWeek: [0] }}
      />,
    )
    const input = screen.getByLabelText('At')
    await userEvent.type(input, '2026-01-15 12:00:01{Enter}')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    await userEvent.clear(input)
    // 11 January 2026 is a Sunday.
    await userEvent.type(input, '2026-01-11 08:00{Enter}')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(onChange).not.toHaveBeenCalled()
    await userEvent.clear(input)
    await userEvent.type(input, '2026-01-15 11:59:59{Enter}')
    expect(onChange).toHaveBeenCalledWith(new Date(2026, 0, 15, 11, 59, 59).toISOString())

    await userEvent.click(screen.getByRole('button', { name: 'Open calendar' }))
    expect(screen.getByRole('button', { name: /January 16th, 2026/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: /January 11th, 2026/ })).toBeDisabled()
  })
})

describe('typed text the pickers do not take', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  it('says why under the field, tells the caller, and drops the text on a reset', async () => {
    const onInvalidChange = vi.fn()
    const { rerender } = render(
      <DatePicker aria-label="Due" value={null} onChange={() => {}} onInvalidChange={onInvalidChange} resetKey={1} />,
    )
    const input = screen.getByLabelText('Due')
    await userEvent.type(input, '2026-02-30')
    await userEvent.tab()
    expect(input).toHaveAccessibleDescription('Not a valid date (YYYY-MM-DD).')
    expect(onInvalidChange).toHaveBeenLastCalledWith(true)
    await expectAccessible()

    // The same value again (null) with a new reset key: the text goes.
    rerender(
      <DatePicker aria-label="Due" value={null} onChange={() => {}} onInvalidChange={onInvalidChange} resetKey={2} />,
    )
    expect(input).toHaveValue('')
    expect(input).not.toHaveAttribute('aria-invalid')
    expect(onInvalidChange).toHaveBeenLastCalledWith(false)
  })

  it('says when a typed day is not allowed', async () => {
    render(<DatePicker aria-label="Due" value={null} onChange={() => {}} disabledDays={{ before: new Date(2026, 0, 10) }} />)
    await userEvent.type(screen.getByLabelText('Due'), '2026-01-01{Enter}')
    expect(screen.getByLabelText('Due')).toHaveAccessibleDescription('This date cannot be chosen.')
  })

  it('reports false when a field holding invalid text goes away', async () => {
    const onInvalidChange = vi.fn()
    const { unmount } = render(
      <DateTimePicker aria-label="At" value={null} onChange={() => {}} onInvalidChange={onInvalidChange} />,
    )
    await userEvent.type(screen.getByLabelText('At'), 'later{Enter}')
    expect(onInvalidChange).toHaveBeenLastCalledWith(true)
    unmount()
    expect(onInvalidChange).toHaveBeenLastCalledWith(false)
  })
})

describe('the time of a DateTimePicker', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  it('stays when the time field is emptied or half typed, and keeps the seconds of hours and minutes', async () => {
    const onChange = vi.fn()
    const value = new Date(2026, 0, 15, 9, 30, 45).toISOString()
    render(<DateTimePicker aria-label="At" value={value} onChange={onChange} />)
    await userEvent.click(screen.getByRole('button', { name: 'Open calendar' }))
    const time = screen.getByLabelText('Time')
    fireEvent.change(time, { target: { value: '' } })
    fireEvent.change(time, { target: { value: '1' } })
    expect(onChange).not.toHaveBeenCalled()
    fireEvent.change(time, { target: { value: '10:15' } })
    expect(onChange).toHaveBeenLastCalledWith(new Date(2026, 0, 15, 10, 15, 45).toISOString())
  })

  it('is not set on a disabled day', async () => {
    const onChange = vi.fn()
    // 11 January 2026 is a Sunday.
    const value = new Date(2026, 0, 11, 9, 0, 0).toISOString()
    render(<DateTimePicker aria-label="At" value={value} onChange={onChange} disabledDays={{ dayOfWeek: [0] }} />)
    await userEvent.click(screen.getByRole('button', { name: 'Open calendar' }))
    fireEvent.change(screen.getByLabelText('Time'), { target: { value: '10:00:00' } })
    expect(onChange).not.toHaveBeenCalled()
  })
})

describe('in a region (en-US)', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  it('reads and writes the regional form as well as ISO', () => {
    expect(parseDateText('01/31/2026', 'en-US')).toBe('2026-01-31')
    expect(parseDateText('2026-01-31', 'en-US')).toBe('2026-01-31')
    expect(parseDateText('31/01/2026', 'en-US')).toBeUndefined()
    expect(parseDateText('31/01/2026', 'en-GB')).toBe('2026-01-31')
    expect(parseDateText('01/31/26', 'en-US')).toBeUndefined()
    expect(parseDateTimeText('01/31/2026, 02:05:09 PM', 'en-US')).toEqual(new Date(2026, 0, 31, 14, 5, 9))
    expect(parseDateTimeText('01/31/2026 12:00 am', 'en-US')).toEqual(new Date(2026, 0, 31, 0, 0, 0))
    expect(parseDateTimeText('01/31/2026, 13:00 PM', 'en-US')).toBeUndefined()
    expect(parseDateTimeText('01/31/2026 soon', 'en-US')).toBeUndefined()
    const at = new Date(2026, 0, 31, 14, 5, 9)
    expect(parseDateTimeText(formatDateTimeText(at, 'en-US'), 'en-US')).toEqual(at)
  })

  it('shows the regional form with its pattern as the placeholder', async () => {
    const onChange = vi.fn()
    const { rerender } = render(<DatePicker aria-label="Due" value="2026-01-31" onChange={onChange} region="en-US" />)
    const input = screen.getByLabelText('Due')
    expect(input).toHaveValue('01/31/2026')
    expect(input).toHaveAttribute('placeholder', 'MM/DD/YYYY')
    await userEvent.clear(input)
    await userEvent.type(input, '02/01/2026{Enter}')
    expect(onChange).toHaveBeenLastCalledWith('2026-02-01')

    rerender(<DateTimePicker aria-label="At" value={null} onChange={onChange} region="en-US" />)
    const at = screen.getByLabelText('At') as HTMLInputElement
    expect(at).toHaveAttribute('placeholder', 'MM/DD/YYYY, hh:mm:ss AM')
    await userEvent.type(at, '02/01/2026 3:04 PM{Enter}')
    expect(onChange).toHaveBeenLastCalledWith(new Date(2026, 1, 1, 15, 4, 0).toISOString())
  })
})
