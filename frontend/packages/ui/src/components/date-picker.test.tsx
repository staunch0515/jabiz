import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { i18n } from '@jabiz/client'
import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '../test/axe'
import { DatePicker, DateTimePicker, parseDateValue, toDateValue } from './date-picker'

describe('date values', () => {
  it('read and write YYYY-MM-DD as local dates', () => {
    expect(toDateValue(parseDateValue('2026-01-31')!)).toBe('2026-01-31')
    expect(parseDateValue('2026-1-31')).toBeUndefined()
    expect(parseDateValue(null)).toBeUndefined()
    expect(toDateValue(new Date(2026, 1, 3))).toBe('2026-02-03')
  })
})

describe('DatePicker', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  it('shows the date on a named button and picks a day from the calendar', async () => {
    const onChange = vi.fn()
    render(<DatePicker aria-label="Due date" value="2026-01-15" onChange={onChange} />)
    const button = screen.getByRole('button', { name: 'Due date' })
    expect(button).toHaveTextContent('2026-01-15')
    await userEvent.click(button)
    expect(screen.getByRole('grid')).toBeInTheDocument()
    await expectAccessible()
    await userEvent.click(screen.getByRole('button', { name: /January 20th, 2026/ }))
    expect(onChange).toHaveBeenCalledWith('2026-01-20')
    expect(screen.queryByRole('grid')).toBeNull()
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
    screen.getByRole('button', { name: 'Due date' }).focus()
    await userEvent.keyboard('{Enter}')
    expect(screen.getByRole('button', { name: /January 15th, 2026/ })).toHaveFocus()
    await userEvent.keyboard('{ArrowRight}{Enter}')
    expect(screen.getByRole('status')).toHaveTextContent('2026-01-16')
  })

  it('offers a placeholder and clearing, in the interface language', async () => {
    await act(() => i18n.changeLanguage('zh'))
    const onChange = vi.fn()
    const { rerender } = render(<DatePicker aria-label="到期日" value={null} onChange={onChange} clearable />)
    expect(screen.getByRole('button', { name: '到期日' })).toHaveTextContent('选择日期')
    expect(screen.queryByRole('button', { name: '清除' })).toBeNull()
    rerender(<DatePicker aria-label="到期日" value="2026-03-01" onChange={onChange} clearable />)
    await userEvent.click(screen.getByRole('button', { name: '清除' }))
    expect(onChange).toHaveBeenCalledWith(null)
    await userEvent.click(screen.getByRole('button', { name: '到期日' }))
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
    await userEvent.click(screen.getByRole('button', { name: 'Effective from' }))
    await userEvent.click(screen.getByRole('button', { name: /January 20th, 2026/ }))
    expect(onChange).toHaveBeenLastCalledWith(new Date(2026, 0, 20, 9, 30, 0).toISOString())

    // The value is the caller's (still the 15th here); a time input changes as a whole.
    fireEvent.change(screen.getByLabelText('Time'), { target: { value: '14:05:09' } })
    expect(onChange).toHaveBeenLastCalledWith(new Date(2026, 0, 15, 14, 5, 9).toISOString())
    await expectAccessible()
  })
})
