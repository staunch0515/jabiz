import { EXTENSION_NAMESPACE, formatAmount } from '@jabiz/admin'
import { Typography } from 'antd'
import { useEffect, useRef, type ClipboardEvent, type KeyboardEvent } from 'react'
import { useTranslation } from 'react-i18next'
import {
  COLUMNS,
  emptyLine,
  fillDown,
  insertLine,
  isBlock,
  MAX_LINES,
  normalizeAmount,
  paste,
  removeLine,
  setCell,
  totals,
  type AccountOption,
  type CellProblem,
  type Column,
  type GridLine,
  type LineProblems,
} from './grid'

export interface JournalGridProps {
  lines: GridLine[]
  /** Every edit, as a whole new set of lines (the page keeps the history for undo). */
  onChange: (lines: GridLine[]) => void
  onUndo?: () => void
  onRedo?: () => void
  /** What the browser can see wrong, per line. */
  problems: LineProblems[]
  /** What the server refused, per grid row and cell. */
  serverProblems?: Map<number, Partial<Record<Column, string>>>
  accounts?: AccountOption[]
  departments?: string[]
  locations?: string[]
  readOnly?: boolean
  /** Told how many rows a paste could not take (over the limit of lines). */
  onPasteDropped?: (dropped: number) => void
}

const AMOUNTS: ReadonlySet<Column> = new Set(['debit', 'credit'])
const LISTS: Partial<Record<Column, string>> = {
  accountCode: 'fin-grid-accounts',
  department: 'fin-grid-departments',
  location: 'fin-grid-locations',
}
const WIDTHS: Record<Column, string> = {
  accountCode: '14%',
  debit: '13%',
  credit: '13%',
  memo: '30%',
  department: '10%',
  location: '10%',
}

const cellStyle = (problem: CellProblem | string | undefined, amount: boolean) => {
  const warning = typeof problem === 'object' && problem.warning
  return {
    width: '100%',
    boxSizing: 'border-box' as const,
    padding: '2px 6px',
    font: 'inherit',
    textAlign: amount ? ('right' as const) : ('left' as const),
    border: `1px solid ${problem ? (warning ? '#d48806' : '#cf1322') : '#d9d9d9'}`,
    background: problem ? (warning ? '#fffbe6' : '#fff1f0') : '#fff',
    borderRadius: 2,
  }
}

/**
 * The journal entry grid (FIN-GL-020, FIN-UI-002/003): a spreadsheet-like table of native inputs, one row per line.
 * Keyboard: Tab moves across in a fixed order, Enter and the arrow keys move down and up (Enter past the last line
 * adds one), Ctrl+D fills down, Ctrl+Z / Ctrl+Y undo and redo, Alt+N or Insert adds a line, Ctrl+Delete removes one.
 * Pasting a block from a spreadsheet fills from the cell pasted into. Amounts need no separators.
 */
export default function JournalGrid({
  lines,
  onChange,
  onUndo,
  onRedo,
  problems,
  serverProblems,
  accounts = [],
  departments = [],
  locations = [],
  readOnly = false,
  onPasteDropped,
}: JournalGridProps) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const inputs = useRef(new Map<string, HTMLInputElement>())
  // A cell to focus once it exists: a line added by Enter or Alt+N appears only after the next render.
  const pendingFocus = useRef<{ row: number; column: Column } | null>(null)
  const sum = totals(lines)

  useEffect(() => {
    const target = pendingFocus.current
    if (!target) return
    const element = inputs.current.get(`${target.row}:${target.column}`)
    if (element) {
      element.focus()
      pendingFocus.current = null
    }
  })

  const setFocusTarget = (target: { row: number; column: Column }) => {
    const element = inputs.current.get(`${target.row}:${target.column}`)
    if (element) element.focus()
    else pendingFocus.current = target
  }

  const label = (column: Column, row: number) => t('journal.cell', { column: t(`journal.column.${column}`), line: row + 1 })

  const move = (row: number, column: Column, delta: number) => {
    const target = row + delta
    if (target < 0) return
    if (target >= lines.length) {
      if (readOnly || lines.length >= MAX_LINES) return
      onChange([...lines, emptyLine()])
    }
    setFocusTarget({ row: target, column })
  }

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>, row: number, column: Column) => {
    const ctrl = event.ctrlKey || event.metaKey
    const key = event.key.toLowerCase()
    if (ctrl && key === 'z' && !event.shiftKey) {
      event.preventDefault()
      onUndo?.()
    } else if (ctrl && (key === 'y' || (key === 'z' && event.shiftKey))) {
      event.preventDefault()
      onRedo?.()
    } else if (readOnly) {
      return
    } else if (ctrl && key === 'd') {
      event.preventDefault()
      onChange(fillDown(lines, row, column))
    } else if ((event.altKey && key === 'n') || (key === 'insert' && !ctrl)) {
      event.preventDefault()
      onChange(insertLine(lines, row + 1))
      setFocusTarget({ row: row + 1, column: 'accountCode' })
    } else if (ctrl && key === 'delete') {
      event.preventDefault()
      onChange(removeLine(lines, row))
      setFocusTarget({ row: Math.max(0, Math.min(row, lines.length - 2)), column })
    } else if ((key === 'enter' && !ctrl && !event.altKey) || key === 'arrowdown') {
      event.preventDefault()
      commitAmount(row, column, event.currentTarget.value)
      move(row, column, 1)
    } else if (key === 'arrowup') {
      event.preventDefault()
      commitAmount(row, column, event.currentTarget.value)
      move(row, column, -1)
    }
  }

  /** Amounts take their form in cents when the cell is left ("15000" becomes "15000.00"). */
  const commitAmount = (row: number, column: Column, value: string) => {
    if (!AMOUNTS.has(column)) return
    const normalized = normalizeAmount(value)
    if (normalized !== value) onChange(setCell(lines, row, column, normalized))
  }

  const onPaste = (event: ClipboardEvent<HTMLInputElement>, row: number, column: Column) => {
    if (readOnly) return
    const text = event.clipboardData.getData('text/plain')
    if (!isBlock(text)) return
    event.preventDefault()
    const result = paste(lines, row, column, text)
    onChange(result.lines)
    if (result.dropped > 0) onPasteDropped?.(result.dropped)
  }

  return (
    <div data-testid="journal-grid">
      <datalist id={LISTS.accountCode}>
        {accounts
          .filter((account) => account.active && !account.summary)
          .map((account) => (
            <option key={account.accountCode} value={account.accountCode} label={account.accountName} />
          ))}
      </datalist>
      <datalist id={LISTS.department}>
        {departments.map((code) => (
          <option key={code} value={code} />
        ))}
      </datalist>
      <datalist id={LISTS.location}>
        {locations.map((code) => (
          <option key={code} value={code} />
        ))}
      </datalist>
      <table style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'fixed' }}>
        <caption style={{ textAlign: 'left', paddingBottom: 4 }}>
          <Typography.Text type="secondary">{t('journal.gridHelp')}</Typography.Text>
        </caption>
        <thead>
          <tr>
            <th scope="col" style={{ width: '3%', textAlign: 'right' }}>
              #
            </th>
            {COLUMNS.map((column) => (
              <th key={column} scope="col" style={{ width: WIDTHS[column], textAlign: AMOUNTS.has(column) ? 'right' : 'left', padding: '4px 6px' }}>
                {t(`journal.column.${column}`)}
              </th>
            ))}
            <th scope="col" style={{ width: '17%', textAlign: 'left', padding: '4px 6px' }}>
              {t('journal.column.check')}
            </th>
          </tr>
        </thead>
        <tbody>
          {lines.map((line, row) => {
            const rowProblems = problems[row] ?? {}
            const server = serverProblems?.get(row) ?? {}
            const messages = COLUMNS.flatMap((column) => {
              const local = rowProblems[column]
              const text = server[column] ?? (local ? t(`journal.problem.${local.key}`, local.values) : undefined)
              return text ? [text] : []
            })
            return (
              <tr key={row} data-testid={`grid-row-${row}`}>
                <td style={{ textAlign: 'right', paddingRight: 4, color: '#8c8c8c' }}>{row + 1}</td>
                {COLUMNS.map((column) => {
                  const problem = server[column] ?? rowProblems[column]
                  const message =
                    typeof problem === 'string' ? problem : problem ? t(`journal.problem.${problem.key}`, problem.values) : undefined
                  return (
                    <td key={column} style={{ padding: 1 }}>
                      <input
                        ref={(element) => {
                          const key = `${row}:${column}`
                          if (element) inputs.current.set(key, element)
                          else inputs.current.delete(key)
                        }}
                        value={line[column]}
                        readOnly={readOnly}
                        aria-label={label(column, row)}
                        aria-invalid={problem && !(typeof problem === 'object' && problem.warning) ? true : undefined}
                        title={message}
                        list={LISTS[column]}
                        inputMode={AMOUNTS.has(column) ? 'decimal' : undefined}
                        autoComplete="off"
                        maxLength={column === 'memo' ? 200 : column === 'accountCode' ? 20 : AMOUNTS.has(column) ? 24 : 20}
                        style={cellStyle(problem, AMOUNTS.has(column))}
                        onChange={(event) => onChange(setCell(lines, row, column, event.target.value))}
                        onBlur={(event) => commitAmount(row, column, event.target.value)}
                        onKeyDown={(event) => onKeyDown(event, row, column)}
                        onPaste={(event) => onPaste(event, row, column)}
                      />
                    </td>
                  )
                })}
                <td style={{ padding: '1px 6px', fontSize: 12, color: '#a8071a' }} data-testid={`grid-check-${row}`}>
                  {messages.join(' ')}
                </td>
              </tr>
            )
          })}
        </tbody>
        <tfoot>
          <tr style={{ fontWeight: 600 }}>
            <td />
            <td style={{ padding: '6px' }}>{t('journal.totals')}</td>
            <td style={{ textAlign: 'right', padding: '6px' }} data-testid="total-debit">
              {formatAmount(sum.debit, { scale: 2 })}
            </td>
            <td style={{ textAlign: 'right', padding: '6px' }} data-testid="total-credit">
              {formatAmount(sum.credit, { scale: 2 })}
            </td>
            <td colSpan={4} style={{ padding: '6px' }} role="status" aria-live="polite" data-testid="total-difference">
              {sum.balanced ? (
                <Typography.Text type="success">{t('journal.balanced')}</Typography.Text>
              ) : (
                <Typography.Text type="danger">
                  {t('journal.difference', { amount: formatAmount(sum.difference, { scale: 2, negative: 'parentheses' }) })}
                </Typography.Text>
              )}
            </td>
          </tr>
        </tfoot>
      </table>
    </div>
  )
}
