import { describe, expect, it } from 'vitest'
import {
  cellKey,
  checkLine,
  emptyLine,
  fromStored,
  lineAmount,
  placeViolations,
  subtotal,
  toInputs,
  type InvoiceLine,
} from './invoice'

const line = (patch: Partial<InvoiceLine>): InvoiceLine => ({ ...emptyLine(), ...patch })

describe('invoice lines', () => {
  it('computes INV-1004 exactly: 100 x 400.00 and 1 x 10,000.00', () => {
    const lines = [
      line({ description: 'Components', quantity: '100', unitPrice: '400.00', revenueAccount: '4000' }),
      line({ description: 'Engineering services', quantity: '1', unitPrice: '10,000', taxCode: 'NT' }),
      emptyLine(),
    ]
    expect(lines.map(lineAmount)).toEqual(['40000.00', '10000.00', null])
    expect(subtotal(lines)).toBe('50000.00')
  })

  it('rounds a line half away from zero, as the server does, and never in floating point', () => {
    expect(lineAmount(line({ quantity: '3', unitPrice: '0.3333' }))).toBe('1.00')
    expect(lineAmount(line({ quantity: '1', unitPrice: '0.005' }))).toBe('0.01')
    expect(lineAmount(line({ quantity: '0.1', unitPrice: '0.2' }))).toBe('0.02')
    expect(subtotal([line({ quantity: '1', unitPrice: '0.1' }), line({ quantity: '1', unitPrice: '0.2' })])).toBe('0.30')
  })

  it('tells what is wrong while typing; blank lines are left alone', () => {
    const accounts = new Map([['4000', { active: true, summary: false }], ['4', { active: true, summary: true }]])
    const codes = new Set(['TX-AUSTIN', 'NT'])
    expect(checkLine(emptyLine(), accounts, codes)).toEqual({})
    expect(checkLine(line({ quantity: '1' }), accounts, codes)).toEqual({
      description: 'descriptionMissing', unitPrice: 'priceMissing' })
    expect(checkLine(line({ description: 'x', quantity: '0', unitPrice: '1.23456' }), accounts, codes)).toEqual({
      quantity: 'quantityPositive', unitPrice: 'priceFormat' })
    expect(checkLine(line({ description: 'x', quantity: 'ten', unitPrice: '-1' }), accounts, codes)).toEqual({
      quantity: 'quantityFormat', unitPrice: 'priceFormat' })
    expect(checkLine(line({ description: 'x', quantity: '1', unitPrice: '1', revenueAccount: '9999', taxCode: 'XX' }),
      accounts, codes)).toEqual({ revenueAccount: 'accountUnknown', taxCode: 'taxCodeUnknown' })
    expect(checkLine(line({ description: 'x', quantity: '1', unitPrice: '1', revenueAccount: '4' }), accounts, codes))
      .toEqual({ revenueAccount: 'accountNotPostable' })
  })

  it('sends the lines without blanks and separators, and places the refusals on the cells typed', () => {
    const lines = [
      emptyLine(),
      line({ description: ' Components ', quantity: '1,000', unitPrice: '$4.50', revenueAccount: '4000' }),
      line({ description: 'Service', quantity: '2', unitPrice: '10' }),
    ]
    const { inputs, rows } = toInputs(lines)
    expect(rows).toEqual([1, 2])
    expect(inputs).toEqual([
      { description: 'Components', quantity: '1000', unitPrice: '4.50', revenueAccount: '4000', taxCode: null },
      { description: 'Service', quantity: '2', unitPrice: '10', revenueAccount: null, taxCode: null },
    ])
    const placed = placeViolations([
      { field: 'lines[1].unitPrice', ruleCode: 'X', message: 'Too low' },
      { field: 'customerCode', ruleCode: 'Y', message: 'No such customer' },
    ], rows)
    expect(placed.cells.get(cellKey(2, 'unitPrice'))).toBe('Too low')
    expect(placed.general).toEqual(['No such customer'])
  })

  it('shows stored lines in order, quantities without trailing zeros and prices with cents', () => {
    expect(fromStored([
      { lineNo: 2, description: 'b', quantity: '1.0000', unitPrice: '10000.0000', revenueAccount: '4100', taxCode: 'NT' },
      { lineNo: 1, description: 'a', quantity: 2.5, unitPrice: '0.3333', revenueAccount: '4000' },
    ])).toEqual([
      { description: 'a', quantity: '2.5', unitPrice: '0.3333', revenueAccount: '4000', taxCode: '' },
      { description: 'b', quantity: '1', unitPrice: '10000.00', revenueAccount: '4100', taxCode: 'NT' },
    ])
  })
})
