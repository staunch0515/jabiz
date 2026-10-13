import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { i18n } from '@jabiz/client'
import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '../test/axe'
import { SegmentedControl } from './segmented-control'

describe('SegmentedControl', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  function Shown({ onChange }: { onChange?: (value: string) => void }) {
    const [value, setValue] = useState<'all' | 'error' | 'duplicate'>('all')
    return (
      <>
        <SegmentedControl
          aria-label="Show"
          value={value}
          onChange={(next) => {
            setValue(next)
            onChange?.(next)
          }}
          options={[
            { value: 'all', label: 'All' },
            { value: 'error', label: 'Errors' },
            { value: 'duplicate', label: 'Duplicates' },
          ]}
        />
        <output>{value}</output>
      </>
    )
  }

  it('is a named radio group whose chosen option is checked', async () => {
    render(<Shown />)
    const group = screen.getByRole('radiogroup', { name: 'Show' })
    const options = within(group).getAllByRole('radio')
    expect(options.map((o) => o.getAttribute('aria-checked'))).toEqual(['true', 'false', 'false'])
    await userEvent.click(screen.getByRole('radio', { name: 'Errors' }))
    expect(screen.getByRole('status')).toHaveTextContent('error')
    expect(screen.getByRole('radio', { name: 'Errors' })).toHaveAttribute('aria-checked', 'true')
    await expectAccessible()
  })

  it('keeps an option chosen when it is pressed again, and is used with the keyboard', async () => {
    const onChange = vi.fn()
    render(<Shown onChange={onChange} />)
    await userEvent.click(screen.getByRole('radio', { name: 'All' }))
    expect(onChange).not.toHaveBeenCalled()
    expect(screen.getByRole('radio', { name: 'All' })).toHaveAttribute('aria-checked', 'true')

    // The click left focus on the chosen option; arrow keys move it along the group.
    expect(screen.getByRole('radio', { name: 'All' })).toHaveFocus()
    await userEvent.keyboard('{ArrowRight}{ArrowRight}')
    expect(screen.getByRole('radio', { name: 'Duplicates' })).toHaveFocus()
    await userEvent.keyboard(' ')
    expect(onChange).toHaveBeenCalledWith('duplicate')
  })
})
