import { EXTENSION_NAMESPACE, formatAmount } from '@jabiz/admin'
import { Typography } from 'antd'
import { useEffect, useId, useRef, type KeyboardEvent } from 'react'
import { useTranslation } from 'react-i18next'
import type { TaxCode } from './api'
import {
  cellKey,
  emptyLine,
  LINE_COLUMNS,
  lineAmount,
  MAX_LINES,
  subtotal,
  type InvoiceLine,
  type LineColumn,
  type LineProblems,
} from './invoice'

export interface InvoiceLinesProps {
  lines: InvoiceLine[]
  onChange: (lines: InvoiceLine[]) => void
  /** What the browser can see wrong, per line. */
  problems: LineProblems[]
  /** What the server refused, by {@link cellKey}. */
  serverProblems?: Map<string, string>
  accounts: { accountCode: string; accountName: string }[]
  taxCodes: TaxCode[]
  readOnly?: boolean
}

const NUMBERS: ReadonlySet<LineColumn> = new Set(['quantity', 'unitPrice'])
const WIDTHS: Record<LineColumn, string> = {
  description: '38%',
  quantity: '11%',
  unitPrice: '13%',
  revenueAccount: '13%',
  taxCode: '11%',
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
 * The lines of an invoice or credit memo (FIN-AR-003, FIN-UI-002): a table of native inputs in a fixed tab order,
 * accounts and tax codes suggested as typed. Enter on the last cell of a line goes to the next line, adding one at
 * the end; Alt+N or Insert adds a line after the current one, Ctrl+Delete removes it. Each line shows its amount;
 * the server computes the amounts, tax and totals again.
 */
export default function InvoiceLines({ lines, onChange, problems, serverProblems, accounts, taxCodes,
  readOnly }: InvoiceLinesProps) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const table = useRef<HTMLTableElement>(null)
  const focusNext = useRef<{ row: number; column: LineColumn } | null>(null)

  const focusCell = (row: number, column: LineColumn) =>
    table.current?.querySelector<HTMLInputElement>(`input[data-row="${row}"][data-column="${column}"]`)?.focus()

  // A line added or removed is focused once it is rendered.
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

  return (
    <>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>{t('receivables.linesHelp')}</Typography.Paragraph>
      <datalist id={`${id}-accounts`}>
        {accounts.map((a) => <option key={a.accountCode} value={a.accountCode}>{a.accountName}</option>)}
      </datalist>
      <datalist id={`${id}-tax`}>
        {taxCodes.map((c) => <option key={c.taxCode} value={c.taxCode}>{c.description}</option>)}
      </datalist>
      <table ref={table} style={{ width: '100%', borderCollapse: 'collapse' }} data-testid="invoice-lines">
        <thead>
          <tr>
            <th style={{ width: '4%', textAlign: 'right' }}>{t('receivables.column.line')}</th>
            {LINE_COLUMNS.map((column) => (
              <th key={column} style={{ width: WIDTHS[column], textAlign: NUMBERS.has(column) ? 'right' : 'left' }}>
                {t(`receivables.column.${column}`)}
              </th>
            ))}
            <th style={{ width: '10%', textAlign: 'right' }}>{t('receivables.column.amount')}</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((line, row) => (
            <tr key={row}>
              <td style={{ textAlign: 'right', paddingRight: 6 }}>{row + 1}</td>
              {LINE_COLUMNS.map((column) => {
                const problemKey = problems[row]?.[column]
                const problem = serverProblems?.get(cellKey(row, column))
                  ?? (problemKey ? t(`receivables.problem.${problemKey}`) : undefined)
                const label = t('receivables.cell', { column: t(`receivables.column.${column}`), line: row + 1 })
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
                      inputMode={NUMBERS.has(column) ? 'decimal' : undefined}
                      list={column === 'revenueAccount' ? `${id}-accounts` : column === 'taxCode' ? `${id}-tax` : undefined}
                      maxLength={column === 'description' ? 500 : 30}
                      style={cellStyle(problem, NUMBERS.has(column))}
                      onChange={(e) => set(row, column, column === 'description' ? e.target.value : e.target.value.toUpperCase())}
                      onKeyDown={(e) => onKeyDown(e, row, column)}
                    />
                  </td>
                )
              })}
              <td style={{ textAlign: 'right', paddingRight: 6 }} data-testid={`line-${row}-amount`}>
                {lineAmount(line) !== null ? formatAmount(lineAmount(line), { scale: 2 }) : ''}
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <td colSpan={LINE_COLUMNS.length + 1} style={{ textAlign: 'right', fontWeight: 600, paddingTop: 6 }}>
              {t('receivables.subtotal')}
            </td>
            <td style={{ textAlign: 'right', fontWeight: 600, paddingTop: 6, paddingRight: 6 }} data-testid="lines-subtotal">
              {formatAmount(subtotal(lines), { scale: 2 })}
            </td>
          </tr>
        </tfoot>
      </table>
    </>
  )
}
