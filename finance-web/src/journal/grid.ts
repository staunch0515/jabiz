import { compareDecimal, formatDecimal, parseDecimal, toDecimal, type Decimal, type Violation } from '@jabiz/admin'

/**
 * The journal entry grid as data (FIN-GL-020, FIN-UI-002/003): lines of text as typed, amounts read without
 * separators, spreadsheet paste, fill-down, totals and the checks that can be made while typing. No React here: the
 * page renders it and these functions are tested on their own. The server checks everything again on submission.
 */

export const COLUMNS = ['accountCode', 'debit', 'credit', 'memo', 'department', 'location'] as const
export type Column = (typeof COLUMNS)[number]

/** A line as typed: every cell is text, amounts included. */
export type GridLine = Record<Column, string>

/** The most lines an entry has (the server's limit). */
export const MAX_LINES = 500

/** Cents: the scale of US dollar amounts. */
export const SCALE = 2

export function emptyLine(): GridLine {
  return { accountCode: '', debit: '', credit: '', memo: '', department: '', location: '' }
}

export function isBlank(line: GridLine): boolean {
  return COLUMNS.every((column) => line[column].trim() === '')
}

/** The lines without the blank ones, as the server stores them, with room to type after them. */
export function compact(lines: GridLine[], room = 2): GridLine[] {
  const kept = lines.filter((line) => !isBlank(line))
  return [...kept, ...Array.from({ length: room }, emptyLine)]
}

/** The cell left with its amount in cents ("15000" becomes "15000.00"); the same lines when nothing changes. */
export function withAmountFormatted(lines: GridLine[], row: number, column: Column): GridLine[] {
  if (column !== 'debit' && column !== 'credit') return lines
  const value = lines[row]?.[column]
  if (value === undefined) return lines
  const normalized = normalizeAmount(value)
  return normalized === value ? lines : setCell(lines, row, column, normalized)
}

/** Lines to show: at least {@code count}, so that there is always room to type. */
export function padLines(lines: GridLine[], count = 2): GridLine[] {
  const padded = [...lines]
  while (padded.length < count) padded.push(emptyLine())
  return padded
}

// ---- amounts ---------------------------------------------------------------------------------------------------

export type AmountProblem = 'format' | 'scale'

export interface ParsedAmount {
  /** The value; negative when typed with a minus or in parentheses. Null when blank or not an amount. */
  value: Decimal | null
  problem?: AmountProblem
}

/** "-$1,234.50", "(1,234.50)", "1234.5": thousands separators only in their places, if at all. */
const AMOUNT = /^(?:-?\$?\s*|\(\$?\s*)(?:\d{1,3}(?:,\d{3})+|\d*)(?:\.\d*)?\)?$/

/**
 * Reads an amount as accountants type and spreadsheets copy it: "15000", "15,000.00", "$1,234.5", "-250" or
 * "(250.00)". Separators are optional; a whole number is dollars, not cents.
 */
export function parseAmount(text: string): ParsedAmount {
  const trimmed = text.trim()
  if (trimmed === '') return { value: null }
  if (!AMOUNT.test(trimmed)) return { value: null, problem: 'format' }
  const parenthesized = trimmed.startsWith('(')
  if (parenthesized !== trimmed.endsWith(')')) return { value: null, problem: 'format' }
  const digits = trimmed.replace(/[()$,\s]/g, '')
  const negative = parenthesized || digits.startsWith('-')
  const decimal = parseDecimal(digits.replace(/^-/, ''))
  if (!decimal) return { value: null, problem: 'format' }
  const value = negative ? { unscaled: -decimal.unscaled, scale: decimal.scale } : decimal
  return scaleOf(value) > SCALE ? { value, problem: 'scale' } : { value }
}

/** Digits after the point that matter ("1.50" has one). */
function scaleOf(d: Decimal): number {
  let { unscaled, scale } = d
  while (scale > 0 && unscaled % 10n === 0n) {
    unscaled /= 10n
    scale -= 1
  }
  return Math.max(scale, 0)
}

/** The amount in cents as plain text ("15000.00"); unchanged when it is not a valid amount. */
export function normalizeAmount(text: string): string {
  const { value, problem } = parseAmount(text)
  if (!value || problem) return text.trim()
  return formatDecimal(withScale(value, SCALE))
}

function withScale(d: Decimal, scale: number): Decimal {
  if (d.scale === scale) return d
  if (d.scale < scale) return { unscaled: d.unscaled * 10n ** BigInt(scale - d.scale), scale }
  return { unscaled: d.unscaled / 10n ** BigInt(d.scale - scale), scale }
}

const ZERO: Decimal = { unscaled: 0n, scale: 0 }

function add(a: Decimal, b: Decimal): Decimal {
  const scale = Math.max(a.scale, b.scale)
  return { unscaled: withScale(a, scale).unscaled + withScale(b, scale).unscaled, scale }
}

// ---- totals ----------------------------------------------------------------------------------------------------

export interface Totals {
  debit: string
  credit: string
  /** Debits less credits; "0.00" when the entry balances. */
  difference: string
  balanced: boolean
}

/** Running totals of the amounts that can be read; unreadable cells count as nothing (they are flagged). */
export function totals(lines: GridLine[]): Totals {
  let debit = ZERO
  let credit = ZERO
  for (const line of lines) {
    const d = parseAmount(line.debit)
    const c = parseAmount(line.credit)
    if (d.value && !d.problem) debit = add(debit, d.value)
    if (c.value && !c.problem) credit = add(credit, c.value)
  }
  const difference = add(debit, { unscaled: -credit.unscaled, scale: credit.scale })
  return {
    debit: formatDecimal(withScale(debit, SCALE)),
    credit: formatDecimal(withScale(credit, SCALE)),
    difference: formatDecimal(withScale(difference, SCALE)),
    balanced: compareDecimal(difference, ZERO) === 0,
  }
}

/** The sum of stored amounts (JSON numbers or text) in cents, exactly: never added in floating point. */
export function sumAmounts(values: (number | string | null | undefined)[]): string {
  let sum = ZERO
  for (const value of values) {
    const d = toDecimal(value)
    if (d) sum = add(sum, d)
  }
  return formatDecimal(withScale(sum, SCALE))
}

// ---- checks while typing ---------------------------------------------------------------------------------------

/** An account as `finance.gl.account_lookup` returns it. */
export interface AccountOption {
  accountCode: string
  accountName: string
  active: boolean
  summary: boolean
  controlClass?: string | null
  requiredDimension?: string | null
}

export interface Dimensions {
  departments: ReadonlySet<string>
  locations: ReadonlySet<string>
}

/** A problem of one cell: the key of its message (namespace `journal.problem`) and its values. */
export interface CellProblem {
  key: string
  values?: Record<string, string>
  /** Only a warning: the entry may still be saved and, with the controller's exception, posted. */
  warning?: boolean
}

export type LineProblems = Partial<Record<Column, CellProblem>>

/**
 * What can be seen wrong in a line before the server sees it. A blank line has no problems: it is left out on save.
 * Accounts and dimensions are checked only once they are loaded.
 */
export function checkLine(line: GridLine, accounts?: ReadonlyMap<string, AccountOption>,
  dimensions?: Dimensions): LineProblems {
  if (isBlank(line)) return {}
  const problems: LineProblems = {}
  const debit = parseAmount(line.debit)
  const credit = parseAmount(line.credit)
  for (const [column, amount] of [['debit', debit], ['credit', credit]] as const) {
    if (amount.problem) problems[column] = { key: amount.problem === 'scale' ? 'cents' : 'amount' }
    else if (amount.value && amount.value.unscaled < 0n) problems[column] = { key: 'negative' }
  }
  const hasDebit = debit.value !== null && debit.value.unscaled !== 0n
  const hasCredit = credit.value !== null && credit.value.unscaled !== 0n
  if (!problems.debit && !problems.credit) {
    if (hasDebit && hasCredit) problems.credit = { key: 'oneSide' }
    else if (!hasDebit && !hasCredit) problems.debit = { key: 'noAmount' }
  }
  const code = line.accountCode.trim()
  if (code === '') {
    problems.accountCode = { key: 'accountMissing' }
  } else if (accounts) {
    const account = accounts.get(code)
    if (!account) problems.accountCode = { key: 'accountUnknown', values: { code } }
    else if (account.summary) problems.accountCode = { key: 'accountSummary', values: { code } }
    else if (!account.active) problems.accountCode = { key: 'accountInactive', values: { code } }
    else if (account.controlClass) {
      problems.accountCode = { key: 'accountControl', values: { code }, warning: true }
    }
    for (const dimension of ['department', 'location'] as const) {
      const value = line[dimension].trim()
      if (value === '' && account?.requiredDimension === dimension) {
        problems[dimension] = { key: 'dimensionRequired', values: { code } }
      }
    }
  }
  if (dimensions) {
    for (const [dimension, values] of [['department', dimensions.departments], ['location', dimensions.locations]] as const) {
      const value = line[dimension].trim()
      if (value !== '' && !values.has(value)) problems[dimension] = { key: 'dimensionUnknown', values: { value } }
    }
  }
  return problems
}

/** Whether any line has a problem that is not just a warning. */
export function hasErrors(problems: LineProblems[]): boolean {
  return problems.some((line) => Object.values(line).some((problem) => problem && !problem.warning))
}

// ---- editing ---------------------------------------------------------------------------------------------------

export function setCell(lines: GridLine[], row: number, column: Column, value: string): GridLine[] {
  const next = padLines(lines, row + 1)
  next[row] = { ...next[row], [column]: value }
  return next
}

/** Ctrl+D: the cell takes the value of the cell above it, as in a spreadsheet. */
export function fillDown(lines: GridLine[], row: number, column: Column): GridLine[] {
  if (row <= 0 || row >= lines.length) return lines
  return setCell(lines, row, column, lines[row - 1][column])
}

export function insertLine(lines: GridLine[], at: number): GridLine[] {
  if (lines.length >= MAX_LINES) return lines
  const next = [...lines]
  next.splice(Math.max(0, Math.min(at, lines.length)), 0, emptyLine())
  return next
}

export function removeLine(lines: GridLine[], at: number): GridLine[] {
  const next = lines.filter((_, i) => i !== at)
  return next.length === 0 ? [emptyLine()] : next
}

// ---- spreadsheets ----------------------------------------------------------------------------------------------

/** Whether pasted text is a block of cells (several cells or rows) rather than one value. */
export function isBlock(text: string): boolean {
  return /[\t\n]/.test(text.replace(/\r?\n$/, ''))
}

/** Rows of cells from text copied in a spreadsheet: tab-separated, quoted cells may hold tabs and line breaks. */
export function parseTsv(text: string): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let cell = ''
  let quoted = false
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]
    if (quoted) {
      if (ch === '"' && text[i + 1] === '"') {
        cell += '"'
        i++
      } else if (ch === '"') quoted = false
      else cell += ch
    } else if (ch === '"' && cell === '') quoted = true
    else if (ch === '\t') {
      row.push(cell)
      cell = ''
    } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && text[i + 1] === '\n') i++
      row.push(cell)
      rows.push(row)
      row = []
      cell = ''
    } else cell += ch
  }
  if (cell !== '' || row.length > 0) {
    row.push(cell)
    rows.push(row)
  }
  return rows.filter((r) => r.some((c) => c.trim() !== ''))
}

/** A first row naming the columns ("Account", "Debit" …) without any amount is a header, not a line. */
function isHeader(row: string[]): boolean {
  return row.some((cell) => /^(account|debit|credit|memo|department|location|amount)\b/i.test(cell.trim()))
    && row.every((cell) => !/\d/.test(cell))
}

export interface PasteResult {
  lines: GridLine[]
  /** Lines pasted (the header excluded). */
  pasted: number
  /** Rows that did not fit under the limit of {@link MAX_LINES}. */
  dropped: number
}

/**
 * Pastes a block from a spreadsheet at a cell: its rows go down from the row, its cells across from the column, in
 * the grid's column order. A signed amount lands on the right side: a negative debit is a credit and the other way
 * round, as a one-column "amount" sheet copies it.
 */
export function paste(lines: GridLine[], row: number, column: Column, text: string): PasteResult {
  let rows = parseTsv(text)
  if (rows.length > 0 && isHeader(rows[0])) rows = rows.slice(1)
  const start = COLUMNS.indexOf(column)
  const room = MAX_LINES - row
  const fitting = rows.slice(0, Math.max(0, room))
  let next = padLines(lines, row + fitting.length)
  fitting.forEach((cells, r) => {
    const line = { ...next[row + r] }
    cells.forEach((value, c) => {
      const target = COLUMNS[start + c]
      if (target) line[target] = value.trim()
    })
    next = [...next.slice(0, row + r), sided(line), ...next.slice(row + r + 1)]
  })
  return { lines: next, pasted: fitting.length, dropped: rows.length - fitting.length }
}

/** Amounts take the form typed ones get; a negative amount moves to the other side as a positive one. */
function sided(line: GridLine): GridLine {
  const result = { ...line, debit: normalizeAmount(line.debit), credit: normalizeAmount(line.credit) }
  for (const [from, to] of [['debit', 'credit'], ['credit', 'debit']] as const) {
    const amount = parseAmount(result[from])
    if (amount.value && !amount.problem && amount.value.unscaled < 0n && result[to].trim() === '') {
      result[to] = formatDecimal(withScale({ unscaled: -amount.value.unscaled, scale: amount.value.scale }, SCALE))
      result[from] = ''
    }
  }
  return result
}

/** The lines as tab-separated text with a header, to paste into a spreadsheet. */
export function toTsv(lines: GridLine[], header: string[]): string {
  const quote = (value: string) => (/[\t\n"]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value)
  return [header, ...lines.filter((line) => !isBlank(line)).map((line) => COLUMNS.map((c) => line[c]))]
    .map((cells) => cells.map(quote).join('\t'))
    .join('\n')
}

// ---- to and from the server ------------------------------------------------------------------------------------

/** A line as `FIN_JOURNAL_SAVE` takes it. */
export interface LineInput {
  accountCode: string
  debit: string | null
  credit: string | null
  memo: string | null
  department: string | null
  location: string | null
}

export interface ServerLines {
  inputs: LineInput[]
  /** For each input, the grid row it came from: the server numbers lines without the blank ones. */
  rows: number[]
}

/** The lines to save: blank ones left out, amounts in cents, empty cells as null. */
export function toInputs(lines: GridLine[]): ServerLines {
  const inputs: LineInput[] = []
  const rows: number[] = []
  lines.forEach((line, row) => {
    if (isBlank(line)) return
    const text = (value: string) => (value.trim() === '' ? null : value.trim())
    inputs.push({
      accountCode: line.accountCode.trim(),
      debit: text(normalizeAmount(line.debit)),
      credit: text(normalizeAmount(line.credit)),
      memo: text(line.memo),
      department: text(line.department),
      location: text(line.location),
    })
    rows.push(row)
  })
  return { inputs, rows }
}

/** A line as the server stores it (amounts as JSON numbers or text). */
export interface StoredLine {
  lineNo: number | string
  accountCode: string
  debit?: number | string | null
  credit?: number | string | null
  memo?: string | null
  department?: string | null
  location?: string | null
}

export function fromStored(stored: StoredLine[]): GridLine[] {
  const amount = (value: number | string | null | undefined) =>
    value === null || value === undefined ? '' : normalizeAmount(String(value))
  return [...stored]
    .sort((a, b) => Number(a.lineNo) - Number(b.lineNo))
    .map((line) => ({
      accountCode: line.accountCode ?? '',
      debit: amount(line.debit),
      credit: amount(line.credit),
      memo: line.memo ?? '',
      department: line.department ?? '',
      location: line.location ?? '',
    }))
}

const LINE_FIELD = /^lines\[(\d+)]\.(\w+)$/

export interface ServerProblems {
  /** Problems of cells, by grid row. */
  cells: Map<number, Partial<Record<Column, string>>>
  /** Problems of the entry as a whole (balance, period, number of lines …). */
  general: string[]
}

/** Places the server's violations ("lines[3].accountCode") on the grid's cells; the others concern the entry. */
export function placeViolations(violations: Violation[], rows: number[]): ServerProblems {
  const cells = new Map<number, Partial<Record<Column, string>>>()
  const general: string[] = []
  for (const violation of violations) {
    const match = LINE_FIELD.exec(violation.field ?? '')
    const column = match?.[2] as Column | undefined
    const row = match ? rows[Number(match[1])] : undefined
    if (match && row !== undefined && column && (COLUMNS as readonly string[]).includes(column)) {
      cells.set(row, { ...cells.get(row), [column]: violation.message })
    } else {
      general.push(violation.message)
    }
  }
  return { cells, general }
}

// ---- undo ------------------------------------------------------------------------------------------------------

/** The grid's edits with their history, for undo (Ctrl+Z) and redo (Ctrl+Y). */
export interface History {
  past: GridLine[][]
  present: GridLine[]
  future: GridLine[][]
}

const HISTORY_LIMIT = 200

export function startHistory(lines: GridLine[]): History {
  return { past: [], present: lines, future: [] }
}

export function record(history: History, next: GridLine[]): History {
  if (next === history.present) return history
  return { past: [...history.past, history.present].slice(-HISTORY_LIMIT), present: next, future: [] }
}

export function undo(history: History): History {
  const previous = history.past.at(-1)
  if (!previous) return history
  return { past: history.past.slice(0, -1), present: previous, future: [history.present, ...history.future] }
}

export function redo(history: History): History {
  const [next, ...rest] = history.future
  if (!next) return history
  return { past: [...history.past, history.present], present: next, future: rest }
}
