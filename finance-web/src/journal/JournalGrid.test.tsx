import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { beforeAll, describe, expect, it } from 'vitest'
import { checkLine, padLines, record, redo, startHistory, undo, type AccountOption, type GridLine } from './grid'
import JournalGrid from './JournalGrid'
import { setUpTexts } from './testing'

const ACCOUNTS: AccountOption[] = [
  { accountCode: '1010', accountName: 'Cash', active: true, summary: false, controlClass: 'BANK' },
  { accountCode: '2100', accountName: 'Accrued liabilities', active: true, summary: false },
  { accountCode: '6400', accountName: 'Professional fees', active: true, summary: false },
]
const BY_CODE = new Map(ACCOUNTS.map((a) => [a.accountCode, a]))

/** The grid with its history, as the entry page holds it. */
function Harness({ initial = padLines([], 2) }: { initial?: GridLine[] }) {
  const [history, setHistory] = useState(() => startHistory(initial))
  return (
    <JournalGrid
      lines={history.present}
      onChange={(lines) => setHistory((h) => record(h, lines))}
      onUndo={() => setHistory(undo)}
      onRedo={() => setHistory(redo)}
      problems={history.present.map((line) => checkLine(line, BY_CODE))}
      accounts={ACCOUNTS}
    />
  )
}

const cell = (column: string, line: number) => screen.getByLabelText(`${column}, line ${line}`) as HTMLInputElement

beforeAll(setUpTexts)

describe('JournalGrid', () => {
  it('takes a 50-line journal pasted from a spreadsheet, flags the bad cells and shows the difference until it balances', async () => {
    const rows = ['Account\tDebit\tCredit\tMemo']
    for (let i = 1; i <= 48; i++) rows.push(`6400\t${i}.00\t\tfee ${i}`)
    rows.push('9999\t10.00\t\tunknown account')
    rows.push('2100\t\t1176.00\tbalancing')
    render(<Harness />)

    fireEvent.paste(cell('Account', 1), { clipboardData: { getData: () => rows.join('\r\n') } })

    expect(screen.getAllByRole('row').filter((r) => r.dataset.testid?.startsWith('grid-row-'))).toHaveLength(50)
    expect(cell('Memo', 50).value).toBe('balancing')
    expect(cell('Account', 49)).toHaveAttribute('aria-invalid', 'true')
    expect(within(screen.getByTestId('grid-check-48')).getByText('There is no account 9999.')).toBeInTheDocument()
    expect(screen.getByTestId('total-debit')).toHaveTextContent('1,186.00')
    expect(screen.getByTestId('total-difference')).toHaveTextContent('Out of balance by 10.00')

    await userEvent.clear(cell('Account', 49))
    await userEvent.type(cell('Account', 49), '6400')
    await userEvent.clear(cell('Credit', 50))
    await userEvent.type(cell('Credit', 50), '1186')
    expect(cell('Account', 49)).not.toHaveAttribute('aria-invalid')
    expect(screen.getByTestId('total-difference')).toHaveTextContent('Balanced')
  })

  it('works by keyboard: Enter adds and moves to a line, amounts take cents, Ctrl+D fills down, Ctrl+Z undoes', async () => {
    render(<Harness />)
    await userEvent.click(cell('Account', 1))
    await userEvent.keyboard('6400{Tab}25000{Tab}{Tab}Audit fee{Enter}')
    expect(cell('Debit', 1).value).toBe('25000.00')
    expect(cell('Memo', 2)).toHaveFocus()

    await userEvent.keyboard('{Control>}d{/Control}')
    expect(cell('Memo', 2).value).toBe('Audit fee')
    await userEvent.keyboard('{Control>}z{/Control}')
    expect(cell('Memo', 2).value).toBe('')
    await userEvent.keyboard('{Control>}y{/Control}')
    expect(cell('Memo', 2).value).toBe('Audit fee')

    // Enter past the last line adds one.
    await userEvent.keyboard('{Enter}')
    expect(cell('Memo', 3)).toHaveFocus()
    // Alt+N inserts a line below; Ctrl+Delete removes the current one.
    await userEvent.keyboard('{Alt>}n{/Alt}')
    expect(cell('Account', 4)).toHaveFocus()
    await userEvent.keyboard('{Control>}{Delete}{/Control}')
    expect(screen.queryByLabelText('Account, line 4')).not.toBeInTheDocument()
  })

  it('warns of a control account without refusing it, and offers only accounts that take postings', () => {
    render(<Harness initial={[{ accountCode: '1010', debit: '', credit: '5.00', memo: '', department: '', location: '' }]} />)
    expect(cell('Account', 1)).not.toHaveAttribute('aria-invalid')
    expect(cell('Account', 1)).toHaveAttribute('title', expect.stringContaining('control account'))
    const options = [...document.querySelectorAll('#fin-grid-accounts option')].map((o) => o.getAttribute('value'))
    expect(options).toEqual(['1010', '2100', '6400'])
  })

  it('a single value pastes into the cell as usual; read-only lines take no edits', () => {
    const { rerender } = render(<Harness />)
    const event = fireEvent.paste(cell('Debit', 1), { clipboardData: { getData: () => '15000' } })
    expect(event).toBe(true)
    rerender(
      <JournalGrid lines={padLines([], 1)} onChange={() => { throw new Error('read-only') }} problems={[]} readOnly />,
    )
    expect(cell('Account', 1)).toHaveAttribute('readonly')
  })
})
