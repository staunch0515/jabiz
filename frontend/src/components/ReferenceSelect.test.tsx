import { act, fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
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
  it('shows the current value by its label, in the best language', async () => {
    await i18n.changeLanguage('en')
    render(<ReferenceSelect datasetId="d" defaultLocale="en" value="s9" />)
    expect(screen.getByText('九州茶')).toBeTruthy()
  })

  it('offers the matches by their display text and reports the chosen key', async () => {
    await i18n.changeLanguage('en')
    const onChange = vi.fn()
    render(<ReferenceSelect datasetId="d" defaultLocale="en" onChange={onChange} />)
    fireEvent.mouseDown(screen.getByRole('combobox'))
    const option = await screen.findByText('Kansai Tea', { selector: '.ant-select-item-option-content' })
    fireEvent.click(option)
    expect(onChange).toHaveBeenCalledWith('s2')
  })

  it('looks up what was typed once typing pauses', async () => {
    vi.useFakeTimers()
    lookups.length = 0
    render(<ReferenceSelect datasetId="d" />)
    fireEvent.mouseDown(screen.getByRole('combobox'))
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'Kan' } })
    expect(lookups).not.toContain('Kan')
    await act(() => vi.advanceTimersByTimeAsync(300))
    expect(lookups).toContain('Kan')
    vi.useRealTimers()
  })
})
