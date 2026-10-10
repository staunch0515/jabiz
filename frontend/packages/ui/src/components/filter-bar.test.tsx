import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { i18n } from '@jabiz/client'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '../test/axe'
import { FilterBar, filledFilters, type FilterField } from './filter-bar'

const fields: FilterField[] = [
  { kind: 'text', name: 'code', label: 'Code' },
  { kind: 'select', name: 'country', label: 'Country', options: [{ value: 'JP', label: 'Japan' }, { value: 'CN', label: 'China' }] },
  { kind: 'number-range', name: 'limit', label: 'Credit limit' },
  { kind: 'datetime-range', name: 'created', label: 'Created' },
  { kind: 'date-range', name: 'due', label: 'Due' },
]

describe('FilterBar', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  it('names every field and range, and is accessible', async () => {
    render(<FilterBar fields={fields} onSubmit={() => {}} />)
    expect(screen.getByRole('search', { name: 'Filters' })).toBeInTheDocument()
    expect(screen.getByLabelText('Code')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Country' })).toBeInTheDocument()
    const limit = screen.getByRole('group', { name: 'Credit limit' })
    expect(within(limit).getByLabelText('Credit limit, from')).toBeInTheDocument()
    expect(within(limit).getByLabelText('Credit limit, to')).toBeInTheDocument()
    expect(within(screen.getByRole('group', { name: 'Created' })).getByLabelText('Created, from')).toBeInTheDocument()
    expect(within(screen.getByRole('group', { name: 'Due' })).getByLabelText('Due, to')).toBeInTheDocument()
    await expectAccessible()
  })

  it('submits the filled-in fields with the button or Enter, and resets', async () => {
    const onSubmit = vi.fn()
    render(<FilterBar fields={fields} values={{ code: 'A' }} onSubmit={onSubmit} />)
    expect(screen.getByLabelText('Code')).toHaveValue('A')
    await userEvent.type(screen.getByLabelText('Credit limit, from'), '100')
    await userEvent.click(screen.getByRole('button', { name: 'Query' }))
    expect(onSubmit).toHaveBeenLastCalledWith({ code: 'A', limit: { from: '100' } })

    // Enter in a date field reads the typed date first.
    await userEvent.type(screen.getByLabelText('Due, to'), '2026-02-01{Enter}')
    expect(onSubmit).toHaveBeenLastCalledWith({ code: 'A', limit: { from: '100' }, due: { to: '2026-02-01' } })
    // An invalid date does not submit.
    onSubmit.mockClear()
    await userEvent.type(screen.getByLabelText('Due, from'), '2026-02-31{Enter}')
    expect(onSubmit).not.toHaveBeenCalled()

    await userEvent.type(screen.getByLabelText('Code'), 'B{Enter}')
    expect(onSubmit).toHaveBeenLastCalledWith({ code: 'AB', limit: { from: '100' }, due: { to: '2026-02-01' } })

    await userEvent.click(screen.getByRole('button', { name: 'Reset' }))
    expect(onSubmit).toHaveBeenLastCalledWith({})
    expect(screen.getByLabelText('Code')).toHaveValue('')
  })

  it('takes new values from the caller and calls its reset', async () => {
    const onReset = vi.fn()
    const { rerender } = render(<FilterBar fields={fields} values={{ code: 'A' }} onSubmit={() => {}} onReset={onReset} />)
    rerender(<FilterBar fields={fields} values={{ code: 'Z' }} onSubmit={() => {}} onReset={onReset} />)
    expect(screen.getByLabelText('Code')).toHaveValue('Z')
    await userEvent.click(screen.getByRole('button', { name: 'Reset' }))
    expect(onReset).toHaveBeenCalledOnce()
  })

  it('leaves out empty fields and ranges open at both ends', () => {
    expect(filledFilters({ a: '', b: null, c: { from: '', to: null }, d: { to: '5' }, e: 'x' })).toEqual({
      d: { to: '5' },
      e: 'x',
    })
  })
})
