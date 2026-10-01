import { describe, expect, it } from 'vitest'
import {
  checkLine,
  emptyLine,
  fillDown,
  fromStored,
  hasErrors,
  insertLine,
  isBlock,
  MAX_LINES,
  normalizeAmount,
  parseAmount,
  parseTsv,
  paste,
  placeViolations,
  record,
  redo,
  removeLine,
  startHistory,
  toInputs,
  totals,
  toTsv,
  undo,
  type AccountOption,
  type GridLine,
} from './grid'

const line = (accountCode: string, debit = '', credit = '', memo = ''): GridLine => ({
  ...emptyLine(),
  accountCode,
  debit,
  credit,
  memo,
})

const ACCOUNTS = new Map<string, AccountOption>(
  [
    { accountCode: '1010', accountName: 'Cash', active: true, summary: false, controlClass: 'BANK' },
    { accountCode: '2100', accountName: 'Accrued liabilities', active: true, summary: false },
    { accountCode: '6000', accountName: 'Operating expenses', active: true, summary: true },
    { accountCode: '6400', accountName: 'Professional fees', active: true, summary: false },
    { accountCode: '6450', accountName: 'Old fees', active: false, summary: false },
    { accountCode: '6500', accountName: 'Software', active: true, summary: false, requiredDimension: 'department' },
  ].map((a) => [a.accountCode, a]),
)

describe('amounts', () => {
  it('are read as typed and as spreadsheets copy them, without separators being needed', () => {
    expect(normalizeAmount('15000')).toBe('15000.00')
    expect(normalizeAmount('15,000.5')).toBe('15000.50')
    expect(normalizeAmount('$1,234.56')).toBe('1234.56')
    expect(normalizeAmount('.5')).toBe('0.50')
    expect(normalizeAmount('(250.00)')).toBe('-250.00')
    expect(normalizeAmount('-250')).toBe('-250.00')
    expect(normalizeAmount('1.500')).toBe('1.50')
  })

  it('refuse what is not an amount in cents', () => {
    expect(parseAmount('').value).toBeNull()
    expect(parseAmount('abc').problem).toBe('format')
    expect(parseAmount('(12').problem).toBe('format')
    expect(parseAmount('1.234').problem).toBe('scale')
    expect(normalizeAmount('1.234')).toBe('1.234')
  })
})

describe('totals', () => {
  it('show the difference until the entry balances, exactly', () => {
    const lines = [line('6400', '0.10'), line('6400', '0.20'), line('2100', '', '0.3')]
    expect(totals(lines)).toEqual({ debit: '0.30', credit: '0.30', difference: '0.00', balanced: true })
    expect(totals([line('6400', '25000'), line('2100', '', '24999.99')])).toMatchObject({
      difference: '0.01',
      balanced: false,
    })
    expect(totals([line('6400', 'x'), line('2100', '', '5')])).toMatchObject({ debit: '0.00', difference: '-5.00' })
  })
})

describe('checks while typing', () => {
  it('flag accounts the ledger would refuse and warn of control accounts', () => {
    expect(checkLine(line('9999', '1'), ACCOUNTS).accountCode?.key).toBe('accountUnknown')
    expect(checkLine(line('6000', '1'), ACCOUNTS).accountCode?.key).toBe('accountSummary')
    expect(checkLine(line('6450', '1'), ACCOUNTS).accountCode?.key).toBe('accountInactive')
    expect(checkLine(line('1010', '', '1'), ACCOUNTS).accountCode).toMatchObject({ key: 'accountControl', warning: true })
    expect(checkLine(line('6500', '1'), ACCOUNTS).department?.key).toBe('dimensionRequired')
    expect(checkLine(line('6400', '1'), ACCOUNTS)).toEqual({})
    // Before the accounts are loaded, only the form is checked.
    expect(checkLine(line('9999', '1'))).toEqual({})
  })

  it('want one amount per line, in cents, and no problems on blank lines', () => {
    expect(checkLine(line('6400'), ACCOUNTS).debit?.key).toBe('noAmount')
    expect(checkLine(line('6400', '1', '1'), ACCOUNTS).credit?.key).toBe('oneSide')
    expect(checkLine(line('6400', '1.001'), ACCOUNTS).debit?.key).toBe('cents')
    expect(checkLine(line('', '1'), ACCOUNTS).accountCode?.key).toBe('accountMissing')
    expect(checkLine(emptyLine(), ACCOUNTS)).toEqual({})
  })

  it('know dimension values only when they are listed', () => {
    const dimensions = { departments: new Set(['ADMIN']), locations: new Set<string>() }
    const withDepartment = { ...line('6400', '1'), department: 'SALES', location: 'TX' }
    const problems = checkLine(withDepartment, ACCOUNTS, dimensions)
    expect(problems.department?.key).toBe('dimensionUnknown')
    expect(problems.location?.key).toBe('dimensionUnknown')
    expect(hasErrors([problems])).toBe(true)
    expect(hasErrors([checkLine(line('1010', '1'), ACCOUNTS)])).toBe(false)
  })
})

describe('spreadsheets', () => {
  it('a 50-line block pastes across and down, skipping the header and landing signed amounts on their side', () => {
    const rows = ['Account\tDebit\tCredit\tMemo']
    for (let i = 0; i < 49; i++) rows.push(`6400\t${i + 1}.00\t\tline ${i + 1}`)
    rows.push('2100\t-1225.00\t\tbalancing')
    const result = paste([emptyLine()], 0, 'accountCode', rows.join('\r\n') + '\r\n')
    expect(result.pasted).toBe(50)
    expect(result.lines).toHaveLength(50)
    expect(result.lines[0]).toMatchObject({ accountCode: '6400', debit: '1.00', memo: 'line 1' })
    expect(result.lines[49]).toMatchObject({ accountCode: '2100', debit: '', credit: '1225.00' })
    expect(totals(result.lines).balanced).toBe(true)
  })

  it('a block pasted in the middle fills from that cell; beyond the limit, rows are dropped and counted', () => {
    const start = [line('6400', '1'), line('2100')]
    const result = paste(start, 1, 'credit', '1\tfirst\n2\tsecond')
    expect(result.lines[1]).toMatchObject({ accountCode: '2100', credit: '1', memo: 'first' })
    expect(result.lines[2]).toMatchObject({ accountCode: '', credit: '2', memo: 'second' })
    const big = Array.from({ length: 10 }, () => '6400\t1').join('\n')
    const full = paste([], MAX_LINES - 3, 'accountCode', big)
    expect(full.pasted).toBe(3)
    expect(full.dropped).toBe(7)
    expect(full.lines).toHaveLength(MAX_LINES)
  })

  it('quoted cells keep tabs, line breaks and quotes; a single value is not a block', () => {
    expect(parseTsv('6400\t"a\tb\nc ""d"""\n2100\tx\n')).toEqual([
      ['6400', 'a\tb\nc "d"'],
      ['2100', 'x'],
    ])
    expect(isBlock('15000\n')).toBe(false)
    expect(isBlock('6400\t15000')).toBe(true)
  })

  it('copies the lines back with a header, quoting where needed', () => {
    const text = toTsv([line('6400', '1.00', '', 'say "hi"'), emptyLine()], ['Account', 'Debit', 'Credit', 'Memo',
      'Department', 'Location'])
    expect(text).toBe('Account\tDebit\tCredit\tMemo\tDepartment\tLocation\n6400\t1.00\t\t"say ""hi"""\t\t')
  })
})

describe('editing', () => {
  it('fills down from the line above, inserts and removes lines', () => {
    const lines = [line('6400', '1', '', 'audit'), line('2100')]
    expect(fillDown(lines, 1, 'memo')[1].memo).toBe('audit')
    expect(fillDown(lines, 0, 'memo')).toBe(lines)
    expect(insertLine(lines, 1)).toHaveLength(3)
    expect(insertLine(lines, 1)[1]).toEqual(emptyLine())
    expect(removeLine([line('6400')], 0)).toEqual([emptyLine()])
  })

  it('undoes and redoes edits', () => {
    let history = startHistory([emptyLine()])
    history = record(history, [line('6400')])
    history = record(history, [line('6400', '5')])
    history = undo(history)
    expect(history.present[0].debit).toBe('')
    history = redo(history)
    expect(history.present[0].debit).toBe('5')
    history = undo(undo(undo(history)))
    expect(history.present).toEqual([emptyLine()])
    expect(record(history, history.present)).toBe(history)
  })
})

describe('the server', () => {
  it('gets the lines without blanks, amounts in cents and empty cells as null', () => {
    const { inputs, rows } = toInputs([line('6400', '15000', '', ' audit '), emptyLine(), line('2100', '', '15,000')])
    expect(inputs).toEqual([
      { accountCode: '6400', debit: '15000.00', credit: null, memo: 'audit', department: null, location: null },
      { accountCode: '2100', debit: null, credit: '15000.00', memo: null, department: null, location: null },
    ])
    expect(rows).toEqual([0, 2])
  })

  it('violations land on the cells they name, counting from the lines sent', () => {
    const placed = placeViolations(
      [
        { field: 'lines[1].accountCode', ruleCode: 'FIN_JOURNAL_ACCOUNT_UNKNOWN', message: 'There is no account 9' },
        { field: 'lines', ruleCode: 'FIN_JOURNAL_UNBALANCED', message: 'Debits 1 and credits 2 differ by 1' },
        { field: 'postingDate', ruleCode: 'FIN_PERIOD_CLOSED', message: 'Period closed' },
      ],
      [0, 2],
    )
    expect(placed.cells.get(2)).toEqual({ accountCode: 'There is no account 9' })
    expect(placed.general).toEqual(['Debits 1 and credits 2 differ by 1', 'Period closed'])
  })

  it('stored lines come back in their order, amounts in cents', () => {
    expect(
      fromStored([
        { lineNo: 2, accountCode: '2100', credit: 15000 },
        { lineNo: 1, accountCode: '6400', debit: '15000.5', memo: 'audit' },
      ]),
    ).toEqual([line('6400', '15000.50', '', 'audit'), line('2100', '', '15000.00')])
  })
})

describe('stored amounts', () => {
  it('add up exactly in cents', async () => {
    const { sumAmounts } = await import('./grid')
    expect(sumAmounts([0.1, '0.2', null, 15000, '1362.9'])).toBe('16363.20')
    expect(sumAmounts([])).toBe('0.00')
  })
})
