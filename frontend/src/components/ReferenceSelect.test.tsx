import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { expectAccessible } from '../test/axe'
import ReferenceSelect from './ReferenceSelect'

const lookups: string[] = []

vi.mock('../meta/references', () => ({
  useLookup: (_dataset: string, q: string) => {
    lookups.push(q)
    return {
      isFetching: false,
      data: [
        { id: 's1', label: 'Kanto Tea' },
        { id: 's2', label: { zh: '关西茶', en: 'Kansai Tea' } },
      ],
    }
  },
  useLabels: (_dataset: string, ids: string[]) => ({ data: ids.includes('s9') ? { s9: { ja: '九州茶' } } : {} }),
}))

describe('ReferenceSelect', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  it('shows the current value by its label, in the best language', () => {
    render(<ReferenceSelect datasetId="d" defaultLocale="en" value="s9" aria-label="Supplier" />)
    expect(screen.getByRole('combobox', { name: 'Supplier' })).toHaveTextContent('九州茶')
  })

  it('offers the matches by their display text and reports the chosen key', async () => {
    const onChange = vi.fn()
    render(
      <>
        <label htmlFor="supplier">Supplier</label>
        <ReferenceSelect id="supplier" datasetId="d" defaultLocale="en" onChange={onChange} aria-required placeholder="Pick one" />
      </>,
    )
    const trigger = screen.getByRole('combobox', { name: 'Supplier' })
    expect(trigger).toHaveAttribute('aria-required', 'true')
    expect(trigger).toHaveTextContent('Pick one')
    await userEvent.click(trigger)
    await expectAccessible()
    await userEvent.click(screen.getByRole('option', { name: 'Kansai Tea' }))
    expect(onChange).toHaveBeenCalledWith('s2')
  })

  it('can be cleared', async () => {
    const onChange = vi.fn()
    render(<ReferenceSelect datasetId="d" value="s1" onChange={onChange} aria-label="Supplier" />)
    await userEvent.click(screen.getByRole('button', { name: 'Clear' }))
    expect(onChange).toHaveBeenCalledWith(null)
  })

  it('looks up what was typed once typing pauses', async () => {
    vi.useFakeTimers()
    try {
      lookups.length = 0
      render(<ReferenceSelect datasetId="d" aria-label="Supplier" />)
      fireEvent.click(screen.getByRole('combobox', { name: 'Supplier' }))
      await act(() => vi.advanceTimersByTimeAsync(0))
      fireEvent.change(screen.getByPlaceholderText('Search…'), { target: { value: 'Kan' } })
      await act(() => vi.advanceTimersByTimeAsync(100))
      expect(lookups).not.toContain('Kan')
      await act(() => vi.advanceTimersByTimeAsync(200))
      expect(lookups).toContain('Kan')
    } finally {
      vi.useRealTimers()
    }
  })
})
