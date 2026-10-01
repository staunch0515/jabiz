import { formatDecimal, type Violation } from '@jabiz/admin'
import type { LineInput, StoredInvoiceLine } from './api'
import { add, cents, multiply, parseNumber, rescale, sign, significantScale, stored, ZERO } from './money'

/**
 * The lines of an invoice or credit memo as data (FIN-AR-003, FIN-UI-002): text as typed, the amount of each line
 * and the subtotal for showing while typing, and the checks that can be made then. The server computes the amounts,
 * the tax and the totals again when the document is saved and posted; what it says is what counts.
 */

export const LINE_COLUMNS = ['description', 'quantity', 'unitPrice', 'revenueAccount', 'taxCode'] as const
export type LineColumn = (typeof LINE_COLUMNS)[number]
export type InvoiceLine = Record<LineColumn, string>

/** The server's limits: a quantity and a price take up to four places (FinInvoiceLine). */
export const QUANTITY_SCALE = 4
export const PRICE_SCALE = 4
export const MAX_LINES = 500

export function emptyLine(): InvoiceLine {
  return { description: '', quantity: '', unitPrice: '', revenueAccount: '', taxCode: '' }
}

export function isBlank(line: InvoiceLine): boolean {
  return LINE_COLUMNS.every((column) => line[column].trim() === '')
}

/** Lines to show: at least {@code count}. */
export function padLines(lines: InvoiceLine[], count = 1): InvoiceLine[] {
  const padded = [...lines]
  while (padded.length < count) padded.push(emptyLine())
  return padded
}

/** The amount of a line in cents, rounded as the server rounds it; null until quantity and price can be read. */
export function lineAmount(line: InvoiceLine): string | null {
  const quantity = parseNumber(line.quantity, QUANTITY_SCALE)
  const price = parseNumber(line.unitPrice, PRICE_SCALE)
  if (!quantity || !price) return null
  return cents(multiply(quantity, price))
}

/** The sum of the lines' amounts that can be read. */
export function subtotal(lines: InvoiceLine[]): string {
  let total = ZERO
  for (const line of lines) {
    const amount = lineAmount(line)
    if (amount !== null) total = add(total, rescale(stored(amount), 2))
  }
  return cents(total)
}

/** A problem of one cell: the key of its message (namespace `receivables.problem`). */
export type LineProblems = Partial<Record<LineColumn, string>>

/** What can be told while typing; blank lines have no problems (they are left out). */
export function checkLine(line: InvoiceLine, accounts?: ReadonlyMap<string, { active: boolean; summary: boolean }>,
  taxCodes?: ReadonlySet<string>): LineProblems {
  if (isBlank(line)) return {}
  const problems: LineProblems = {}
  if (line.description.trim() === '') problems.description = 'descriptionMissing'
  const quantity = parseNumber(line.quantity, QUANTITY_SCALE)
  if (!quantity) problems.quantity = line.quantity.trim() === '' ? 'quantityMissing' : 'quantityFormat'
  else if (sign(quantity) <= 0) problems.quantity = 'quantityPositive'
  if (!parseNumber(line.unitPrice, PRICE_SCALE)) {
    problems.unitPrice = line.unitPrice.trim() === '' ? 'priceMissing' : 'priceFormat'
  }
  const account = line.revenueAccount.trim()
  if (account && accounts) {
    const found = accounts.get(account)
    if (!found) problems.revenueAccount = 'accountUnknown'
    else if (found.summary || !found.active) problems.revenueAccount = 'accountNotPostable'
  }
  const code = line.taxCode.trim()
  if (code && taxCodes && !taxCodes.has(code)) problems.taxCode = 'taxCodeUnknown'
  return problems
}

export function hasProblems(problems: LineProblems[]): boolean {
  return problems.some((p) => Object.keys(p).length > 0)
}

/**
 * The lines to save, without the blank ones, and for each the row it was typed in: the server's refusals name
 * lines by their place in what was sent ({@code lines[2].quantity}).
 */
export function toInputs(lines: InvoiceLine[]): { inputs: LineInput[]; rows: number[] } {
  const inputs: LineInput[] = []
  const rows: number[] = []
  lines.forEach((line, row) => {
    if (isBlank(line)) return
    rows.push(row)
    inputs.push({
      description: line.description.trim(),
      quantity: plain(line.quantity, QUANTITY_SCALE),
      unitPrice: plain(line.unitPrice, PRICE_SCALE),
      revenueAccount: line.revenueAccount.trim() || null,
      taxCode: line.taxCode.trim() || null,
    })
  })
  return { inputs, rows }
}

/** The number without separators, as the server reads it; the text as typed when it is not a number. */
function plain(text: string, scale: number): string {
  const value = parseNumber(text, scale)
  return value ? formatDecimal(value) : text.trim()
}

/** Stored lines as text: quantities without trailing zeros, prices with their cents. */
export function fromStored(lines: StoredInvoiceLine[]): InvoiceLine[] {
  return [...lines]
    .sort((a, b) => Number(a.lineNo) - Number(b.lineNo))
    .map((line) => ({
      description: line.description ?? '',
      quantity: trimmed(line.quantity, 0),
      unitPrice: trimmed(line.unitPrice, 2),
      revenueAccount: line.revenueAccount ?? '',
      taxCode: line.taxCode ?? '',
    }))
}

function trimmed(value: unknown, minScale: number): string {
  const d = stored(value)
  return formatDecimal(rescale(d, Math.max(significantScale(d), minScale)))
}

/** A refusal's messages placed on the cells they name ({@code lines[1].unitPrice}); the rest are general. */
export interface ServerProblems {
  cells: Map<string, string>
  general: string[]
}

export const cellKey = (row: number, column: LineColumn) => `${row}:${column}`

export function placeViolations(violations: Violation[], rows: number[]): ServerProblems {
  const cells = new Map<string, string>()
  const general: string[] = []
  for (const violation of violations) {
    const match = /^lines\[(\d+)]\.(\w+)$/.exec(violation.field ?? '')
    const row = match ? rows[Number(match[1])] : undefined
    const column = match?.[2] as LineColumn | undefined
    if (row !== undefined && column && (LINE_COLUMNS as readonly string[]).includes(column)) {
      cells.set(cellKey(row, column), violation.message)
    } else {
      general.push(violation.message)
    }
  }
  return { cells, general }
}
