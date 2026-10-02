import { formatDecimal, type Violation } from '@jabiz/admin'
import { add, cents, parseNumber, rescale, sign, stored, ZERO } from '../receivables/money'
import type { LineInput, StoredBillLine } from './api'

/**
 * The lines of a bill or vendor credit as data (FIN-AP-004, FIN-UI-002): text as typed, the total for showing while
 * typing, and the checks that can be made then. The server checks again and computes the use tax when posting; what
 * it says is what counts.
 */

export const LINE_COLUMNS = ['description', 'amount', 'account', 'useTaxCode', 'department', 'form1099',
  'box1099'] as const
export type LineColumn = (typeof LINE_COLUMNS)[number]
/** A line as typed; a location set elsewhere (the API, an import) is not shown here but kept when the draft is saved. */
export type BillLine = Record<LineColumn, string> & { location?: string | null }

export const AMOUNT_SCALE = 2
export const MAX_LINES = 500

/** The boxes a line may be reported in (ApEntities.BOXES, FIN-AP-020). */
export const BOXES: Record<string, string[]> = { NEC: ['1'], MISC: ['1', '2', '3', '6', '10'] }

export function emptyLine(): BillLine {
  return { description: '', amount: '', account: '', useTaxCode: '', department: '', form1099: '', box1099: '' }
}

export function isBlank(line: BillLine): boolean {
  return LINE_COLUMNS.every((column) => line[column].trim() === '')
}

export function padLines(lines: BillLine[], count = 1): BillLine[] {
  const padded = [...lines]
  while (padded.length < count) padded.push(emptyLine())
  return padded
}

/** The sum of the amounts that can be read, in cents. */
export function total(lines: BillLine[]): string {
  let sum = ZERO
  for (const line of lines) {
    const amount = parseNumber(line.amount, AMOUNT_SCALE)
    if (amount) sum = add(sum, rescale(amount, 2))
  }
  return cents(sum)
}

/** A problem of one cell: the key of its message (namespace `payables.problem`). */
export type LineProblems = Partial<Record<LineColumn, string>>

export interface Lookups {
  accounts?: ReadonlyMap<string, { active: boolean; summary: boolean }>
  taxCodes?: ReadonlySet<string>
  departments?: ReadonlySet<string>
}

/** What can be told while typing; blank lines have no problems (they are left out). */
export function checkLine(line: BillLine, lookups: Lookups = {}): LineProblems {
  if (isBlank(line)) return {}
  const problems: LineProblems = {}
  if (line.description.trim() === '') problems.description = 'descriptionMissing'
  const amount = parseNumber(line.amount, AMOUNT_SCALE)
  if (!amount) problems.amount = line.amount.trim() === '' ? 'amountMissing' : 'amountFormat'
  else if (sign(amount) <= 0) problems.amount = 'amountPositive'
  const account = line.account.trim()
  if (account && lookups.accounts) {
    const found = lookups.accounts.get(account)
    if (!found) problems.account = 'accountUnknown'
    else if (found.summary || !found.active) problems.account = 'accountNotPostable'
  }
  const taxCode = line.useTaxCode.trim()
  if (taxCode && lookups.taxCodes && !lookups.taxCodes.has(taxCode)) problems.useTaxCode = 'taxCodeUnknown'
  const department = line.department.trim()
  if (department && lookups.departments && !lookups.departments.has(department)) {
    problems.department = 'departmentUnknown'
  }
  const form = line.form1099.trim()
  const box = line.box1099.trim()
  if (form && !BOXES[form]) problems.form1099 = 'formUnknown'
  else if (form && box && !BOXES[form].includes(box)) problems.box1099 = 'boxUnknown'
  else if (!form && box) problems.form1099 = 'formMissing'
  return problems
}

export function hasProblems(problems: LineProblems[]): boolean {
  return problems.some((p) => Object.keys(p).length > 0)
}

/**
 * The lines to save, without the blank ones, and for each the row it was typed in: the server's refusals name lines
 * by their place in what was sent ({@code lines[2].amount}). An account left out is the vendor's expense account, a
 * form left out the vendor's 1099 form and box (the server fills them in).
 */
export function toInputs(lines: BillLine[]): { inputs: LineInput[]; rows: number[] } {
  const inputs: LineInput[] = []
  const rows: number[] = []
  lines.forEach((line, row) => {
    if (isBlank(line)) return
    rows.push(row)
    const amount = parseNumber(line.amount, AMOUNT_SCALE)
    inputs.push({
      description: line.description.trim(),
      amount: amount ? formatDecimal(amount) : line.amount.trim(),
      account: line.account.trim() || null,
      useTaxCode: line.useTaxCode.trim() || null,
      department: line.department.trim() || null,
      form1099: line.form1099.trim() || null,
      box1099: line.box1099.trim() || null,
      location: line.location ?? null,
    })
  })
  return { inputs, rows }
}

/** Stored lines as text, amounts with their cents. */
export function fromStored(lines: StoredBillLine[]): BillLine[] {
  return [...lines]
    .sort((a, b) => Number(a.lineNo) - Number(b.lineNo))
    .map((line) => ({
      description: line.description ?? '',
      amount: cents(stored(line.amount)),
      account: line.account ?? '',
      useTaxCode: line.useTaxCode ?? '',
      department: line.department ?? '',
      form1099: line.form1099 ?? '',
      box1099: line.box1099 ?? '',
      location: line.location ?? null,
    }))
}

/** A refusal's messages placed on the cells they name ({@code lines[1].amount}); the rest are general. */
export interface ServerProblems {
  cells: Map<string, string>
  general: string[]
  /** Whether the server asks to confirm a bill like another of the vendor's, with a reason (FIN-AP-005). */
  possibleDuplicate: boolean
}

export const cellKey = (row: number, column: LineColumn) => `${row}:${column}`

export const POSSIBLE_DUPLICATE = 'FIN_BILL_POSSIBLE_DUPLICATE'

export function placeViolations(violations: Violation[], rows: number[]): ServerProblems {
  const cells = new Map<string, string>()
  const general: string[] = []
  let possibleDuplicate = false
  for (const violation of violations) {
    if (violation.ruleCode === POSSIBLE_DUPLICATE) possibleDuplicate = true
    const match = /^lines\[(\d+)]\.(\w+)$/.exec(violation.field ?? '')
    const row = match ? rows[Number(match[1])] : undefined
    const column = match?.[2] as LineColumn | undefined
    if (row !== undefined && column && (LINE_COLUMNS as readonly string[]).includes(column)) {
      cells.set(cellKey(row, column), violation.message)
    } else {
      general.push(violation.message)
    }
  }
  return { cells, general, possibleDuplicate }
}
