import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import type { FieldMeta, Violation } from '../meta/types'
import { expectAccessible } from '@jabiz/ui/testing'
import { countries, field } from '../test/fixtures'
import EntityField from './EntityField'

vi.mock('../meta/references', () => ({
  useLookup: () => ({ isFetching: false, data: [{ id: 's1', label: 'Kanto Tea' }] }),
  useLabels: () => ({ data: {} }),
}))

const t = i18n.t.bind(i18n)

/** One field holding its value, as the form does. */
function Field(props: { field: FieldMeta; initial?: unknown; disabled?: boolean; violations?: Violation[]; referenceDatasetId?: string; onValue?: (value: unknown) => void }) {
  const [value, setValue] = useState<unknown>(props.initial)
  return (
    <EntityField
      field={props.field}
      disabled={props.disabled ?? false}
      dictionaries={countries}
      violations={props.violations}
      t={t}
      value={value}
      onChange={(next) => {
        setValue(next)
        props.onValue?.(next)
      }}
      referenceDatasetId={props.referenceDatasetId}
    />
  )
}

describe('EntityField', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  it('enters an amount as typed, without rounding or a fixed scale, with its currency', async () => {
    const onValue = vi.fn()
    render(<Field field={field('creditLimit')} onValue={onValue} />)
    const input = screen.getByLabelText(/^Credit limit/)
    expect(input).toHaveAttribute('aria-required', 'true')
    await userEvent.type(input, '10.555')
    expect(input).toHaveValue('10.555')
    expect(onValue).toHaveBeenLastCalledWith('10.555')
    // The currency is part of the field's description.
    expect(input.getAttribute('aria-describedby')).toBeTruthy()
    expect(document.getElementById(input.getAttribute('aria-describedby')!)).toHaveTextContent('JPY')
    await expectAccessible()
  })

  it('ties its problems to the input, each with its rule code', async () => {
    const violations = [{ field: 'carrierCode', ruleCode: 'TOO_LONG', message: 'Too long.', params: {} }]
    render(<Field field={field('carrierCode')} violations={violations} />)
    const input = screen.getByLabelText(/^Code/)
    expect(input).toHaveAttribute('aria-invalid', 'true')
    const errors = document.getElementById(input.getAttribute('aria-describedby')!)!
    expect(errors.querySelector('[data-rule-code="TOO_LONG"]')).toHaveTextContent('Too long.')
    await expectAccessible()
  })

  it('chooses a code from its dictionary (enabled items, in order) and can empty it', async () => {
    const onValue = vi.fn()
    render(<Field field={field('countryCode')} initial="JP" onValue={onValue} />)
    const country = screen.getByRole('combobox', { name: /^Country/ })
    expect(country).toHaveTextContent('Japan')
    await userEvent.click(country)
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual(['China', 'Japan'])
    await userEvent.click(screen.getByRole('option', { name: 'China' }))
    expect(onValue).toHaveBeenLastCalledWith('CN')
    await userEvent.click(screen.getByRole('button', { name: 'Clear' }))
    expect(onValue).toHaveBeenLastCalledWith(null)
  })

  it('switches a yes / no field, named by its label', async () => {
    const onValue = vi.fn()
    render(<Field field={field('active')} initial={false} onValue={onValue} />)
    await userEvent.click(screen.getByRole('switch', { name: /^Active/ }))
    expect(onValue).toHaveBeenLastCalledWith(true)
  })

  it('types a time and gives back an instant; keeps a stored time as it is', async () => {
    const since = { ...field('effectStartTime'), name: 'since', label: 'Since', systemManaged: false } as FieldMeta
    const onValue = vi.fn()
    render(<Field field={since} initial="2026-01-31T09:00:00.123456Z" onValue={onValue} />)
    const input = screen.getByLabelText(/^Since/)
    expect(onValue).not.toHaveBeenCalled()
    await userEvent.clear(input)
    await userEvent.type(input, '2030-05-01 08:30:00{Enter}')
    expect(onValue).toHaveBeenLastCalledWith(new Date(2030, 4, 1, 8, 30).toISOString())
  })

  it('takes a day as the day, never through a zone', async () => {
    const due = { ...field('versionNo'), name: 'dueDate', label: 'Due', type: 'date', systemManaged: false } as FieldMeta
    const onValue = vi.fn()
    render(<Field field={due} onValue={onValue} />)
    await userEvent.type(screen.getByLabelText(/^Due/), '2026-01-31{Enter}')
    expect(onValue).toHaveBeenLastCalledWith('2026-01-31')
  })

  it('enters JSON in a text area and is disabled when read-only', () => {
    render(<Field field={field('cell')} initial='{"a":1}' disabled />)
    const input = screen.getByLabelText(/^Cell/)
    expect(input.tagName).toBe('TEXTAREA')
    expect(input).toBeDisabled()
    // A read-only field is not marked required.
    expect(input).not.toHaveAttribute('aria-required')
  })

  it('picks a reference by its display text when it can be looked up, else takes its key as text', async () => {
    const supplier = { ...field('carrierCode'), name: 'supplierId', label: 'Supplier', type: 'reference', targetEntity: 'Supplier' } as unknown as FieldMeta
    const onValue = vi.fn()
    const { unmount } = render(<Field field={supplier} referenceDatasetId="urn:s" onValue={onValue} />)
    await userEvent.click(screen.getByRole('combobox', { name: /^Supplier/ }))
    await userEvent.click(screen.getByRole('option', { name: 'Kanto Tea' }))
    expect(onValue).toHaveBeenLastCalledWith('s1')
    unmount()
    render(<Field field={supplier} />)
    expect(screen.getByLabelText(/^Supplier/).tagName).toBe('INPUT')
  })

  it('names each language of a multilingual text with the field and the language', async () => {
    const title = {
      ...field('note'),
      name: 'title',
      label: 'Title',
      type: 'custom',
      kindId: 'jabiz.i18n-text',
      locales: ['zh', 'en'],
      requiredLanguages: ['en'],
      required: true,
    } as unknown as FieldMeta
    render(<Field field={title} />)
    expect(screen.getByRole('group', { name: /^Title/ })).toBeInTheDocument()
    expect(screen.getByLabelText('Title (English)')).toHaveAttribute('aria-required', 'true')
    await expectAccessible()
  })
})
