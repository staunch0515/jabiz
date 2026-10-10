import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { expectAccessible } from '../test/axe'
import { acceptsDecimalText, DecimalInput, MoneyInput, normalizeDecimalText } from './decimal-input'

describe('decimal text', () => {
  it('accepts what can become a decimal while typing', () => {
    expect(acceptsDecimalText('')).toBe(true)
    expect(acceptsDecimalText('-')).toBe(true)
    expect(acceptsDecimalText('12.')).toBe(true)
    expect(acceptsDecimalText('.5')).toBe(true)
    expect(acceptsDecimalText('1.2.3')).toBe(false)
    expect(acceptsDecimalText('1e3')).toBe(false)
    expect(acceptsDecimalText('12.345', 2)).toBe(false)
    expect(acceptsDecimalText('-1', 2, false)).toBe(false)
  })

  it('is left in its plain form, exactly, and padded for amounts', () => {
    expect(normalizeDecimalText('1.')).toBe('1')
    expect(normalizeDecimalText('.5')).toBe('0.5')
    expect(normalizeDecimalText('-')).toBe('')
    expect(normalizeDecimalText('12345678901234567.89')).toBe('12345678901234567.89')
    expect(normalizeDecimalText('12', 2, true)).toBe('12.00')
    expect(normalizeDecimalText('-0.5', 2, true)).toBe('-0.50')
  })
})

function Field({ money = false }: { money?: boolean }) {
  const [value, setValue] = useState('')
  return (
    <>
      <label htmlFor="amount">Amount</label>
      {money ? (
        <MoneyInput id="amount" value={value} onChange={setValue} scale={2} unit="Kudos" />
      ) : (
        <DecimalInput id="amount" value={value} onChange={setValue} scale={3} />
      )}
      <output>{value}</output>
    </>
  )
}

describe('DecimalInput', () => {
  it('keeps the typed text, refuses what is no decimal, and is named by its label', async () => {
    render(<Field />)
    const input = screen.getByRole('textbox', { name: 'Amount' })
    expect(input).toHaveAttribute('inputmode', 'decimal')
    await userEvent.type(input, '1a2.3456')
    expect(input).toHaveValue('12.345')
    await userEvent.clear(input)
    await userEvent.type(input, '7.')
    await userEvent.tab()
    expect(screen.getByRole('status')).toHaveTextContent(/^7$/)
    await expectAccessible()
  })
})

describe('MoneyInput', () => {
  it("takes the currency's decimals, pads them when left, and is read with its unit", async () => {
    render(<Field money />)
    const input = screen.getByRole('textbox', { name: 'Amount' })
    expect(input).toHaveAccessibleDescription('Kudos')
    await userEvent.type(input, '1,234.567')
    expect(input).toHaveValue('1234.56')
    await userEvent.clear(input)
    await userEvent.type(input, '5')
    await userEvent.tab()
    expect(input).toHaveValue('5.00')
    await expectAccessible()
  })
})
