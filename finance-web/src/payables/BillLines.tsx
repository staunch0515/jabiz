import { EXTENSION_NAMESPACE, formatAmount } from '@jabiz/admin'
import { Typography } from 'antd'
import { useEffect, useId, useRef, type KeyboardEvent } from 'react'
import { useTranslation } from 'react-i18next'
import type { TaxCode } from './api'
import {
  AMOUNT_SCALE,
  cellKey,
  emptyLine,
  LINE_COLUMNS,
  MAX_LINES,
  total,
  type BillLine,
  type LineColumn,
  type LineProblems,
} from './bill'
import { parseNumber } from '../receivables/money'

export interface BillLinesProps {
  lines: BillLine[]
  onChange: (lines: BillLine[]) => void
  /** What the browser can see wrong, per line. */
  problems: LineProblems[]
  /** What the server refused, by {@link cellKey}. */
  serverProblems?: Map<string, string>
  accounts: { accountCode: string; accountName: string }[]
  taxCodes: TaxCode[]
  departments: string[]
  readOnly?: boolean
}

const WIDTHS: Record<LineColumn, string> = {
  description: '34%',
  amount: '12%',
  account: '11%',
  useTaxCode: '11%',
  department: '10%',
  form1099: '8%',
  box1099: '6%',
}
const MAX_LENGTH: Record<LineColumn, number> = {
  description: 500,
  amount: 20,
  account: 20,
  useTaxCode: 20,
  department: 20,
  form1099: 10,
  box1099: 2,
}

const cellStyle = (problem: string | undefined, number: boolean) => ({
  width: '100%',
  boxSizing: 'border-box' as const,
  padding: '2px 6px',
  font: 'inherit',
  textAlign: number ? ('right' as const) : ('left' as const),
  border: `1px solid ${problem ? '#cf1322' : '#d9d9d9'}`,
  background: problem ? '#fff1f0' : '#fff',
  borderRadius: 2,
})

/**
 * The lines of a bill or vendor credit (FIN-AP-004, FIN-UI-002): a table of native inputs in a fixed tab order,
 * accounts, use tax codes and departments suggested as typed. Enter on the last cell of a line goes to the next line,
 * adding one at the end; Alt+N or Insert adds a line after the current one, Ctrl+Delete removes it. Amounts are typed
 * with or without separators; the server computes the use tax.
 */
export default function BillLines({ lines, onChange, problems, serverProblems, accounts, taxCodes, departments,
  readOnly }: BillLinesProps) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const table = useRef<HTMLTableElement>(null)
  const focusNext = useRef<{ row: number; column: LineColumn } | null>(null)

  const focusCell = (row: number, column: LineColumn) =>
    table.current?.querySelector<HTMLInputElement>(`input[data-row="${row}"][data-column="${column}"]`)?.focus()

  useEffect(() => {
    const target = focusNext.current
    if (!target) return
    focusNext.current = null
    focusCell(target.row, target.column)
  })

  const set = (row: number, column: LineColumn, value: string) =>
    onChange(lines.map((line, i) => (i === row ? { ...line, [column]: value } : line)))

  const insertAfter = (row: number) => {
    if (lines.length >= MAX_LINES) return
    focusNext.current = { row: row + 1, column: 'description' }
    onChange([...lines.slice(0, row + 1), emptyLine(), ...lines.slice(row + 1)])
  }

  const remove = (row: number) => {
    const next = lines.filter((_, i) => i !== row)
    focusNext.current = { row: Math.min(row, Math.max(next.length - 1, 0)), column: 'description' }
    onChange(next.length > 0 ? next : [emptyLine()])
  }

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>, row: number, column: LineColumn) => {
    const ctrl = event.ctrlKey || event.metaKey
    if ((event.altKey && event.key.toLowerCase() === 'n') || event.key === 'Insert') {
      event.preventDefault()
      insertAfter(row)
    } else if (ctrl && event.key === 'Delete') {
      event.preventDefault()
      remove(row)
    } else if (event.key === 'Enter' && !ctrl && column === LINE_COLUMNS[LINE_COLUMNS.length - 1]) {
      event.preventDefault()
      if (row === lines.length - 1) insertAfter(row)
      else focusCell(row + 1, 'description')
    }
  }

  const list = (column: LineColumn) =>
    ({ account: `${id}-accounts`, useTaxCode: `${id}-tax`, department: `${id}-departments`,
      form1099: `${id}-forms` } as Partial<Record<LineColumn, string>>)[column]

  return (
    <>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>{t('payables.linesHelp')}</Typography.Paragraph>
      <datalist id={`${id}-accounts`}>
        {accounts.map((a) => <option key={a.accountCode} value={a.accountCode}>{a.accountName}</option>)}
      </datalist>
      <datalist id={`${id}-tax`}>
        {taxCodes.map((c) => <option key={c.taxCode} value={c.taxCode}>{c.description}</option>)}
      </datalist>
      <datalist id={`${id}-departments`}>
        {departments.map((d) => <option key={d} value={d} />)}
      </datalist>
      <datalist id={`${id}-forms`}>
        <option value="NEC">1099-NEC</option>
        <option value="MISC">1099-MISC</option>
      </datalist>
      <table ref={table} style={{ width: '100%', borderCollapse: 'collapse' }} data-testid="bill-lines">
        <thead>
          <tr>
            <th style={{ width: '4%', textAlign: 'right' }}>{t('payables.column.line')}</th>
            {LINE_COLUMNS.map((column) => (
              <th key={column} style={{ width: WIDTHS[column], textAlign: column === 'amount' ? 'right' : 'left' }}>
                {t(`payables.column.${column}`)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {lines.map((line, row) => (
            <tr key={row}>
              <td style={{ textAlign: 'right', paddingRight: 6 }}>{row + 1}</td>
              {LINE_COLUMNS.map((column) => {
                const problemKey = problems[row]?.[column]
                const problem = serverProblems?.get(cellKey(row, column))
                  ?? (problemKey ? t(`payables.problem.${problemKey}`) : undefined)
                const label = t('payables.cell', { column: t(`payables.column.${column}`), line: row + 1 })
                return (
                  <td key={column} style={{ padding: 2 }}>
                    <input
                      aria-label={label}
                      aria-invalid={problem ? true : undefined}
                      title={problem}
                      data-row={row}
                      data-column={column}
                      data-testid={`line-${row}-${column}`}
                      value={line[column]}
                      readOnly={readOnly}
                      inputMode={column === 'amount' || column === 'box1099' ? 'decimal' : undefined}
                      list={list(column)}
                      maxLength={MAX_LENGTH[column]}
                      style={cellStyle(problem, column === 'amount')}
                      onChange={(e) => set(row, column, column === 'description' ? e.target.value : e.target.value.toUpperCase())}
                      onBlur={() => {
                        // Typed without separators, shown with them and the cents: "1500" reads 1,500.00.
                        if (column !== 'amount' || readOnly) return
                        const value = parseNumber(line.amount, AMOUNT_SCALE)
                        if (value) {
                          const shown = formatAmount(line.amount.replace(/[$,\s]/g, ''), { scale: 2 })
                          if (shown !== line.amount) set(row, column, shown)
                        }
                      }}
                      onKeyDown={(e) => onKeyDown(e, row, column)}
                    />
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <td colSpan={2} style={{ textAlign: 'right', fontWeight: 600, paddingTop: 6 }}>{t('payables.linesTotal')}</td>
            <td style={{ textAlign: 'right', fontWeight: 600, paddingTop: 6, paddingRight: 6 }} data-testid="lines-total">
              {formatAmount(total(lines), { scale: 2 })}
            </td>
            <td colSpan={LINE_COLUMNS.length - 2} />
          </tr>
        </tfoot>
      </table>
    </>
  )
}
