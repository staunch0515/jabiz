import { describe, expect, it } from 'vitest'
import {
  checkLine,
  emptyLine,
  fromStored,
  hasProblems,
  padLines,
  placeViolations,
  POSSIBLE_DUPLICATE,
  toInputs,
  total,
  type BillLine,
} from './bill'

const line = (patch: Partial<BillLine>): BillLine => ({ ...emptyLine(), ...patch })
const accounts = new Map([
  ['6400', { active: true, summary: false }],
  ['6000', { active: true, summary: true }],
])

describe('bill lines', () => {
  it('total the amounts that can be read, typed with or without separators', () => {
    expect(total([line({ amount: '1500' }), line({ amount: '1,250.5' }), line({ amount: 'x' }), emptyLine()]))
      .toBe('2750.50')
    expect(total([])).toBe('0.00')
  })

  it('tell what is wrong while typing, and nothing of a blank line', () => {
    expect(checkLine(emptyLine())).toEqual({})
    expect(checkLine(line({ amount: '10' }))).toEqual({ description: 'descriptionMissing' })
    expect(checkLine(line({ description: 'Fees' }))).toEqual({ amount: 'amountMissing' })
    expect(checkLine(line({ description: 'Fees', amount: '1.234' }))).toEqual({ amount: 'amountFormat' })
    expect(checkLine(line({ description: 'Fees', amount: '0' }))).toEqual({ amount: 'amountPositive' })
    expect(checkLine(line({ description: 'Fees', amount: '5', account: '9999' }), { accounts }))
      .toEqual({ account: 'accountUnknown' })
    expect(checkLine(line({ description: 'Fees', amount: '5', account: '6000' }), { accounts }))
      .toEqual({ account: 'accountNotPostable' })
    expect(checkLine(line({ description: 'Fees', amount: '5', useTaxCode: 'X' }), { taxCodes: new Set(['TX-AUSTIN']) }))
      .toEqual({ useTaxCode: 'taxCodeUnknown' })
    expect(checkLine(line({ description: 'Fees', amount: '5', department: 'X' }), { departments: new Set(['ADMIN']) }))
      .toEqual({ department: 'departmentUnknown' })
  })

  it('check the 1099 form and box together', () => {
    const fees = { description: 'Fees', amount: '5' }
    expect(checkLine(line({ ...fees, form1099: 'NEC', box1099: '1' }))).toEqual({})
    expect(checkLine(line({ ...fees, form1099: 'MISC', box1099: '10' }))).toEqual({})
    expect(checkLine(line({ ...fees, form1099: 'K' }))).toEqual({ form1099: 'formUnknown' })
    expect(checkLine(line({ ...fees, form1099: 'NEC', box1099: '2' }))).toEqual({ box1099: 'boxUnknown' })
    expect(checkLine(line({ ...fees, box1099: '1' }))).toEqual({ form1099: 'formMissing' })
    expect(hasProblems([{}, { amount: 'amountMissing' }])).toBe(true)
    expect(hasProblems([{}, {}])).toBe(false)
  })

  it('send the lines without blanks, amounts without separators, and remember their rows', () => {
    const { inputs, rows } = toInputs([
      line({ description: ' Fees ', amount: '1,500', account: '6400', form1099: 'NEC', box1099: '1' }),
      emptyLine(),
      line({ description: 'Travel', amount: '$75.5', useTaxCode: 'TX-AUSTIN', department: 'ADMIN' }),
    ])
    expect(rows).toEqual([0, 2])
    expect(inputs).toEqual([
      { description: 'Fees', amount: '1500', account: '6400', useTaxCode: null, department: null, form1099: 'NEC',
        box1099: '1', location: null },
      { description: 'Travel', amount: '75.5', account: null, useTaxCode: 'TX-AUSTIN', department: 'ADMIN',
        form1099: null, box1099: null, location: null },
    ])
    expect(toInputs([line({ description: 'Bad', amount: 'abc' })]).inputs[0].amount).toBe('abc')
  })

  it('show stored lines in order with their cents, keeping a location the page does not show', () => {
    const shown = fromStored([
      { lineNo: 2, description: 'B', amount: 10, account: '6500', location: 'AUSTIN' },
      { lineNo: 1, description: 'A', amount: '1500.5', account: '6400', form1099: 'NEC', box1099: '1' },
    ])
    expect(shown).toEqual([
      { ...line({ description: 'A', amount: '1500.50', account: '6400', form1099: 'NEC', box1099: '1' }), location: null },
      { ...line({ description: 'B', amount: '10.00', account: '6500' }), location: 'AUSTIN' },
    ])
    expect(toInputs(shown).inputs[1].location).toBe('AUSTIN')
    expect(padLines([], 2)).toHaveLength(2)
  })

  it('place the server refusals on their cells and tell a possible duplicate', () => {
    const placed = placeViolations([
      { field: 'lines[1].account', ruleCode: 'FIN_BILL_ACCOUNT', message: 'Not an expense account' },
      { field: 'vendorInvoiceNo', ruleCode: POSSIBLE_DUPLICATE, message: 'Like BILL-3' },
      { field: 'lines[9].amount', ruleCode: 'X', message: 'Out of range' },
    ], [0, 3])
    expect(placed.cells.get('3:account')).toBe('Not an expense account')
    expect(placed.general).toEqual(['Like BILL-3', 'Out of range'])
    expect(placed.possibleDuplicate).toBe(true)
  })
})
